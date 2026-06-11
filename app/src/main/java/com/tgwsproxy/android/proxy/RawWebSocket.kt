package com.tgwsproxy.android.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class RawWebSocket private constructor(
    private val socket: SSLSocket,
) : Closeable {
    private val input = BufferedInputStream(socket.inputStream)
    private val output = BufferedOutputStream(socket.outputStream)
    private val random = SecureRandom()
    @Volatile private var closed = false

    @Synchronized
    fun send(data: ByteArray) {
        if (closed) throw IllegalStateException("WebSocket is closed")
        output.write(buildFrame(OP_BINARY, data, mask = true))
        output.flush()
    }

    fun receive(): ByteArray? {
        while (!closed) {
            val first = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                close()
                return null
            }
            if (first < 0) return null
            val second = input.read()
            if (second < 0) return null

            val opcode = first and 0x0f
            var length = second and 0x7f
            if (length == 126) {
                length = readUInt16()
            } else if (length == 127) {
                val longLength = readUInt64()
                if (longLength > Int.MAX_VALUE) error("WebSocket frame is too large")
                length = longLength.toInt()
            }

            val payload = ByteArray(length)
            val masked = (second and 0x80) != 0
            val mask = if (masked) readExact(4) else null
            readFully(payload)
            if (mask != null) applyMask(payload, mask)

            when (opcode) {
                OP_CLOSE -> {
                    close()
                    return null
                }
                OP_PING -> sendControl(OP_PONG, payload)
                OP_PONG -> Unit
                OP_TEXT, OP_BINARY -> return payload
            }
        }
        return null
    }

    @Synchronized
    private fun sendControl(opcode: Int, payload: ByteArray) {
        if (closed) return
        output.write(buildFrame(opcode, payload, mask = true))
        output.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    private fun buildFrame(opcode: Int, data: ByteArray, mask: Boolean): ByteArray {
        val header = ArrayList<Byte>(14 + data.size)
        header.add((0x80 or opcode).toByte())

        val length = data.size
        if (length < 126) {
            header.add(((if (mask) 0x80 else 0) or length).toByte())
        } else if (length <= 0xffff) {
            header.add(((if (mask) 0x80 else 0) or 126).toByte())
            header.add(((length ushr 8) and 0xff).toByte())
            header.add((length and 0xff).toByte())
        } else {
            header.add(((if (mask) 0x80 else 0) or 127).toByte())
            for (shift in 56 downTo 0 step 8) {
                header.add(((length.toLong() ushr shift) and 0xff).toByte())
            }
        }

        val payload = data.copyOf()
        if (mask) {
            val maskKey = ByteArray(4).also(random::nextBytes)
            maskKey.forEach { header.add(it) }
            applyMask(payload, maskKey)
        }
        payload.forEach { header.add(it) }
        return header.toByteArray()
    }

    private fun readUInt16(): Int {
        val bytes = readExact(2)
        return ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
    }

    private fun readUInt64(): Long {
        val bytes = readExact(8)
        var value = 0L
        for (byte in bytes) {
            value = (value shl 8) or (byte.toLong() and 0xff)
        }
        return value
    }

    private fun readExact(size: Int): ByteArray {
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes
    }

    private fun readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) throw java.io.EOFException()
            offset += read
        }
    }

    private fun readHttpLine(): String {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
        }
        return out.toString()
    }

    private fun applyMask(data: ByteArray, mask: ByteArray) {
        for (i in data.indices) {
            data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
    }

    companion object {
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xa
        private const val READ_TIMEOUT_MS = 300_000

        fun connect(host: String, domain: String, timeoutMs: Int = 10_000, path: String = "/apiws"): RawWebSocket {
            val tcpSocket = Socket()
            tcpSocket.tcpNoDelay = true
            tcpSocket.connect(InetSocketAddress(host, 443), timeoutMs)
            tcpSocket.soTimeout = timeoutMs

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket(tcpSocket, domain, 443, true) as SSLSocket
            sslSocket.sslParameters = sslSocket.sslParameters.apply {
                serverNames = listOf(SNIHostName(domain))
            }
            sslSocket.startHandshake()
            sslSocket.soTimeout = READ_TIMEOUT_MS

            val ws = RawWebSocket(sslSocket)
            ws.handshake(domain, path)
            return ws
        }
    }

    private fun handshake(domain: String, path: String) {
        val wsKey = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $domain\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $wsKey\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Protocol: binary\r\n")
            append("\r\n")
        }
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()

        val statusLine = readHttpLine()
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
        while (readHttpLine().isNotEmpty()) {
            // consume headers
        }
        if (statusCode != 101) {
            close()
            throw IllegalStateException("WebSocket handshake failed: $statusLine")
        }
    }
}
