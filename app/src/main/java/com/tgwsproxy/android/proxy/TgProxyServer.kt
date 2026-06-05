package com.tgwsproxy.android.proxy

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class TgProxyServer(
    private val config: ProxyRuntimeConfig,
) : Closeable {
    constructor(host: String, port: Int, secretHex: String) : this(
        ProxyRuntimeConfig(host = host, port = port, secretHex = secretHex),
    )

    private val secret = config.secretHex.hexToBytes()
    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<Socket>()
    private val balancer = DomainBalancer()
    private val wsPool = WsPool(config.poolSize)
    private val wsBlacklist = ConcurrentHashMap.newKeySet<String>()
    private val dcFailUntil = ConcurrentHashMap<String, Long>()
    private val wsDomainFailUntil = ConcurrentHashMap<String, Long>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var cfRefreshThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        ProxyStats.reset()
        initCfProxy()
        warmupPool()

        acceptThread = Thread({
            runCatching {
                ServerSocket(config.port, 50, InetAddress.getByName(config.host)).use { server ->
                    serverSocket = server
                    ProxyLogger.i("Listening on ${config.host}:${config.port}")
                    while (running.get()) {
                        val client = server.accept()
                        client.tcpNoDelay = true
                        client.soTimeout = CLIENT_READ_TIMEOUT_MS
                        clients.add(client)
                        Thread({ handleClient(client) }, "tgws-client").start()
                    }
                }
            }.onFailure {
                if (running.get()) ProxyLogger.e("Server stopped unexpectedly", it)
            }
        }, "tgws-accept").apply { start() }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        wsPool.close()
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        ProxyLogger.i("Server closed. ${ProxyStats.summary()}")
    }

    private fun handleClient(client: Socket) {
        ProxyStats.connectionsTotal.incrementAndGet()
        ProxyStats.connectionsActive.incrementAndGet()
        try {
            val init = readClientInit(client) ?: return
            val parsed = MtprotoCrypto.tryHandshake(init.handshake, secret)
            if (parsed == null) {
                ProxyStats.connectionsBad.incrementAndGet()
                ProxyLogger.w("Bad MTProto handshake")
                return
            }

            val dcIdx = if (parsed.isMedia) -parsed.dcId else parsed.dcId
            val relayInit = MtprotoCrypto.generateRelayInit(parsed.protoTag, dcIdx)
            val ctx = MtprotoCrypto.buildCryptoContext(parsed.clientDecPrekeyIv, secret, relayInit)
            val splitter = MtprotoPacketSplitter(relayInit, parsed.protoTag.toProtoTransport())
            val dcKey = dcKey(parsed.dcId, parsed.isMedia)
            ProxyLogger.i("Client connected: DC${parsed.dcId}${if (parsed.isMedia) " media" else ""}, ${parsed.protoTag}")

            if (config.directTcpFirst || wsBlacklist.contains(dcKey)) {
                bridgeFallback(init.io, parsed.dcId, parsed.isMedia, relayInit, ctx)
                return
            }

            val route = connectWebSocket(parsed.dcId, parsed.isMedia)
            if (route != null) {
                ProxyStats.connectionsWs.incrementAndGet()
                route.ws.send(relayInit)
                val result = bridgeWebSocket(init.io, route.ws, ctx, splitter, route.label)
                updateWsRouteHealth(route.domain, result)
            } else {
                bridgeFallback(init.io, parsed.dcId, parsed.isMedia, relayInit, ctx)
            }
        } catch (ex: Exception) {
            ProxyLogger.w("Client session failed", ex)
            runCatching { client.close() }
        } finally {
            clients.remove(client)
            ProxyStats.connectionsActive.decrementAndGet()
        }
    }

    private fun readClientInit(client: Socket): ClientInit? {
        val input = client.inputStream
        val first = input.read()
        if (first < 0) return null

        if (config.fakeTlsDomain.isNotBlank()) {
            if (first == FakeTls.TLS_RECORD_HANDSHAKE) {
                val headerRest = input.readExact(4)
                val recordLength = ((headerRest[2].toInt() and 0xff) shl 8) or (headerRest[3].toInt() and 0xff)
                val body = input.readExact(recordLength)
                val clientHello = byteArrayOf(first.toByte()) + headerRest + body
                val verified = FakeTls.verifyClientHello(clientHello, secret)
                if (verified == null) {
                    ProxyLogger.i("Fake TLS verify failed, proxying to masking domain ${config.fakeTlsDomain}")
                    FakeTls.proxyToMaskingDomain(client, clientHello, config.fakeTlsDomain)
                    return null
                }
                val (clientRandom, sessionId) = verified
                client.outputStream.write(FakeTls.buildServerHello(secret, clientRandom, sessionId))
                client.outputStream.flush()
                val fake = FakeTlsSocket(client)
                return ClientInit(fake.readExact(HANDSHAKE_LEN), FakeTlsClientIo(fake))
            }

            val redirect = "HTTP/1.1 301 Moved Permanently\r\n" +
                "Location: https://${config.fakeTlsDomain}/\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
            client.outputStream.write(redirect.toByteArray(Charsets.US_ASCII))
            client.outputStream.flush()
            return null
        }

        val rest = input.readExact(HANDSHAKE_LEN - 1)
        return ClientInit(byteArrayOf(first.toByte()) + rest, SocketClientIo(client))
    }

    private fun connectWebSocket(dcId: Int, isMedia: Boolean): WsRoute? {
        val dcKey = dcKey(dcId, isMedia)
        val routeDcId = routeDcId(dcId)
        val domains = wsDomains(dcId, isMedia)
        wsPool.take(dcId, isMedia)?.let {
            ProxyStats.poolHits.incrementAndGet()
            return WsRoute(it, "pool", "pooled DC$dcId")
        }
        ProxyStats.poolMisses.incrementAndGet()

        val now = System.currentTimeMillis()
        val timeout = if ((dcFailUntil[dcKey] ?: 0L) > now) WS_FAIL_TIMEOUT_MS else WS_CONNECT_TIMEOUT_MS
        var sawRedirect = false
        var allRedirects = true
        for (domain in domains) {
            val failUntil = wsDomainFailUntil[domain] ?: 0L
            if (failUntil > now) {
                ProxyLogger.i("WS skip cooldown domain $domain (${(failUntil - now) / 1000}s left)")
                continue
            }
            val ws = runCatching {
                RawWebSocket.connect(config.dcRedirects[routeDcId] ?: domain, domain, timeout)
            }.onFailure { ex ->
                ProxyStats.wsErrors.incrementAndGet()
                allRedirects = false
                markWsDomainFailure(domain, "connect failed: ${ex.javaClass.simpleName}")
                ProxyLogger.w("WS connect failed DC$dcId via $domain", ex)
            }.getOrNull()
            if (ws != null) {
                wsDomainFailUntil.remove(domain)
                ProxyLogger.i("WS connected DC$dcId via $domain")
                return WsRoute(ws, domain, "WS DC$dcId $domain")
            }
            if (!sawRedirect) allRedirects = false
        }
        if (sawRedirect && allRedirects) wsBlacklist.add(dcKey) else dcFailUntil[dcKey] = now + DC_FAIL_COOLDOWN_MS
        return null
    }

    private fun bridgeFallback(
        client: ClientIo,
        dcId: Int,
        isMedia: Boolean,
        relayInit: ByteArray,
        ctx: CryptoContext,
    ) {
        val methods = if (config.fallbackCfProxy && config.fallbackCfProxyPriority) {
            listOf("cf", "tcp")
        } else if (config.fallbackCfProxy) {
            listOf("tcp", "cf")
        } else {
            listOf("tcp")
        }
        for (method in methods) {
            val ok = when (method) {
                "cf" -> bridgeCfFallback(client, dcId, isMedia, relayInit, ctx)
                else -> bridgeTcpFallback(client, dcId, relayInit, ctx)
            }
            if (ok) return
        }
        ProxyLogger.w("No fallback available for DC$dcId")
    }

    private fun bridgeCfFallback(
        client: ClientIo,
        dcId: Int,
        isMedia: Boolean,
        relayInit: ByteArray,
        ctx: CryptoContext,
    ): Boolean {
        for (baseDomain in balancer.getDomainsForDc(dcId)) {
            val domain = "kws$dcId.$baseDomain"
            val ws = runCatching {
                RawWebSocket.connect(domain, domain)
            }.onFailure {
                balancer.markFailure(baseDomain, it.javaClass.simpleName)
                ProxyLogger.w("CF fallback failed DC$dcId via $domain", it)
            }.getOrNull() ?: continue
            balancer.markSuccess(dcId, baseDomain)
            ProxyStats.connectionsCfProxy.incrementAndGet()
            ws.send(relayInit)
            val result = bridgeWebSocket(client, ws, ctx, MtprotoPacketSplitter(relayInit, ProtoTransport.PaddedIntermediate), "CF DC$dcId $domain")
            if (result.isUnhealthy) balancer.markFailure(baseDomain, result.reason)
            return true
        }
        return false
    }

    private fun bridgeTcpFallback(client: ClientIo, dcId: Int, relayInit: ByteArray, ctx: CryptoContext): Boolean {
        val fallbackIps = tcpFallbackIps(dcId)
        for (fallbackIp in fallbackIps) {
            val connected = runCatching {
                Socket().use { telegram ->
                    telegram.connect(InetSocketAddress(fallbackIp, 443), TCP_CONNECT_TIMEOUT_MS)
                    telegram.tcpNoDelay = true
                    telegram.soTimeout = CLIENT_READ_TIMEOUT_MS
                    telegram.outputStream.write(relayInit)
                    telegram.outputStream.flush()
                    ProxyStats.connectionsTcpFallback.incrementAndGet()
                    ProxyLogger.i("TCP fallback connected DC$dcId via $fallbackIp")
                    bridgeTcp(client, telegram, ctx)
                }
            }.onFailure { ProxyLogger.w("TCP fallback failed DC$dcId via $fallbackIp", it) }.isSuccess
            if (connected) return true
        }
        return false
    }

    private fun bridgeWebSocket(
        client: ClientIo,
        ws: RawWebSocket,
        ctx: CryptoContext,
        splitter: MtprotoPacketSplitter,
        label: String,
    ): BridgeResult {
        val startedAt = System.currentTimeMillis()
        val bytesDownStart = ProxyStats.bytesDown.get()
        val bytesUpStart = ProxyStats.bytesUp.get()
        val failureReason = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val up = Thread({
            runCatching {
                val buffer = ByteArray(config.bufferSize)
                while (running.get()) {
                    val read = client.read(buffer)
                    if (read < 0) break
                    ProxyStats.bytesUp.addAndGet(read.toLong())
                    val plain = ctx.clientDecrypt.update(buffer, 0, read)
                    val toTelegram = ctx.telegramEncrypt.update(plain)
                    splitter.split(toTelegram).forEach(ws::send)
                }
                splitter.flush().forEach(ws::send)
            }.onFailure { failureReason.compareAndSet(null, "upstream ${it.javaClass.simpleName}") }
            runCatching { ws.close() }
            runCatching { client.close() }
        }, "tgws-up")

        val down = Thread({
            runCatching {
                while (running.get()) {
                    val data = ws.receive() ?: break
                    ProxyStats.bytesDown.addAndGet(data.size.toLong())
                    val plain = ctx.telegramDecrypt.update(data)
                    val toClient = ctx.clientEncrypt.update(plain)
                    client.write(toClient)
                }
            }.onFailure { failureReason.compareAndSet(null, "downstream ${it.javaClass.simpleName}") }
            runCatching { ws.close() }
            runCatching { client.close() }
        }, "tgws-down")

        up.start(); down.start(); up.join(); down.join()
        val durationMs = System.currentTimeMillis() - startedAt
        val bytesDown = ProxyStats.bytesDown.get() - bytesDownStart
        val bytesUp = ProxyStats.bytesUp.get() - bytesUpStart
        val reason = failureReason.get() ?: "closed"
        ProxyLogger.i("$label closed after ${durationMs}ms, up=$bytesUp, down=$bytesDown, reason=$reason")
        return BridgeResult(durationMs, bytesUp, bytesDown, reason)
    }

    private fun bridgeTcp(client: ClientIo, telegram: Socket, ctx: CryptoContext) {
        val up = Thread({ forward(client, telegram.outputStream, ctx.clientDecrypt, ctx.telegramEncrypt, true) }, "tgws-tcp-up")
        val down = Thread({ forward(SocketClientIo(telegram), client.outputStream(), ctx.telegramDecrypt, ctx.clientEncrypt, false) }, "tgws-tcp-down")
        up.start(); down.start(); up.join(); down.join()
    }

    private fun forward(input: ClientIo, output: OutputStream, decrypt: javax.crypto.Cipher, encrypt: javax.crypto.Cipher, isUp: Boolean) {
        runCatching {
            val buffer = ByteArray(config.bufferSize)
            while (running.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (isUp) ProxyStats.bytesUp.addAndGet(read.toLong()) else ProxyStats.bytesDown.addAndGet(read.toLong())
                val plain = decrypt.update(buffer, 0, read)
                val encrypted = encrypt.update(plain)
                output.write(encrypted)
                output.flush()
            }
        }.onFailure { ProxyLogger.w("TCP bridge closed", it) }
    }

    private fun updateWsRouteHealth(domain: String, result: BridgeResult) {
        if (domain == "pool") return
        if (result.isUnhealthy) {
            markWsDomainFailure(domain, result.reason)
        } else {
            wsDomainFailUntil.remove(domain)
        }
    }

    private fun markWsDomainFailure(domain: String, reason: String) {
        wsDomainFailUntil[domain] = System.currentTimeMillis() + WS_DOMAIN_COOLDOWN_MS
        ProxyLogger.w("WS domain cooldown $domain for ${WS_DOMAIN_COOLDOWN_MS / 1000}s: $reason")
    }

    private fun initCfProxy() {
        if (!config.fallbackCfProxy) return
        if (config.cfProxyUserDomain.isNotBlank()) {
            balancer.updateDomainsList(listOf(config.cfProxyUserDomain))
            return
        }
        balancer.updateDomainsList(DomainBalancer.defaultDomains)
        cfRefreshThread = Thread({
            while (running.get()) {
                val fetched = DomainBalancer.fetchDomains()
                if (fetched.size >= 3) balancer.updateDomainsList(fetched)
                Thread.sleep(60 * 60 * 1000L)
            }
        }, "tgws-cf-refresh").apply { isDaemon = true; start() }
    }

    private fun warmupPool() {
        if (config.poolSize <= 0) return
        for ((dc, target) in config.dcRedirects) {
            for (media in listOf(false, true)) {
                wsPool.refill(dc, media) { RawWebSocket.connect(target, wsDomains(dc, media).first()) }
            }
        }
    }

    private fun wsDomains(dcId: Int, isMedia: Boolean): List<String> {
        val dc = if (dcId == 203) 2 else dcId
        return if (isMedia) listOf("kws$dc-1.web.telegram.org", "kws$dc.web.telegram.org")
        else listOf("kws$dc.web.telegram.org", "kws$dc-1.web.telegram.org")
    }

    private fun routeDcId(dcId: Int): Int = if (dcId == 203) 2 else dcId

    private fun tcpFallbackIps(dcId: Int): List<String> {
        val direct = defaultDcIps[dcId].orEmpty()
        return if (dcId == 203) direct + defaultDcIps[2].orEmpty() else direct
    }

    private fun dcKey(dcId: Int, isMedia: Boolean): String = "$dcId${if (isMedia) "m" else ""}"

    companion object {
        private const val HANDSHAKE_LEN = 64
        private const val DC_FAIL_COOLDOWN_MS = 30_000L
        private const val WS_DOMAIN_COOLDOWN_MS = 120_000L
        private const val WS_CONNECT_TIMEOUT_MS = 4_000
        private const val WS_FAIL_TIMEOUT_MS = 2_000
        private const val CLIENT_READ_TIMEOUT_MS = 120_000
        private const val TCP_CONNECT_TIMEOUT_MS = 4_000
        private val defaultDcIps = mapOf(
            1 to listOf("149.154.175.50", "149.154.175.53"),
            2 to listOf("149.154.167.51", "149.154.167.50", "149.154.167.91"),
            3 to listOf("149.154.175.100", "149.154.175.117"),
            4 to listOf("149.154.167.91", "149.154.167.92", "149.154.167.51"),
            5 to listOf("91.108.56.130", "149.154.171.5", "149.154.170.100"),
            203 to listOf("91.105.192.100"),
        )
    }
}

