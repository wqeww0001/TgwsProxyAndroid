package com.tgwsproxy.android.proxy

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class FakeTlsSocket(private val socket: Socket) {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val readBuffer = ArrayDeque<Byte>()
    private var readLeft = 0

    fun read(buffer: ByteArray): Int {
        if (readBuffer.isNotEmpty()) {
            var count = 0
            while (count < buffer.size && readBuffer.isNotEmpty()) buffer[count++] = readBuffer.removeFirst()
            return count
        }
        val payload = readTlsPayload()
        if (payload.isEmpty()) return -1
        val count = minOf(buffer.size, payload.size)
        payload.copyInto(buffer, 0, 0, count)
        for (i in count until payload.size) readBuffer.addLast(payload[i])
        return count
    }

    fun readExact(size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val buffer = ByteArray(size - offset)
            val read = read(buffer)
            if (read < 0) throw EOFException()
            buffer.copyInto(out, offset, 0, read)
            offset += read
        }
        return out
    }

    fun write(data: ByteArray) {
        output.write(FakeTls.wrapTlsRecord(data))
        output.flush()
    }

    fun close() = socket.close()

    private fun readTlsPayload(): ByteArray {
        if (readLeft > 0) {
            val size = minOf(readLeft, 65536)
            val data = input.readExact(size)
            readLeft -= data.size
            return data
        }

        while (true) {
            val header = input.readExact(5)
            val type = header[0].toInt() and 0xff
            val recordLength = ((header[3].toInt() and 0xff) shl 8) or (header[4].toInt() and 0xff)
            if (type == FakeTls.TLS_RECORD_CCS) {
                if (recordLength > 0) input.readExact(recordLength)
                continue
            }
            if (type != FakeTls.TLS_RECORD_APPDATA) return ByteArray(0)
            val size = minOf(recordLength, 65536)
            val data = input.readExact(size)
            val remaining = recordLength - data.size
            if (remaining > 0) readLeft = remaining
            return data
        }
    }
}

object FakeTls {
    private val random = SecureRandom()
    private val ccsFrame = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)
    private val serverHelloTemplate = buildServerHelloTemplate()

    fun verifyClientHello(data: ByteArray, secret: ByteArray): Triple<ByteArray, ByteArray, Int>? {
        if (data.size < 43) return null
        if ((data[0].toInt() and 0xff) != TLS_RECORD_HANDSHAKE) return null
        if ((data[5].toInt() and 0xff) != 0x01) return null

        val clientRandom = data.copyOfRange(CLIENT_RANDOM_OFFSET, CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN)
        val zeroed = data.copyOf()
        for (i in CLIENT_RANDOM_OFFSET until CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN) zeroed[i] = 0
        val expected = hmacSha256(secret, zeroed)
        if (!MessageDigest.isEqual(expected.copyOfRange(0, 28), clientRandom.copyOfRange(0, 28))) return null

        val tsBytes = ByteArray(4) { i -> (clientRandom[28 + i].toInt() xor expected[28 + i].toInt()).toByte() }
        val timestamp = (tsBytes[0].toInt() and 0xff) or
            ((tsBytes[1].toInt() and 0xff) shl 8) or
            ((tsBytes[2].toInt() and 0xff) shl 16) or
            ((tsBytes[3].toInt() and 0xff) shl 24)
        val now = (System.currentTimeMillis() / 1000).toInt()
        if (abs(now - timestamp) > TIMESTAMP_TOLERANCE) return null

        val sessionId = if (data.size >= SESSION_ID_OFFSET + SESSION_ID_LEN && data[43] == 0x20.toByte()) {
            data.copyOfRange(SESSION_ID_OFFSET, SESSION_ID_OFFSET + SESSION_ID_LEN)
        } else {
            ByteArray(SESSION_ID_LEN)
        }
        return Triple(clientRandom, sessionId, timestamp)
    }

    fun buildServerHello(secret: ByteArray, clientRandom: ByteArray, sessionId: ByteArray): ByteArray {
        val sh = serverHelloTemplate.copyOf()
        sessionId.copyInto(sh, SH_SESSID_OFF, 0, 32)
        randomBytes(32).copyInto(sh, SH_PUBKEY_OFF, 0, 32)
        val encryptedSize = random.nextInt(1900, 2101)
        val encryptedData = randomBytes(encryptedSize)
        val appRecord = byteArrayOf(0x17, 0x03, 0x03) + u16(encryptedSize) + encryptedData
        val response = sh + ccsFrame + appRecord
        val serverRandom = hmacSha256(secret, clientRandom + response)
        val final = response.copyOf()
        serverRandom.copyInto(final, SH_RANDOM_OFF, 0, 32)
        return final
    }

    fun wrapTlsRecord(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < data.size) {
            val size = minOf(TLS_APPDATA_MAX, data.size - offset)
            out.write(byteArrayOf(0x17, 0x03, 0x03, ((size ushr 8) and 0xff).toByte(), (size and 0xff).toByte()))
            out.write(data, offset, size)
            offset += size
        }
        return out.toByteArray()
    }

    fun proxyToMaskingDomain(client: Socket, initialData: ByteArray, domain: String) {
        Socket(domain, 443).use { upstream ->
            ProxyStats.connectionsMasked.incrementAndGet()
            if (initialData.isNotEmpty()) {
                upstream.outputStream.write(initialData)
                upstream.outputStream.flush()
            }
            val up = Thread { relay(client.inputStream, upstream.outputStream) }
            val down = Thread { relay(upstream.inputStream, client.outputStream) }
            up.start(); down.start(); up.join(); down.join()
        }
    }

    private fun relay(input: InputStream, output: OutputStream) {
        runCatching {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        }
    }

    private fun buildServerHelloTemplate(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x7a, 0x02, 0x00, 0x00, 0x76, 0x03, 0x03))
        out.write(ByteArray(32))
        out.write(0x20)
        out.write(ByteArray(32))
        out.write(byteArrayOf(0x13, 0x01, 0x00, 0x00, 0x2e, 0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20))
        out.write(ByteArray(32))
        out.write(byteArrayOf(0x00, 0x2b, 0x00, 0x02, 0x03, 0x04))
        return out.toByteArray()
    }

    private fun hmacSha256(secret: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    private fun u16(value: Int): ByteArray = byteArrayOf(((value ushr 8) and 0xff).toByte(), (value and 0xff).toByte())

    const val TLS_RECORD_HANDSHAKE = 0x16
    const val TLS_RECORD_CCS = 0x14
    const val TLS_RECORD_APPDATA = 0x17
    private const val CLIENT_RANDOM_OFFSET = 11
    private const val CLIENT_RANDOM_LEN = 32
    private const val SESSION_ID_OFFSET = 44
    private const val SESSION_ID_LEN = 32
    private const val TIMESTAMP_TOLERANCE = 120
    private const val TLS_APPDATA_MAX = 16384
    private const val SH_RANDOM_OFF = 11
    private const val SH_SESSID_OFF = 44
    private const val SH_PUBKEY_OFF = 89
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
