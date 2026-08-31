package com.tgwsproxy.android.config

import com.tgwsproxy.android.ProxyConfig
import org.json.JSONObject

data class ProxyProfile(
    val id: String,
    val ruName: String,
    val enName: String,
    val ruDesc: String,
    val enDesc: String,
    val fakeTlsDomain: String,
    val cfWorkerDomain: String,
    val cfEnabled: Boolean,
    val poolSize: Int,
    val smartStandby: Boolean,
    val dcMappings: String = "",
) {
    fun name(isRu: Boolean): String = if (isRu) ruName else enName
    fun description(isRu: Boolean): String = if (isRu) ruDesc else enDesc

    fun exportToJson(secret: String): String {
        return JSONObject().apply {
            put("v", 1)
            put("secret", secret)
            put("fake_tls_domain", fakeTlsDomain)
            put("cf_worker_domain", cfWorkerDomain)
            put("cf_enabled", cfEnabled)
            put("pool_size", poolSize)
            put("smart_standby", smartStandby)
            put("dc_mappings", dcMappings)
        }.toString(2)
    }

    companion object {
        val FAST_CLOUDFLARE = ProxyProfile(
            id = "fast_cf",
            ruName = "Скоростной (Cloudflare)",
            enName = "Fast (Cloudflare)",
            ruDesc = "Приоритет Cloudflare CDN + пул 4 соединения",
            enDesc = "Cloudflare CDN priority + 4 connection pool",
            fakeTlsDomain = "",
            cfWorkerDomain = "",
            cfEnabled = true,
            poolSize = 4,
            smartStandby = true,
        )

        val STEALTH_FAKETLS = ProxyProfile(
            id = "stealth_faketls",
            ruName = "Скрытный (FakeTLS VK)",
            enName = "Stealth (FakeTLS VK)",
            ruDesc = "Маскировка под VKontakte с прямым WSS",
            enDesc = "VKontakte TLS disguise with direct WSS",
            fakeTlsDomain = "vk.com",
            cfWorkerDomain = "",
            cfEnabled = false,
            poolSize = 4,
            smartStandby = true,
        )

        val ECO_BATTERY = ProxyProfile(
            id = "eco_battery",
            ruName = "Энергосбережение (Eco)",
            enName = "Eco Battery",
            ruDesc = "Минимальный пул 2 WSS + режим сна",
            enDesc = "Minimal 2 WSS pool + sleep mode",
            fakeTlsDomain = "",
            cfWorkerDomain = "",
            cfEnabled = true,
            poolSize = 2,
            smartStandby = true,
        )

        val PRESETS = listOf(FAST_CLOUDFLARE, STEALTH_FAKETLS, ECO_BATTERY)

        data class ImportedConfig(
            val secret: String?,
            val fakeTlsDomain: String,
            val cfWorkerDomain: String,
            val cfEnabled: Boolean,
            val poolSize: Int,
            val smartStandby: Boolean,
            val dcMappings: String,
        )

        fun parseImport(raw: String): ImportedConfig? {
            val text = raw.trim()
            if (text.startsWith("{") && text.endsWith("}")) {
                return try {
                    val json = JSONObject(text)
                    val secret = json.optString("secret").takeIf { ProxyConfig.isValidSecret(it) }
                    ImportedConfig(
                        secret = secret,
                        fakeTlsDomain = ProxyConfig.normalizeDomain(json.optString("fake_tls_domain", "")),
                        cfWorkerDomain = ProxyConfig.cleanDomain(json.optString("cf_worker_domain", "")),
                        cfEnabled = json.optBoolean("cf_enabled", true),
                        poolSize = json.optInt("pool_size", 4).takeIf { it in setOf(2, 4, 6) } ?: 4,
                        smartStandby = json.optBoolean("smart_standby", true),
                        dcMappings = json.optString("dc_mappings", ""),
                    )
                } catch (_: Throwable) {
                    null
                }
            }

            // Also support tg://proxy?server=... or tgws://config?... link formats
            if (text.startsWith("tg://proxy") || text.startsWith("tgws://config")) {
                val secretPart = text.substringAfter("secret=", "").substringBefore("&").trim()
                if (secretPart.startsWith("ee") && secretPart.length > 34) {
                    val rawHexDomain = secretPart.substring(34)
                    val domain = runCatching {
                        String(rawHexDomain.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.US_ASCII)
                    }.getOrDefault("")
                    val secretHex = secretPart.substring(2, 34)
                    return ImportedConfig(
                        secret = if (ProxyConfig.isValidSecret(secretHex)) secretHex else null,
                        fakeTlsDomain = ProxyConfig.normalizeDomain(domain),
                        cfWorkerDomain = "",
                        cfEnabled = true,
                        poolSize = 4,
                        smartStandby = true,
                        dcMappings = "",
                    )
                } else if (secretPart.startsWith("dd") && secretPart.length == 34) {
                    val secretHex = secretPart.substring(2)
                    return ImportedConfig(
                        secret = if (ProxyConfig.isValidSecret(secretHex)) secretHex else null,
                        fakeTlsDomain = "",
                        cfWorkerDomain = "",
                        cfEnabled = true,
                        poolSize = 4,
                        smartStandby = true,
                        dcMappings = "",
                    )
                }
            }
            return null
        }
    }
}