private data class ClientInit(val handshake: ByteArray, val io: ClientIo)

private data class WsRoute(val ws: RawWebSocket, val domain: String, val label: String)

private data class BridgeResult(
    val durationMs: Long,
    val bytesUp: Long,
    val bytesDown: Long,
    val reason: String,
) {
    val isUnhealthy: Boolean
        get() = bytesDown == 0L && durationMs < 15_000L
}

private interface ClientIo : Closeable {
    fun read(buffer: ByteArray): Int
    fun write(data: ByteArray)
    fun outputStream(): OutputStream
}

private class SocketClientIo(private val socket: Socket) : ClientIo {
    override fun read(buffer: ByteArray): Int = socket.inputStream.read(buffer)
    override fun write(data: ByteArray) { socket.outputStream.write(data); socket.outputStream.flush() }
    override fun outputStream(): OutputStream = socket.outputStream
    override fun close() = socket.close()
}

private class FakeTlsClientIo(private val fake: FakeTlsSocket) : ClientIo {
    private val out = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) = fake.write(b.copyOfRange(off, off + len))
    }
    override fun read(buffer: ByteArray): Int = fake.read(buffer)
    override fun write(data: ByteArray) = fake.write(data)
    override fun outputStream(): OutputStream = out
    override fun close() = fake.close()
}

private class WsPool(private val maxSize: Int) : Closeable {
    private data class Key(val dc: Int, val media: Boolean)
    private val idle = ConcurrentHashMap<Key, ConcurrentLinkedDeque<RawWebSocket>>()

    fun take(dc: Int, media: Boolean): RawWebSocket? = idle[Key(dc, media)]?.pollFirst()

    fun refill(dc: Int, media: Boolean, factory: () -> RawWebSocket) {
        if (maxSize <= 0) return
        Thread({
            val queue = idle.getOrPut(Key(dc, media)) { ConcurrentLinkedDeque() }
            while (queue.size < maxSize) {
                val ws = runCatching(factory).getOrNull() ?: break
                queue.addLast(ws)
            }
        }, "tgws-pool-$dc-${if (media) "m" else "d"}").start()
    }

    override fun close() {
        idle.values.flatten().forEach { runCatching { it.close() } }
        idle.clear()
    }
}

private fun InputStream.readExact(size: Int): ByteArray {
    val result = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(result, offset, size - offset)
        if (read < 0) throw EOFException()
        offset += read
    }
    return result
}
