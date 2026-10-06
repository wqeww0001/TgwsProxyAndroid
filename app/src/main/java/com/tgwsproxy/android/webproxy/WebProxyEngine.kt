package com.tgwsproxy.android.webproxy

import com.tgwsproxy.android.proxy.ProxyLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Local `127.0.0.1:1443` listener and `tproxy-v1` multiplexed session engine.
 * Bridges local Telegram MTProto connections through a Telegram WEB Proxy (`t.me/webproxy`).
 */
object WebProxyEngine {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var engineScope: CoroutineScope? = null
    private var currentSession: RelaySession? = null
    private val sessionMutex = Mutex()

    private val activeConnections = AtomicLong(0)
    private val totalConnections = AtomicLong(0)
    private val activeSessions = AtomicLong(0)
    private val sessionAttempts = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private val upBytes = AtomicLong(0)
    private val downBytes = AtomicLong(0)
    @Volatile private var lastErrorMessage: String = ""
    @Volatile private var activeEndpoint: WebProxyProtocol.WebProxyEndpoint? = null
    @Volatile private var activeCarrierMode: String = ""

    fun isRunning(): Boolean = running.get()

    fun getActiveCarrierMode(): String = activeCarrierMode

    fun getLastError(): String = lastErrorMessage

    fun start(
        bindHost: String,
        bindPort: Int,
        endpoint: WebProxyProtocol.WebProxyEndpoint,
    ): Int {
        if (!running.compareAndSet(false, true)) {
            stop()
            running.set(true)
        }

        activeConnections.set(0)
        totalConnections.set(0)
        activeSessions.set(0)
        sessionAttempts.set(0)
        errorCount.set(0)
        upBytes.set(0)
        downBytes.set(0)
        lastErrorMessage = ""
        activeEndpoint = endpoint
        activeCarrierMode = "connecting"

        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(bindHost), bindPort), 128)
            serverSocket = socket

            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            engineScope = scope

            ProxyLogger.i(
                "WebProxy engine listening on $bindHost:$bindPort -> https://${endpoint.serverField} (tproxy-v1)",
            )

            // Warm up the initial tproxy-v1 relay session in the background
            scope.launch {
                runCatching { getOrCreateSession(endpoint, scope) }
                    .onFailure { recordError("Initial WebProxy session warmup failed: ${it.message}") }
            }

            // Accept loop for local Telegram client connections
            scope.launch {
                acceptLoop(socket, endpoint, scope)
            }
            0
        } catch (t: Throwable) {
            recordError("Failed to bind WebProxy listener on $bindHost:$bindPort: ${t.message}")
            running.set(false)
            runCatching { serverSocket?.close() }
            serverSocket = null
            -1
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        ProxyLogger.i("Stopping WebProxy engine")
        runCatching { serverSocket?.close() }
        serverSocket = null
        val session = currentSession
        currentSession = null
        session?.close("Engine stopped")
        engineScope?.cancel()
        engineScope = null
        activeConnections.set(0)
        activeSessions.set(0)
        activeCarrierMode = ""
    }

    fun getStats(): String {
        val active = activeConnections.get()
        val total = totalConnections.get()
        val ws = activeSessions.get()
        val tries = sessionAttempts.get()
        val err = errorCount.get()
        val up = upBytes.get()
        val down = downBytes.get()
        return "active=$active total=$total ws=$ws cf=0 tcp_fb=0 ws_try=$tries err=$err " +
            "up=${formatBytes(up)} down=${formatBytes(down)} up_b=$up down_b=$down"
    }

    private fun recordError(message: String, t: Throwable? = null) {
        lastErrorMessage = message
        errorCount.incrementAndGet()
        if (t != null) {
            ProxyLogger.e("WebProxy: $message", t)
        } else {
            ProxyLogger.e("WebProxy: $message")
        }
    }

    private suspend fun acceptLoop(
        listener: ServerSocket,
        endpoint: WebProxyProtocol.WebProxyEndpoint,
        scope: CoroutineScope,
    ) {
        while (scope.isActive && running.get() && !listener.isClosed) {
            val client = try {
                listener.accept()
            } catch (_: Throwable) {
                if (!running.get() || listener.isClosed) break
                continue
            }

            // Ignore quick TCP port probes (watchdog connects and closes immediately with 0 bytes)
            scope.launch {
                handleClientSocket(client, endpoint, scope)
            }
        }
    }

    private suspend fun handleClientSocket(
        client: Socket,
        endpoint: WebProxyProtocol.WebProxyEndpoint,
        scope: CoroutineScope,
    ) {
        runCatching {
            client.tcpNoDelay = true
            client.soTimeout = 0
        }

        val input = try {
            client.getInputStream()
        } catch (_: Throwable) {
            runCatching { client.close() }
            return
        }

        // Wait for the first byte from Telegram before opening a remote stream,
        // so local watchdog port checks don't churn remote tproxy-v1 streams.
        val firstBuffer = ByteArray(WebProxyProtocol.MAX_DATA_CHUNK_SIZE)
        val firstRead = try {
            input.read(firstBuffer)
        } catch (_: Throwable) {
            -1
        }
        if (firstRead <= 0) {
            runCatching { client.close() }
            return
        }

        totalConnections.incrementAndGet()
        activeConnections.incrementAndGet()
        try {
            val session = getOrCreateSession(endpoint, scope)
            session.bridgeStream(client, input, firstBuffer.copyOfRange(0, firstRead))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            recordError("Stream failure: ${t.message}")
            runCatching { client.close() }
        } finally {
            activeConnections.decrementAndGet()
        }
    }

    private suspend fun getOrCreateSession(
        endpoint: WebProxyProtocol.WebProxyEndpoint,
        scope: CoroutineScope,
    ): RelaySession = sessionMutex.withLock {
        val existing = currentSession
        if (existing != null && existing.isAlive()) {
            return existing
        }
        existing?.close("Replacing dead session")

        sessionAttempts.incrementAndGet()
        val created = createRelaySession(endpoint, scope)
        currentSession = created
        activeSessions.set(1)
        activeCarrierMode = created.carrierMode
        ProxyLogger.i("WebProxy session established: host=${endpoint.serverField} mode=${created.carrierMode}")
        return created
    }

    private suspend fun createRelaySession(
        endpoint: WebProxyProtocol.WebProxyEndpoint,
        parentScope: CoroutineScope,
    ): RelaySession {
        // 1. Fetch GET https://<host><base>?bridge=<bridge>
        val bridgeHtml = fetchBridgeDocument(endpoint)
        val (bootstrapToken, _) = WebProxyProtocol.extractBridgeBootstrap(bridgeHtml)
            ?: throw IllegalStateException("Bridge page did not contain a valid bootstrap token")

        // 2. Exchange bootstrap token at POST <base>api/v1/session with HELLO frame
        val sessionUrl = "https://${endpoint.host}${endpoint.basePrefix}api/v1/session"
        val helloBytes = WebProxyProtocol.helloFrame()

        var attempt = 0
        while (true) {
            attempt++
            val conn = openHttpsConnection(sessionUrl, "POST", endpoint.host)
            conn.setRequestProperty("Authorization", "Bearer $bootstrapToken")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(helloBytes.size)
            conn.outputStream.use { it.write(helloBytes) }

            val status = conn.responseCode
            if (status == 503 && attempt < 6) {
                val retryAfterSec = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(1L, 10L) ?: 1L
                conn.disconnect()
                delay(retryAfterSec * 1000L)
                continue
            }
            if (status != 200) {
                conn.disconnect()
                throw IllegalStateException("Session creation failed with HTTP $status")
            }

            val sessionToken = conn.getHeaderField("X-Session-Token")?.trim().orEmpty()
            val carrierMode = conn.getHeaderField("X-Carrier-Mode")?.trim()?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotEmpty() } ?: "https"
            val initialCursor = conn.getHeaderField("X-Down-Cursor")?.trim()?.takeIf { it.isNotEmpty() } ?: "0"
            val responseBytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()

            if (sessionToken.length != 43) {
                throw IllegalStateException("Invalid X-Session-Token from relay")
            }
            val frames = WebProxyProtocol.decodeFrames(responseBytes)
            if (frames.size != 1 || frames[0].type != WebProxyProtocol.TYPE_WELCOME || frames[0].streamId != 0) {
                throw IllegalStateException("Expected single WELCOME frame from relay")
            }

            val session = RelaySession(
                endpoint = endpoint,
                sessionToken = sessionToken,
                carrierMode = carrierMode,
                initialDownCursor = initialCursor,
                parentScope = parentScope,
            )
            session.startCarrier()
            return session
        }
    }

    private fun fetchBridgeDocument(endpoint: WebProxyProtocol.WebProxyEndpoint): String {
        val conn = openHttpsConnection(endpoint.bridgeUrl, "GET", endpoint.host)
        try {
            val code = conn.responseCode
            if (code != 200) {
                throw IllegalStateException("Bridge GET returned HTTP $code (check server/secret)")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openHttpsConnection(url: String, method: String, host: String): HttpsURLConnection {
        val conn = (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = 10_000
            readTimeout = 45_000
            setRequestProperty("Cache-Control", "no-store")
            setRequestProperty("Origin", "https://$host")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
        }
        return conn
    }

    // =========================================================================
    // Multiplexed tproxy-v1 Session
    // =========================================================================

    private class StreamState(
        val streamId: Int,
        val socket: Socket,
        val output: OutputStream,
    ) {
        val closed = AtomicBoolean(false)
        private var sendWindowBytes: Long = WebProxyProtocol.INITIAL_STREAM_WINDOW.toLong()
        private val windowLock = java.util.concurrent.locks.ReentrantLock()
        private val windowCondition = windowLock.newCondition()
        val laneOutbound = Channel<ByteArray>(capacity = 256)

        fun consumeSendWindow(requested: Int): Int {
            windowLock.lock()
            try {
                while (sendWindowBytes <= 0L && !closed.get()) {
                    windowCondition.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                if (closed.get()) return -1
                val granted = minOf(requested.toLong(), sendWindowBytes).toInt()
                sendWindowBytes -= granted
                return granted
            } finally {
                windowLock.unlock()
            }
        }

        fun grantSendWindow(delta: Long) {
            windowLock.lock()
            try {
                sendWindowBytes += delta
                windowCondition.signalAll()
            } finally {
                windowLock.unlock()
            }
        }

        fun markClosed() {
            if (closed.compareAndSet(false, true)) {
                windowLock.lock()
                try {
                    windowCondition.signalAll()
                } finally {
                    windowLock.unlock()
                }
                laneOutbound.close()
                runCatching { socket.close() }
            }
        }
    }

    private class RelaySession(
        val endpoint: WebProxyProtocol.WebProxyEndpoint,
        val sessionToken: String,
        val carrierMode: String,
        val initialDownCursor: String,
        parentScope: CoroutineScope,
    ) {
        private val sessionScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
        private val alive = AtomicBoolean(true)
        private val nextStreamId = AtomicInteger(1)
        private val streams = ConcurrentHashMap<Int, StreamState>()
        private val outboundFrames = Channel<ByteArray>(capacity = 1024)
        private var carrierJob: Job? = null

        fun isAlive(): Boolean = alive.get() && sessionScope.isActive

        fun startCarrier() {
            carrierJob = sessionScope.launch {
                try {
                    when (carrierMode) {
                        "websocket" -> runMultiplexedWebSocketCarrier()
                        "websocket-lanes" -> {
                            // Each stream starts its own lane WebSocket in bridgeStream()
                            while (isActive && alive.get()) {
                                delay(5_000)
                            }
                        }
                        "https-lanes" -> {
                            // Lane 0 handles session-level PONGs if any; streams run their own lane in bridgeStream()
                            while (isActive && alive.get()) {
                                delay(5_000)
                            }
                        }
                        else -> runSerializedHttpsCarrier()
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    if (alive.get()) {
                        recordError("Carrier ($carrierMode) terminated: ${t.message}")
                        close("Carrier error")
                    }
                }
            }
        }

        suspend fun bridgeStream(
            socket: Socket,
            input: InputStream,
            initialBytes: ByteArray,
        ) {
            if (!isAlive()) throw IllegalStateException("RelaySession is closed")
            val streamId = nextStreamId.getAndIncrement()
            if (streamId > 0xFFFFFF) {
                close("Stream ID space exhausted")
                throw IllegalStateException("Stream ID space exhausted")
            }

            val output = socket.getOutputStream()
            val state = StreamState(streamId, socket, output)
            streams[streamId] = state

            var laneJob: Job? = null
            try {
                when (carrierMode) {
                    "websocket-lanes" -> {
                        laneJob = sessionScope.launch {
                            runWebSocketLane(state)
                        }
                    }
                    "https-lanes" -> {
                        laneJob = sessionScope.launch {
                            runHttpsLane(state)
                        }
                    }
                }

                // 1. Send OPEN frame
                enqueueFrame(state, WebProxyProtocol.openFrame(streamId))

                // 2. Send initial bytes read from local Telegram client
                sendDataRespectingWindow(state, initialBytes, initialBytes.size)

                // 3. Pump subsequent bytes from local Telegram socket -> DATA frames
                val buf = ByteArray(WebProxyProtocol.MAX_DATA_CHUNK_SIZE)
                while (isAlive() && !state.closed.get()) {
                    val n = try {
                        input.read(buf)
                    } catch (_: Throwable) {
                        -1
                    }
                    if (n <= 0) break
                    sendDataRespectingWindow(state, buf, n)
                }
            } finally {
                val wasOpen = !state.closed.get()
                state.markClosed()
                streams.remove(streamId)
                if (wasOpen && isAlive()) {
                    runCatching {
                        if (carrierMode == "websocket" || carrierMode == "https") {
                            outboundFrames.trySend(WebProxyProtocol.closeFrame(streamId))
                        }
                    }
                }
                laneJob?.cancel()
            }
        }

        private suspend fun sendDataRespectingWindow(state: StreamState, buffer: ByteArray, length: Int) {
            var offset = 0
            while (offset < length && !state.closed.get() && isAlive()) {
                val remaining = length - offset
                val chunkMax = minOf(remaining, WebProxyProtocol.MAX_DATA_CHUNK_SIZE)
                val granted = state.consumeSendWindow(chunkMax)
                if (granted <= 0) break
                val frame = WebProxyProtocol.dataFrame(state.streamId, buffer, offset, granted)
                enqueueFrame(state, frame)
                upBytes.addAndGet(granted.toLong())
                offset += granted
            }
        }

        private suspend fun enqueueFrame(state: StreamState, frame: ByteArray) {
            if (carrierMode == "websocket-lanes" || carrierMode == "https-lanes") {
                state.laneOutbound.send(frame)
            } else {
                outboundFrames.send(frame)
            }
        }

        private fun handleIncomingFrames(batch: ByteArray) {
            val frames = WebProxyProtocol.decodeFrames(batch)
            for (frame in frames) {
                when (frame.type) {
                    WebProxyProtocol.TYPE_DATA -> {
                        val stream = streams[frame.streamId] ?: continue
                        if (stream.closed.get()) continue
                        try {
                            synchronized(stream.output) {
                                stream.output.write(frame.payload)
                                stream.output.flush()
                            }
                            downBytes.addAndGet(frame.payload.size.toLong())
                            val windowUpdate = WebProxyProtocol.windowFrame(frame.streamId, frame.payload.size)
                            if (carrierMode == "websocket-lanes" || carrierMode == "https-lanes") {
                                stream.laneOutbound.trySend(windowUpdate)
                            } else {
                                outboundFrames.trySend(windowUpdate)
                            }
                        } catch (_: Throwable) {
                            stream.markClosed()
                            streams.remove(frame.streamId)
                            outboundFrames.trySend(WebProxyProtocol.closeFrame(frame.streamId))
                        }
                    }
                    WebProxyProtocol.TYPE_WINDOW -> {
                        val stream = streams[frame.streamId] ?: continue
                        val delta = WebProxyProtocol.parseWindowDelta(frame.payload)
                        stream.grantSendWindow(delta)
                    }
                    WebProxyProtocol.TYPE_CLOSE -> {
                        val stream = streams.remove(frame.streamId)
                        stream?.markClosed()
                    }
                    WebProxyProtocol.TYPE_PING -> {
                        val pong = WebProxyProtocol.pongFrame(frame.payload)
                        outboundFrames.trySend(pong)
                    }
                    WebProxyProtocol.TYPE_BYE -> {
                        close("Relay sent BYE")
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // Carrier 1: Serialized HTTPS (/api/v1/up + /api/v1/down)
        // ---------------------------------------------------------------------

        private suspend fun runSerializedHttpsCarrier() {
            val upUrl = "https://${endpoint.host}${endpoint.basePrefix}api/v1/up"
            val downUrl = "https://${endpoint.host}${endpoint.basePrefix}api/v1/down"

            val upJob = sessionScope.launch {
                var upSeq = 1L
                while (isActive && alive.get()) {
                    val first = outboundFrames.receiveCatching().getOrNull() ?: break
                    val batch = coalesceFrames(first, outboundFrames)
                    postUplinkBatch(upUrl, upSeq, batch, laneId = null)
                    upSeq++
                }
            }

            val downJob = sessionScope.launch {
                var cursor = initialDownCursor
                while (isActive && alive.get()) {
                    val conn = openHttpsConnection(downUrl, "POST", endpoint.host)
                    conn.setRequestProperty("Authorization", "Bearer $sessionToken")
                    conn.setRequestProperty("X-Down-Cursor", cursor)
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(0)
                    conn.outputStream.close()

                    val code = conn.responseCode
                    val nextCursor = conn.getHeaderField("X-Down-Cursor")?.trim()
                    if (!nextCursor.isNullOrEmpty()) {
                        cursor = nextCursor
                    }
                    when (code) {
                        200 -> {
                            val body = conn.inputStream.use { it.readBytes() }
                            conn.disconnect()
                            if (body.isNotEmpty()) {
                                handleIncomingFrames(body)
                            }
                        }
                        204 -> {
                            conn.disconnect()
                        }
                        503 -> {
                            conn.disconnect()
                            delay(1000)
                        }
                        else -> {
                            conn.disconnect()
                            throw IllegalStateException("Downlink poll returned HTTP $code")
                        }
                    }
                }
            }

            upJob.join()
            downJob.cancel()
        }

        // ---------------------------------------------------------------------
        // Carrier 2: Stream-aware HTTPS Lanes (X-Lane-ID)
        // ---------------------------------------------------------------------

        private suspend fun runHttpsLane(state: StreamState) {
            val upUrl = "https://${endpoint.host}${endpoint.basePrefix}api/v1/up"
            val downUrl = "https://${endpoint.host}${endpoint.basePrefix}api/v1/down"
            val laneId = state.streamId

            val upJob = sessionScope.launch {
                var upSeq = 1L
                while (isActive && alive.get() && !state.closed.get()) {
                    val first = state.laneOutbound.receiveCatching().getOrNull() ?: break
                    val batch = coalesceFrames(first, state.laneOutbound)
                    postUplinkBatch(upUrl, upSeq, batch, laneId = laneId)
                    upSeq++
                }
                if (alive.get()) {
                    runCatching {
                        postUplinkBatch(upUrl, upSeq, WebProxyProtocol.closeFrame(laneId), laneId = laneId)
                    }
                }
            }

            val downJob = sessionScope.launch {
                var cursor = "0"
                while (isActive && alive.get() && !state.closed.get()) {
                    val conn = openHttpsConnection(downUrl, "POST", endpoint.host)
                    conn.setRequestProperty("Authorization", "Bearer $sessionToken")
                    conn.setRequestProperty("X-Down-Cursor", cursor)
                    conn.setRequestProperty("X-Lane-ID", laneId.toString())
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(0)
                    conn.outputStream.close()

                    val code = conn.responseCode
                    val laneClosed = conn.getHeaderField("X-Lane-Closed")?.trim() == "1"
                    val nextCursor = conn.getHeaderField("X-Down-Cursor")?.trim()
                    if (!nextCursor.isNullOrEmpty()) cursor = nextCursor

                    when (code) {
                        200 -> {
                            val body = conn.inputStream.use { it.readBytes() }
                            conn.disconnect()
                            if (body.isNotEmpty()) handleIncomingFrames(body)
                            if (laneClosed) break
                        }
                        204 -> {
                            conn.disconnect()
                            if (laneClosed) break
                        }
                        503 -> {
                            conn.disconnect()
                            delay(1000)
                        }
                        else -> {
                            conn.disconnect()
                            break
                        }
                    }
                }
                state.markClosed()
            }

            upJob.join()
            downJob.cancel()
        }

        private suspend fun postUplinkBatch(url: String, seq: Long, batch: ByteArray, laneId: Int?) {
            var retries = 0
            while (alive.get()) {
                val conn = openHttpsConnection(url, "POST", endpoint.host)
                conn.setRequestProperty("Authorization", "Bearer $sessionToken")
                conn.setRequestProperty("X-Up-Seq", seq.toString())
                if (laneId != null) {
                    conn.setRequestProperty("X-Lane-ID", laneId.toString())
                }
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(batch.size)
                conn.outputStream.use { it.write(batch) }

                val code = conn.responseCode
                conn.disconnect()
                if (code == 204 || code == 200) return
                if (code == 503 && retries < 30) {
                    retries++
                    delay(1000)
                    continue
                }
                throw IllegalStateException("Uplink POST failed with HTTP $code")
            }
        }

        // ---------------------------------------------------------------------
        // Carrier 3 & 4: Multiplexed WebSocket & WebSocket Lanes (RFC 6455 over TLS)
        // ---------------------------------------------------------------------

        private suspend fun runMultiplexedWebSocketCarrier() {
            val subprotocol = "tproxy-v1.$sessionToken"
            val ws = openWebSocket(endpoint.host, "${endpoint.basePrefix}api/v1/ws", subprotocol)
            try {
                val writerJob = sessionScope.launch {
                    while (isActive && alive.get()) {
                        val first = outboundFrames.receiveCatching().getOrNull() ?: break
                        val batch = coalesceFrames(first, outboundFrames)
                        ws.sendBinary(batch)
                    }
                }
                while (sessionScope.isActive && alive.get()) {
                    val payload = ws.readBinaryFrame() ?: break
                    if (payload.isNotEmpty()) {
                        handleIncomingFrames(payload)
                    }
                }
                writerJob.cancel()
            } finally {
                ws.close()
            }
        }

        private suspend fun runWebSocketLane(state: StreamState) {
            val subprotocol = "tproxy-lane-v1.$sessionToken.${state.streamId}"
            val ws = openWebSocket(endpoint.host, "${endpoint.basePrefix}api/v1/ws", subprotocol)
            try {
                val writerJob = sessionScope.launch {
                    while (isActive && alive.get() && !state.closed.get()) {
                        val first = state.laneOutbound.receiveCatching().getOrNull() ?: break
                        val batch = coalesceFrames(first, state.laneOutbound)
                        ws.sendBinary(batch)
                    }
                    runCatching { ws.sendBinary(WebProxyProtocol.closeFrame(state.streamId)) }
                }
                while (sessionScope.isActive && alive.get() && !state.closed.get()) {
                    val payload = ws.readBinaryFrame() ?: break
                    if (payload.isNotEmpty()) {
                        handleIncomingFrames(payload)
                    }
                }
                writerJob.cancel()
            } finally {
                ws.close()
                state.markClosed()
            }
        }

        private fun coalesceFrames(first: ByteArray, channel: Channel<ByteArray>): ByteArray {
            val out = ByteArrayOutputStream(first.size.coerceAtLeast(4096))
            out.write(first)
            var count = 1
            while (count < WebProxyProtocol.MAX_BATCH_FRAMES && out.size() < WebProxyProtocol.DEFAULT_BATCH_LIMIT_BYTES) {
                val next = channel.tryReceive().getOrNull() ?: break
                if (out.size() + next.size > WebProxyProtocol.DEFAULT_BATCH_LIMIT_BYTES) {
                    // Put back or send in current batch if still within 2 MiB
                    out.write(next)
                    break
                }
                out.write(next)
                count++
            }
            return out.toByteArray()
        }

        fun close(reason: String) {
            if (!alive.compareAndSet(true, false)) return
            ProxyLogger.d("Closing WebProxy session: $reason")
            if (currentSession === this) {
                activeSessions.set(0)
            }
            outboundFrames.close()
            for (stream in streams.values) {
                stream.markClosed()
            }
            streams.clear()
            carrierJob?.cancel()
            sessionScope.cancel()
        }
    }

    // =========================================================================
    // Minimal RFC 6455 Binary WebSocket Client over TLS 1.2/1.3
    // =========================================================================

    private class TlsWebSocket(
        private val socket: SSLSocket,
        private val input: BufferedInputStream,
        private val output: BufferedOutputStream,
    ) {
        private val writeLock = Any()
        private val random = SecureRandom()

        fun sendBinary(payload: ByteArray) {
            sendFrame(opcode = 0x02, payload = payload)
        }

        private fun sendFrame(opcode: Int, payload: ByteArray) {
            val maskKey = ByteArray(4)
            random.nextBytes(maskKey)
            synchronized(writeLock) {
                output.write(0x80 or (opcode and 0x0F))
                val size = payload.size
                when {
                    size <= 125 -> output.write(0x80 or size)
                    size <= 65535 -> {
                        output.write(0x80 or 126)
                        output.write((size ushr 8) and 0xFF)
                        output.write(size and 0xFF)
                    }
                    else -> {
                        output.write(0x80 or 127)
                        val len = size.toLong()
                        for (shift in 56 downTo 0 step 8) {
                            output.write(((len ushr shift) and 0xFF).toInt())
                        }
                    }
                }
                output.write(maskKey)
                val masked = ByteArray(size)
                for (i in 0 until size) {
                    masked[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                }
                output.write(masked)
                output.flush()
            }
        }

        fun readBinaryFrame(): ByteArray? {
            var messageBuffer: ByteArrayOutputStream? = null
            while (true) {
                val b0 = input.read()
                if (b0 == -1) return null
                val b1 = input.read()
                if (b1 == -1) return null

                val fin = (b0 and 0x80) != 0
                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var length = (b1 and 0x7F).toLong()

                if (length == 126L) {
                    val hi = input.read()
                    val lo = input.read()
                    if (hi == -1 || lo == -1) return null
                    length = ((hi shl 8) or lo).toLong()
                } else if (length == 127L) {
                    length = 0
                    for (i in 0 until 8) {
                        val b = input.read()
                        if (b == -1) return null
                        length = (length shl 8) or b.toLong()
                    }
                }

                if (length < 0L || length > 4L * 1024L * 1024L) {
                    throw IllegalStateException("WebSocket frame exceeds 4 MiB limit: $length")
                }

                val maskKey = if (masked) {
                    val key = ByteArray(4)
                    readFully(input, key)
                    key
                } else null

                val payload = ByteArray(length.toInt())
                readFully(input, payload)
                if (maskKey != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x02 -> { // Binary frame
                        if (fin && messageBuffer == null) return payload
                        if (messageBuffer == null) messageBuffer = ByteArrayOutputStream()
                        messageBuffer.write(payload)
                        if (fin) return messageBuffer.toByteArray()
                    }
                    0x00 -> { // Continuation frame
                        val buf = messageBuffer ?: throw IllegalStateException("Unexpected WebSocket continuation")
                        buf.write(payload)
                        if (fin) return buf.toByteArray()
                    }
                    0x08 -> { // Close
                        runCatching { sendFrame(0x08, WebProxyProtocol.EMPTY_BYTES) }
                        return null
                    }
                    0x09 -> { // Ping -> reply Pong
                        sendFrame(0x0A, payload)
                    }
                    0x0A -> { // Pong -> ignore
                    }
                    else -> throw IllegalStateException("Unsupported WebSocket opcode: $opcode")
                }
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private fun openWebSocket(host: String, path: String, subprotocol: String): TlsWebSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val rawSocket = Socket()
        rawSocket.tcpNoDelay = true
        rawSocket.soTimeout = 60_000
        rawSocket.connect(InetSocketAddress(host, 443), 10_000)

        val sslSocket = (factory.createSocket(rawSocket, host, 443, true) as SSLSocket).apply {
            sslParameters = sslParameters.apply {
                serverNames = listOf(SNIHostName(host))
            }
            startHandshake()
        }

        val keyBytes = ByteArray(16)
        SecureRandom().nextBytes(keyBytes)
        val wsKey = Base64.getEncoder().encodeToString(keyBytes)

        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Origin: https://$host\r\n")
            append("Sec-WebSocket-Key: $wsKey\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Protocol: $subprotocol\r\n")
            append("User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36\r\n")
            append("\r\n")
        }

        val input = BufferedInputStream(sslSocket.inputStream, 64 * 1024)
        val output = BufferedOutputStream(sslSocket.outputStream, 64 * 1024)
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()

        val statusLine = readHttpLine(input) ?: throw EOFException("Empty WebSocket upgrade response")
        if (!statusLine.startsWith("HTTP/1.1 101")) {
            sslSocket.close()
            throw IllegalStateException("WebSocket upgrade failed: $statusLine")
        }

        var echoedSubprotocol = ""
        var acceptHeader = ""
        while (true) {
            val line = readHttpLine(input) ?: throw EOFException("Truncated WebSocket headers")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                val value = line.substring(colon + 1).trim()
                if (name == "sec-websocket-protocol") echoedSubprotocol = value
                if (name == "sec-websocket-accept") acceptHeader = value
            }
        }

        val expectedAccept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
        )
        if (acceptHeader != expectedAccept) {
            sslSocket.close()
            throw IllegalStateException("Invalid Sec-WebSocket-Accept from relay")
        }
        if (echoedSubprotocol != subprotocol) {
            sslSocket.close()
            throw IllegalStateException("Relay did not echo expected subprotocol")
        }

        return TlsWebSocket(sslSocket, input, output)
    }

    private fun readHttpLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString("US-ASCII")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("US-ASCII")
    }

    private fun readFully(input: InputStream, dst: ByteArray) {
        var offset = 0
        while (offset < dst.size) {
            val n = input.read(dst, offset, dst.size - offset)
            if (n < 0) throw EOFException("Unexpected EOF")
            offset += n
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
            else -> "${bytes}B"
        }
    }
}
