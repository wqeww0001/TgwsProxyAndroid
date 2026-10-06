package com.tgwsproxy.android.webproxy

import java.net.IDN
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Implementation of Telegram WEB Proxy protocol v1 (`tproxy-v1`) wire format,
 * capability derivation (`PROTOCOL.md` & `BASE_PATH.md`), secret encoding,
 * and 8-byte multiplexed binary frames.
 */
object WebProxyProtocol {
    const val HEADER_SIZE = 8
    const val MAX_PAYLOAD_SIZE = 1024 * 1024 // 1 MiB
    const val MAX_DATA_CHUNK_SIZE = 64 * 1024 // 64 KiB
    const val INITIAL_STREAM_WINDOW = 4 * 1024 * 1024 // 4 MiB
    const val MAX_BATCH_FRAMES = 4096
    const val DEFAULT_BATCH_LIMIT_BYTES = 2 * 1024 * 1024 // 2 MiB

    const val TYPE_OPEN = 0x01
    const val TYPE_DATA = 0x02
    const val TYPE_CLOSE = 0x03
    const val TYPE_WINDOW = 0x04
    const val TYPE_PING = 0x05
    const val TYPE_PONG = 0x06
    const val TYPE_HELLO = 0x10
    const val TYPE_WELCOME = 0x11
    const val TYPE_BYE = 0x1F

    private const val BASE_PATH_SECRET_MARKER: Byte = 0x70
    private const val DD_SECRET_PREFIX: Byte = 0xDD.toByte()

    val EMPTY_BYTES = ByteArray(0)

    data class WebProxyEndpoint(
        val host: String,
        val basePath: String,
        val rawSecretBytes: ByteArray,
    ) {
        val mtprotoSecretHex: String = rawSecretBytes.toHex()

        /**
         * Secret formatted for local `tg://proxy?server=127.0.0.1&port=1443&secret=...` link.
         * Retains `dd` prefix if already present, or prefixes `dd` for 16-byte raw secrets.
         */
        val localTelegramSecretHex: String
            get() = if (rawSecretBytes.size == 16) "dd$mtprotoSecretHex" else mtprotoSecretHex

        /**
         * Raw 32-char hex secret (without leading `dd`), used if syncing with local 16-byte secret store.
         */
        val plain16HexSecret: String
            get() = if (rawSecretBytes.size == 17 && rawSecretBytes[0] == DD_SECRET_PREFIX) {
                rawSecretBytes.copyOfRange(1, 17).toHex()
            } else {
                mtprotoSecretHex.takeLast(32)
            }

        val serverField: String
            get() = if (basePath.isEmpty()) host else "$host/$basePath"

        val serverParam: String
            get() = serverField

        val displaySecret: String
            get() = if (basePath.isEmpty()) mtprotoSecretHex else encodeMarkedSecret(rawSecretBytes)

        val basePrefix: String
            get() = if (basePath.isEmpty()) "/" else "/$basePath/"

        val bridgeCapability: String
            get() = deriveBridgeCapability(host, basePath, rawSecretBytes)

        val bridgeUrl: String
            get() = "https://$host$basePrefix?bridge=$bridgeCapability"

        fun toTmeLink(): String {
            return if (basePath.isEmpty()) {
                "https://t.me/webproxy?server=$host&secret=$mtprotoSecretHex"
            } else {
                val encodedServer = "$host%2F${basePath.replace("/", "%2F")}"
                val markedSecret = encodeMarkedSecret(rawSecretBytes)
                "https://t.me/webproxy?server=$encodedServer&secret=$markedSecret"
            }
        }

        fun toHttpsShareUrl(): String = toTmeLink()

        fun toTgLink(): String {
            return if (basePath.isEmpty()) {
                "tg://webproxy?server=$host&secret=$mtprotoSecretHex"
            } else {
                val encodedServer = "$host%2F${basePath.replace("/", "%2F")}"
                val markedSecret = encodeMarkedSecret(rawSecretBytes)
                "tg://webproxy?server=$encodedServer&secret=$markedSecret"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WebProxyEndpoint) return false
            return host == other.host &&
                basePath == other.basePath &&
                rawSecretBytes.contentEquals(other.rawSecretBytes)
        }

        override fun hashCode(): Int {
            var result = host.hashCode()
            result = 31 * result + basePath.hashCode()
            result = 31 * result + rawSecretBytes.contentHashCode()
            return result
        }
    }

