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

    suspend fun pingDomain(
        domain: String,
        description: String = "",
        port: Int = 443,
        timeoutMs: Int = 1500,
    ): DomainPingResult = withContext(Dispatchers.IO) {
        val cleanDomain = ProxyConfig.normalizeDomain(domain)
        if (cleanDomain.isBlank()) {
            return@withContext DomainPingResult(domain, -1, false, description)
        }

        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(cleanDomain, port), timeoutMs)
                val duration = System.currentTimeMillis() - start
                DomainPingResult(cleanDomain, duration.coerceAtLeast(1), true, description)
            }
        } catch (_: Throwable) {
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
