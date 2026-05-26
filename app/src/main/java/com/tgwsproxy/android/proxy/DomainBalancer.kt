package com.tgwsproxy.android.proxy

import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class DomainBalancer {
    @Volatile private var domains: List<String> = emptyList()
    private val dcToDomain = Collections.synchronizedMap(mutableMapOf<Int, String>())
    private val domainFailUntil = ConcurrentHashMap<String, Long>()

    fun updateDomainsList(newDomains: List<String>) {
        val normalized = newDomains.map { it.trim().lowercase() }.filter(::isValidDomain).distinct()
        if (normalized.isEmpty()) return
        if (domains.sorted() == normalized.sorted()) return
        domains = normalized
        dcToDomain.clear()
        for (dc in listOf(1, 2, 3, 4, 5, 203)) {
            dcToDomain[dc] = normalized.random()
        }
        ProxyLogger.i("CF domain pool updated: ${normalized.size} domains")
    }

    fun updateDomainForDc(dcId: Int, domain: String): Boolean {
        if (dcToDomain[dcId] == domain) return false
        dcToDomain[dcId] = domain
        ProxyLogger.i("CF DC$dcId switched to $domain")
        return true
    }

    fun getDomainsForDc(dcId: Int): List<String> {
        val now = System.currentTimeMillis()
        val current = dcToDomain[dcId]
        val available = domains.filter { (domainFailUntil[it] ?: 0L) <= now }
        val source = available.ifEmpty { domains }
        val shuffled = source.shuffled(Random(System.nanoTime()))
        return buildList {
            if (current != null && current in source) add(current)
            addAll(shuffled.filter { it != current })
        }
    }

    fun markFailure(domain: String, reason: String) {
        domainFailUntil[domain] = System.currentTimeMillis() + DOMAIN_COOLDOWN_MS
        ProxyLogger.w("CF domain cooldown $domain for ${DOMAIN_COOLDOWN_MS / 1000}s: $reason")
    }

    fun markSuccess(dcId: Int, domain: String) {
        domainFailUntil.remove(domain)
        updateDomainForDc(dcId, domain)
    }

    companion object {
        private const val DOMAIN_COOLDOWN_MS = 120_000L
        private val encodedDefaults = listOf(
            "virkgj.com",
            "vmmzovy.com",
            "mkuosckvso.com",
            "zaewayzmplad.com",
            "twdmbzcm.com",
            "awzwsldi.com",
            "clngqrflngqin.com",
            "tjacxbqtj.com",
            "bxaxtxmrw.com",
            "dmohrsgmohcrwb.com",
        )
        val defaultDomains: List<String> = encodedDefaults.map(::decodeDomain)
        private const val URL = "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

        fun fetchDomains(): List<String> {
            return runCatching {
                val connection = URL("$URL?${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", "tg-ws-proxy-android")
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .map(::decodeDomain)
                        .filter(::isValidDomain)
                        .distinct()
                        .toList()
                }
            }.getOrElse {
                ProxyLogger.w("CF domain refresh failed", it)
                emptyList()
            }
        }

        private fun decodeDomain(value: String): String {
            if (!value.endsWith(".com")) return value
            val prefix = value.dropLast(4)
            val shift = prefix.count { it.isLetter() }
            val decoded = prefix.map { ch ->
                if (!ch.isLetter()) ch else {
                    val base = if (ch >= 'a') 'a'.code else 'A'.code
                    (((ch.code - base - shift) % 26 + 26) % 26 + base).toChar()
                }
            }.joinToString("")
            return "$decoded.co.uk"
        }

        private fun isValidDomain(domain: String): Boolean {
            if (domain.isBlank() || domain.length > 253) return false
            if (domain.startsWith('.') || domain.endsWith('.')) return false
            val labels = domain.split('.')
            if (labels.size < 2) return false
            return labels.all { label ->
                label.isNotBlank() && label.length <= 63 &&
                    !label.startsWith('-') && !label.endsWith('-') &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            } && labels.last().length >= 2 && labels.last().any { it.isLetter() }
        }
    }
}
