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

data class DcPingResult(
    val dc: Int,
    val location: String,
    val wssHost: String,
    val directIp: String,
    val wssPingMs: Long,
    val tcpPingMs: Long,
) {
    val bestPingMs: Long
        get() = listOf(wssPingMs, tcpPingMs).filter { it > 0 }.minOrNull() ?: -1L
    val isReachable: Boolean
        get() = wssPingMs > 0 || tcpPingMs > 0
}

object DomainBenchmark {

    data class PresetDomain(val domain: String, val ruDescription: String, val enDescription: String)
    data class TelegramDcEndpoint(val dc: Int, val location: String, val wssHost: String, val directIp: String)

    val TELEGRAM_DCS = listOf(
        TelegramDcEndpoint(1, "Miami, US", "kws1.web.telegram.org", "149.154.175.50"),
        TelegramDcEndpoint(2, "Amsterdam, NL", "kws2.web.telegram.org", "149.154.167.51"),
        TelegramDcEndpoint(3, "Miami, US", "kws3.web.telegram.org", "149.154.175.100"),
        TelegramDcEndpoint(4, "Amsterdam, NL", "kws4.web.telegram.org", "149.154.167.91"),
        TelegramDcEndpoint(5, "Singapore, SG", "kws5.web.telegram.org", "91.108.56.130"),
    )

    val PRESET_DOMAINS = listOf(
        PresetDomain("cloudflare.com", "Cloudflare WSS (По умолчанию)", "Cloudflare WSS (Default)"),
        PresetDomain("t.me", "Telegram Web (HTTPS 443)", "Telegram Web (HTTPS 443)"),
        PresetDomain("vk.com", "ВКонтакте (HTTPS 443)", "VKontakte (HTTPS 443)"),
        PresetDomain("yandex.ru", "Яндекс (HTTPS 443)", "Yandex (HTTPS 443)"),
        PresetDomain("sberbank.ru", "Сбербанк (HTTPS 443)", "Sberbank (HTTPS 443)"),
        PresetDomain("gosuslugi.ru", "Госуслуги (HTTPS 443)", "Gosuslugi (HTTPS 443)"),
        PresetDomain("ozon.ru", "Ozon (HTTPS 443)", "Ozon (HTTPS 443)"),
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
            DomainPingResult(cleanDomain, -1, false, description)
        }
    }

    suspend fun runDcBenchmark(timeoutMs: Int = 2800): List<DcPingResult> = coroutineScope {
        TELEGRAM_DCS.map { ep ->
            async(Dispatchers.IO) {
                val wssDef = async { pingDomain(ep.wssHost, port = 443, timeoutMs = timeoutMs) }
                val tcpDef = async { pingDomain(ep.directIp, port = 443, timeoutMs = timeoutMs) }
                val wssRes = wssDef.await()
                val tcpRes = tcpDef.await()
                DcPingResult(
                    dc = ep.dc,
                    location = ep.location,
                    wssHost = ep.wssHost,
                    directIp = ep.directIp,
                    wssPingMs = if (wssRes.isSuccess) wssRes.pingMs else -1L,
                    tcpPingMs = if (tcpRes.isSuccess) tcpRes.pingMs else -1L,
                )
            }
        }.awaitAll()
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
