package com.tgwsproxy.android

import java.security.SecureRandom

object ProxyConfig {
    const val HOST = "127.0.0.1"
    const val PORT = 1443

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
}