    data class Frame(
        val type: Int,
        val streamId: Int,
        val payload: ByteArray = EMPTY_BYTES,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return type == other.type && streamId == other.streamId && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = type
            result = 31 * result + streamId
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Derives the 43-character unpadded base64url HMAC-SHA256 bridge capability token.
     *
     * Root context: `UTF-8("tdesktop-web-proxy-bridge-v1\n" + H)`
     * Base-path context: `UTF-8("tdesktop-web-proxy-bridge-v2\n" + H + "\n" + P)`
     */
    fun deriveBridgeCapability(host: String, basePath: String, secretBytes: ByteArray): String {
        val cleanHost = normalizeHost(host)
        require(cleanHost.isNotEmpty()) { "Invalid Web Proxy hostname" }
        val cleanPath = basePath.trim()
        val context = if (cleanPath.isEmpty()) {
            "tdesktop-web-proxy-bridge-v1\n$cleanHost"
        } else {
            require(isValidBasePath(cleanPath)) { "Invalid Web Proxy base path" }
            "tdesktop-web-proxy-bridge-v2\n$cleanHost\n$cleanPath"
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        val digest = mac.doFinal(context.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Encodes secret bytes with leading `0x70` marker as unpadded base64url for base-path links.
     */
    fun encodeMarkedSecret(secretBytes: ByteArray): String {
        val marked = ByteArray(secretBytes.size + 1)
        marked[0] = BASE_PATH_SECRET_MARKER
        System.arraycopy(secretBytes, 0, marked, 1, secretBytes.size)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(marked)
    }

    /**
     * Decodes a Web Proxy secret string into raw MTProxy secret bytes (16 bytes or 17 bytes starting with 0xDD).
     *
     * Per `BASE_PATH.md`:
     * - Base64url-decode first; if the result is >= 17 bytes and starts with `0x70`, strip `0x70`
     *   and validate the remaining bytes as an MTProxy secret (16 bytes, or 17 bytes starting with `0xDD`).
     * - Otherwise, if `requireMarked` is false, parse as hex (32 hex chars or 34 hex chars starting with `dd`)
     *   or unpadded base64url (16 bytes or 17 bytes starting with `0xDD`).
     */
    fun decodeSecretBytes(secretInput: String, requireMarked: Boolean = false): ByteArray? {
        val clean = secretInput.trim()
        if (clean.isEmpty()) return null

        // 1. Check 0x70-marked base64url form first
        val base64Decoded = runCatching {
            Base64.getUrlDecoder().decode(clean)
        }.getOrNull()

        if (base64Decoded != null && base64Decoded.size >= 17 && base64Decoded[0] == BASE_PATH_SECRET_MARKER) {
            val stripped = base64Decoded.copyOfRange(1, base64Decoded.size)
            if (isValidRawSecretBytes(stripped)) {
                return stripped
            }
            return null
        }

        if (requireMarked) {
            return null
        }

        // 2. Check hex representation (32 chars = 16 bytes, or 34 chars starting with dd = 17 bytes)
        val hexBytes = decodeHex(clean)
        if (hexBytes != null && isValidRawSecretBytes(hexBytes)) {
            return hexBytes
        }

        // 3. Check unmarked base64url representation (16 bytes, or 17 bytes starting with 0xDD)
        if (base64Decoded != null && isValidRawSecretBytes(base64Decoded)) {
            return base64Decoded
        }

        return null
    }

    fun isValidRawSecretBytes(bytes: ByteArray): Boolean {
        return when (bytes.size) {
            16 -> true
            17 -> bytes[0] == DD_SECRET_PREFIX
            else -> false
        }
    }

    /**
     * Validates a base path according to `BASE_PATH.md` §1:
     * One or more `/`-separated segments, each `[A-Za-z0-9][A-Za-z0-9_-]*`, at most 128 chars total,
     * no leading or trailing slash, case-sensitive.
     */
    fun isValidBasePath(path: String): Boolean {
        if (path.isEmpty()) return true
        if (path.length > 128 || path.startsWith('/') || path.endsWith('/')) return false
        val segments = path.split('/')
        return segments.all { seg ->
            seg.isNotEmpty() &&
                isAsciiAlnum(seg.first()) &&
                seg.all { ch -> isAsciiAlnum(ch) || ch == '_' || ch == '-' }
        }
    }

    private fun isAsciiAlnum(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9'

    /**
     * Normalizes a canonical DNS hostname (ASCII/IDNA, lowercase, no IPv4/IPv6 literals or ports).
     */
    fun normalizeHost(rawHost: String): String {
        val trimmed = rawHost.trim().trimEnd('.')
        if (trimmed.isEmpty() || trimmed.contains(':') || trimmed.contains('/')) return ""
        val ascii = runCatching { IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT) }
            .getOrDefault("")
        if (ascii.isEmpty() || ascii.length > 253 || !ascii.contains('.')) return ""
        val labels = ascii.split('.')
        val validLabels = labels.all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                isAsciiAlnum(label.first()) &&
                isAsciiAlnum(label.last()) &&
                label.all { ch -> isAsciiAlnum(ch) || ch == '-' }
        }
        if (!validLabels) return ""
        // Reject numeric IPv4 literals (TLD cannot be all digits)
        if (labels.last().all { it in '0'..'9' }) return ""
        return ascii
    }

    /**
     * Splits a `server` field (`proxy.example.com` or `proxy.example.com/base/path`) into `(host, basePath)`.
     */
    fun parseServerAndBasePath(rawServer: String): Pair<String, String>? {
        val cleaned = rawServer.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
        if (cleaned.isEmpty()) return null
        val slashIndex = cleaned.indexOf('/')
        val hostPart = if (slashIndex >= 0) cleaned.substring(0, slashIndex) else cleaned
        val pathPart = if (slashIndex >= 0) cleaned.substring(slashIndex + 1) else ""
        val host = normalizeHost(hostPart)
        if (host.isEmpty()) return null
        if (!isValidBasePath(pathPart)) return null
        return host to pathPart
    }

    /**
     * Strictly parses a `https://t.me/webproxy?...` or `tg://webproxy?...` URL per `ANDROID.md` & `BASE_PATH.md`.
     */
    fun parseWebProxyLink(rawUrl: String): WebProxyEndpoint? {
        val text = rawUrl.trim()
        val query = when {
            text.startsWith("tg://webproxy?", ignoreCase = true) -> text.substringAfter('?', "").substringBefore('#')
            text.startsWith("https://t.me/webproxy?", ignoreCase = true) -> text.substringAfter('?', "").substringBefore('#')
            text.startsWith("http://t.me/webproxy?", ignoreCase = true) -> text.substringAfter('?', "").substringBefore('#')
            text.startsWith("t.me/webproxy?", ignoreCase = true) -> text.substringAfter('?', "").substringBefore('#')
            else -> return null
        }
        if (query.isEmpty()) return null

        var serverParam: String? = null
        var secretParam: String? = null
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq).lowercase(Locale.ROOT)
            val value = runCatching {
                URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }.getOrNull() ?: continue
            when (key) {
                "server" -> serverParam = value
                "host" -> if (serverParam == null) serverParam = value
                "secret" -> secretParam = value
            }
        }

        val server = serverParam?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val secret = secretParam?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val (host, basePath) = parseServerAndBasePath(server) ?: return null
        val secretBytes = decodeSecretBytes(secret, requireMarked = basePath.isNotEmpty()) ?: return null
        return WebProxyEndpoint(host = host, basePath = basePath, rawSecretBytes = secretBytes)
    }

    /**
     * Flexible parser for UI fields: accepts either a full `t.me/webproxy` link in `serverInput`,
     * or separate `serverInput` (`host` / `host/basePath`) and `secretInput` (hex or `0x70` base64url).
     */
    fun parseEndpointInput(serverInput: String, secretInput: String): WebProxyEndpoint? {
        val trimmedServer = serverInput.trim()
        parseWebProxyLink(trimmedServer)?.let { return it }
        parseWebProxyLink(secretInput.trim())?.let { return it }

        val (host, basePath) = parseServerAndBasePath(trimmedServer) ?: return null
        val secretBytes = decodeSecretBytes(secretInput.trim(), requireMarked = false) ?: return null
        return WebProxyEndpoint(host = host, basePath = basePath, rawSecretBytes = secretBytes)
    }

    /**
     * Extracts the 43-character base64url `bootstrap` token and optional `carrierMode` from
     * the HTML bridge page returned by `GET /?bridge=<bridge>`.
     */
    fun extractBridgeBootstrap(html: String): Pair<String, String?>? {
        val bootstrapRegex = Regex("""bootstrap\s*=\s*["']([A-Za-z0-9_-]{43})["']""")
        val carrierRegex = Regex("""carrierMode\s*=\s*["'](https|https-lanes|websocket|websocket-lanes)["']""")
        val bootstrap = bootstrapRegex.find(html)?.groupValues?.getOrNull(1) ?: return null
        val carrier = carrierRegex.find(html)?.groupValues?.getOrNull(1)
        return bootstrap to carrier
    }

    // =========================================================================
    // Shared Frame Codec (8-byte big-endian header: type:u8 | stream_id:u24 | len:u32)
    // =========================================================================

    fun encodeFrame(type: Int, streamId: Int, payload: ByteArray = EMPTY_BYTES): ByteArray {
        require(type in 0..0xFF) { "Invalid frame type: $type" }
        require(streamId in 0..0xFFFFFF) { "Invalid 24-bit streamId: $streamId" }
        require(payload.size <= MAX_PAYLOAD_SIZE) { "Frame payload exceeds 1 MiB: ${payload.size}" }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = type.toByte()
        out[1] = ((streamId ushr 16) and 0xFF).toByte()
        out[2] = ((streamId ushr 8) and 0xFF).toByte()
        out[3] = (streamId and 0xFF).toByte()
        val len = payload.size
        out[4] = ((len ushr 24) and 0xFF).toByte()
        out[5] = ((len ushr 16) and 0xFF).toByte()
        out[6] = ((len ushr 8) and 0xFF).toByte()
        out[7] = (len and 0xFF).toByte()
        if (len > 0) {
            System.arraycopy(payload, 0, out, HEADER_SIZE, len)
        }
        return out
    }

    fun helloFrame(): ByteArray = encodeFrame(TYPE_HELLO, 0, byteArrayOf(0x01))

    fun welcomeFrame(): ByteArray = encodeFrame(TYPE_WELCOME, 0, EMPTY_BYTES)

    fun openFrame(streamId: Int): ByteArray {
        require(streamId > 0) { "OPEN frame requires nonzero streamId" }
        return encodeFrame(TYPE_OPEN, streamId, EMPTY_BYTES)
    }

    fun dataFrame(streamId: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray {
        require(streamId > 0) { "DATA frame requires nonzero streamId" }
        require(length in 1..MAX_PAYLOAD_SIZE) { "Invalid DATA frame length: $length" }
        val slice = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
        return encodeFrame(TYPE_DATA, streamId, slice)
    }

    fun closeFrame(streamId: Int): ByteArray {
        require(streamId > 0) { "CLOSE frame requires nonzero streamId" }
        return encodeFrame(TYPE_CLOSE, streamId, EMPTY_BYTES)
    }

    fun windowFrame(streamId: Int, delta: Int): ByteArray {
        require(streamId > 0) { "WINDOW frame requires nonzero streamId" }
        require(delta > 0) { "WINDOW delta must be positive: $delta" }
        val payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(delta).array()
        return encodeFrame(TYPE_WINDOW, streamId, payload)
    }

    fun pongFrame(echoToken: ByteArray): ByteArray = encodeFrame(TYPE_PONG, 0, echoToken)

    fun parseWindowDelta(payload: ByteArray): Long {
        require(payload.size == 4) { "WINDOW payload must be 4 bytes" }
        return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    fun decodeFrames(batch: ByteArray): List<Frame> {
        if (batch.isEmpty()) return emptyList()
        val result = ArrayList<Frame>()
        var offset = 0
        while (offset < batch.size) {
            if (batch.size - offset < HEADER_SIZE) {
                throw IllegalArgumentException("Truncated frame header at offset $offset")
            }
            if (result.size >= MAX_BATCH_FRAMES) {
                throw IllegalArgumentException("Batch exceeds $MAX_BATCH_FRAMES frames")
            }
            val type = batch[offset].toInt() and 0xFF
            val streamId = ((batch[offset + 1].toInt() and 0xFF) shl 16) or
                ((batch[offset + 2].toInt() and 0xFF) shl 8) or
                (batch[offset + 3].toInt() and 0xFF)
            val size = ((batch[offset + 4].toInt() and 0xFF) shl 24) or
                ((batch[offset + 5].toInt() and 0xFF) shl 16) or
                ((batch[offset + 6].toInt() and 0xFF) shl 8) or
                (batch[offset + 7].toInt() and 0xFF)

            if (size < 0 || size > MAX_PAYLOAD_SIZE) {
                throw IllegalArgumentException("Invalid frame payload size: $size")
            }
            val end = offset + HEADER_SIZE + size
            if (end > batch.size) {
                throw IllegalArgumentException("Truncated frame payload: expected $end, got ${batch.size}")
            }
            if (type == TYPE_DATA && size == 0) {
                throw IllegalArgumentException("Empty DATA frame is invalid")
            }
            val payload = if (size == 0) EMPTY_BYTES else batch.copyOfRange(offset + HEADER_SIZE, end)
            result.add(Frame(type = type, streamId = streamId, payload = payload))
            offset = end
        }
        return result
    }

    private fun decodeHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi == -1 || lo == -1) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
