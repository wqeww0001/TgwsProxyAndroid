package com.tgwsproxy.android.benchmark

import com.tgwsproxy.android.ProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class DomainPingResult(
    val domain: String,
    val pingMs: Long,
    val isSuccess: Boolean,
    val description: String = "",
)

object DomainBenchmark {

    data class PresetDomain(val domain: String, val ruDescription: String, val enDescription: String)

    val PRESET_DOMAINS = listOf(
        PresetDomain("cloudflare.com", "Cloudflare WSS (По умолчанию)", "Cloudflare WSS (Default)"),
        PresetDomain("vk.com", "ВКонтакте (FakeTLS)", "VKontakte (FakeTLS)"),
        PresetDomain("yandex.ru", "Яндекс (FakeTLS)", "Yandex (FakeTLS)"),
        PresetDomain("sberbank.ru", "Сбербанк (FakeTLS)", "Sberbank (FakeTLS)"),
        PresetDomain("gosuslugi.ru", "Госуслуги (FakeTLS)", "Gosuslugi (FakeTLS)"),
        PresetDomain("t.me", "Telegram Web (FakeTLS)", "Telegram Web (FakeTLS)"),
        PresetDomain("ozon.ru", "Ozon (FakeTLS)", "Ozon (FakeTLS)"),
    )

    private val FALLBACK_IPS = mapOf(
        "cloudflare.com" to "104.16.132.229",
        "vk.com" to "87.240.137.164",
        "yandex.ru" to "77.88.55.77",
        "sberbank.ru" to "194.54.14.131",
        "gosuslugi.ru" to "109.207.1.97",
        "t.me" to "149.154.167.220",
        "ozon.ru" to "178.248.237.147",
    )

    suspend fun pingDomain(
        domain: String,
        description: String = "",
        port: Int = 443,
        timeoutMs: Int = 3500,
    ): DomainPingResult = withContext(Dispatchers.IO) {
        val cleanDomain = ProxyConfig.normalizeDomain(domain)
        if (cleanDomain.isBlank()) {
            return@withContext DomainPingResult(domain, -1, false, description)
        }

        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(cleanDomain, port), timeoutMs)
                val duration = System.currentTimeMillis() - start
                return@withContext DomainPingResult(cleanDomain, duration.coerceAtLeast(1), true, description)
            }
        } catch (_: Throwable) {
            // If hostname lookup failed or timed out, try fallback IP if known
            val fallbackIp = FALLBACK_IPS[cleanDomain.lowercase()]
            if (fallbackIp != null) {
                try {
                    val fallbackStart = System.currentTimeMillis()
                    Socket().use { socket ->
                        socket.soTimeout = timeoutMs
                        socket.connect(InetSocketAddress(fallbackIp, port), timeoutMs)
                        val duration = System.currentTimeMillis() - fallbackStart
                        return@withContext DomainPingResult(cleanDomain, duration.coerceAtLeast(1), true, description)
                    }
                } catch (_: Throwable) {
                    // ignore
                }
            }
            DomainPingResult(cleanDomain, -1, false, description)
        }
    }

    suspend fun runBenchmark(
        customDomain: String? = null,
        isRu: Boolean = true,
    ): List<DomainPingResult> = coroutineScope {
        val listToTest = mutableListOf<Pair<String, String>>()

        val cleanCustom = customDomain?.let(ProxyConfig::normalizeDomain)?.takeIf { it.isNotBlank() }
        if (cleanCustom != null && PRESET_DOMAINS.none { it.domain.equals(cleanCustom, ignoreCase = true) }) {
            listToTest.add(cleanCustom to if (isRu) "Пользовательский" else "Custom domain")
        }

        PRESET_DOMAINS.forEach { preset ->
            listToTest.add(preset.domain to if (isRu) preset.ruDescription else preset.enDescription)
        }

        val deferreds = listToTest.map { (domain, desc) ->
            async { pingDomain(domain, desc) }
        }

        deferreds.awaitAll().sortedWith(
            compareBy<DomainPingResult> { !it.isSuccess }
                .thenBy { if (it.pingMs > 0) it.pingMs else Long.MAX_VALUE }
        )
    }
}
