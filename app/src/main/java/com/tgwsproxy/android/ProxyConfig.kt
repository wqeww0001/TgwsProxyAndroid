package com.tgwsproxy.android

import android.content.Context
import com.tgwsproxy.android.proxy.ProxyLogger
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Locale

object ProxyConfig {
    const val HOST = "127.0.0.1"
    const val DEFAULT_PORT = 1443
    private const val PORT_PREF = "proxy_port"

    /** Active local port: DEFAULT_PORT unless it was busy, then a free port saved by [resolvePort]. */
    @Volatile
    var port: Int = DEFAULT_PORT
        private set

    fun loadSavedPort(context: Context) {
        port = context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).getInt(PORT_PREF, DEFAULT_PORT)
    }

    /**
     * Keeps the saved port if it is free, otherwise takes a free loopback port, saves it and makes it active.
     * Must be called only while the proxy core is stopped, so the port is not ours.
     */
    fun resolvePort(context: Context): Int {
        val prefs = context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getInt(PORT_PREF, DEFAULT_PORT)
        val chosen = if (isLocalPortFree(saved)) saved else pickFreePort()
        if (chosen != saved) {
            prefs.edit().putInt(PORT_PREF, chosen).apply()
            ProxyLogger.w("Port $saved is busy, using $chosen. Update the Telegram proxy link.")
        }
        port = chosen
        return chosen
    }

    private fun isLocalPortFree(candidate: Int): Boolean = runCatching {
        ServerSocket().use { it.bind(InetSocketAddress(HOST, candidate)) }
        true
    }.getOrDefault(false)

    private fun pickFreePort(): Int = ServerSocket().use { socket ->
        socket.bind(InetSocketAddress(HOST, 0))
        socket.localPort
    }
    const val DEFAULT_CF_WORKER_DOMAIN = ""

    fun generateSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun isValidSecret(value: String): Boolean {
        val clean = value.trim()
        return clean.length == 32 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    fun telegramProxyLink(secret: String): String {
        val clean = secret.trim().lowercase(Locale.ROOT)
        val proxySecret = if (clean.startsWith("dd") && clean.length == 34) {
            clean
        } else {
            "dd$clean"
        }
        return "tg://proxy?server=$HOST&port=$port&secret=$proxySecret"
    }

    fun cleanDomain(value: String): String {
        return value.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .trim('/')
            .trimEnd('.')
            .lowercase(Locale.ROOT)
    }

    fun normalizeDomain(value: String): String {
        val domain = cleanDomain(value)
        if (domain.isBlank()) return ""
        return domain.takeIf {
            it.length <= 253 &&
                it.contains('.') &&
                it.split('.').all { label ->
                    label.isNotEmpty() && label.length <= 63 &&
                        label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                        label.all { char -> char.isLetterOrDigit() || char == '-' }
                }
        }.orEmpty()
    }

    fun normalizeDcMappings(value: String): String = value
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString("\n")

    fun isValidDcMappings(value: String): Boolean {
        val lines = normalizeDcMappings(value).lines().filter(String::isNotBlank)
        if (lines.isEmpty()) return true
        return lines.all { line ->
            val parts = line.split(':')
            if (parts.size != 2) return@all false
            val dc = parts[0].trim().toIntOrNull()
            val octets = parts[1].trim().split('.')
            dc != null && dc in 1..5 && octets.size == 4 && octets.all { octet ->
                val number = octet.toIntOrNull()
                number != null && number in 0..255
            }
        }
    }

    fun dcMappingsForNative(value: String): String {
        val normalized = normalizeDcMappings(value)
        return if (isValidDcMappings(normalized)) normalized.lines().filter(String::isNotBlank).joinToString(",") else ""
    }
}
