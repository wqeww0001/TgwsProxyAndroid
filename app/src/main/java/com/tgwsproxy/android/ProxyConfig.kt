package com.tgwsproxy.android

import java.security.SecureRandom

object ProxyConfig {
    const val HOST = "127.0.0.1"
    const val PORT = 1443
    const val DEFAULT_CF_WORKER_DOMAIN = ""

    fun generateSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidSecret(value: String): Boolean {
        return value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    fun telegramProxyLink(secret: String, fakeTlsDomain: String = ""): String {
        val cleanDomain = fakeTlsDomain.trim()
        val proxySecret = if (cleanDomain.isBlank()) {
            "dd$secret"
        } else {
            "ee$secret${cleanDomain.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }}"
        }
        return "tg://proxy?server=$HOST&port=$PORT&secret=$proxySecret"
    }

    fun cleanDomain(value: String): String {
        return value.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .trim('/')
            .trimEnd('.')
            .lowercase()
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
