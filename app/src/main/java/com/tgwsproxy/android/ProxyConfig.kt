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
            .trim('/')
    }
}
