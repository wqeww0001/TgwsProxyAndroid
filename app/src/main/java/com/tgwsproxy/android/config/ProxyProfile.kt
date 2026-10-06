package com.tgwsproxy.android.config

import com.tgwsproxy.android.ProxyConfig
import com.tgwsproxy.android.webproxy.WebProxyProtocol
import org.json.JSONObject

data class ProxyProfile(
    val id: String,
    val ruName: String,
    val enName: String,
    val ruDesc: String,
    val enDesc: String,
    val cfWorkerDomain: String,
    val cfEnabled: Boolean,
    val poolSize: Int,
    val smartStandby: Boolean,
    val dcMappings: String = "",
    val webProxyEnabled: Boolean = false,
    val webProxyServer: String = "",
    val webProxySecret: String = "",
) {
    fun name(isRu: Boolean): String = if (isRu) ruName else enName
    fun description(isRu: Boolean): String = if (isRu) ruDesc else enDesc

    fun exportToJson(secret: String): String {
        return JSONObject().apply {
            put("v", 2)
            put("secret", secret)
            put("cf_worker_domain", cfWorkerDomain)
            put("cf_enabled", cfEnabled)
            put("pool_size", poolSize)
            put("smart_standby", smartStandby)
            put("dc_mappings", dcMappings)
            put("web_proxy_enabled", webProxyEnabled)
            put("web_proxy_server", webProxyServer)
            put("web_proxy_secret", webProxySecret)
        }.toString(2)
    }

    companion object {
        val FAST_CLOUDFLARE = ProxyProfile(
            id = "fast_cf",
            ruName = "Скоростной (Cloudflare)",
            enName = "Fast (Cloudflare)",
            ruDesc = "Приоритет Cloudflare CDN + пул 4 соединения",
            enDesc = "Cloudflare CDN priority + 4 connection pool",
            cfWorkerDomain = "",
            cfEnabled = true,
            poolSize = 4,
            smartStandby = true,
        )

        val DIRECT_WSS = ProxyProfile(
            id = "direct_wss",
            ruName = "Прямой WSS (Низкий пинг)",
            enName = "Direct WSS (Low Ping)",
            ruDesc = "Прямое соединение с Telegram DC без Cloudflare",
            enDesc = "Direct WSS to Telegram DC without Cloudflare",
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
            cfWorkerDomain = "",
            cfEnabled = true,
            poolSize = 2,
            smartStandby = true,
        )

        val PRESETS = listOf(FAST_CLOUDFLARE, DIRECT_WSS, ECO_BATTERY)

        data class ImportedConfig(
            val secret: String?,
            val cfWorkerDomain: String,
            val cfEnabled: Boolean,
            val poolSize: Int,
            val smartStandby: Boolean,
            val dcMappings: String,
            val webProxyEnabled: Boolean = false,
            val webProxyServer: String = "",
            val webProxySecret: String = "",
        )

        fun parseImport(raw: String): ImportedConfig? {
            val text = raw.trim()
            if (text.isEmpty()) return null

            // 1. Check Telegram Web Proxy link (https://t.me/webproxy?... or tg://webproxy?...)
            WebProxyProtocol.parseWebProxyLink(text)?.let { endpoint ->
                return ImportedConfig(
                    secret = endpoint.plain16HexSecret.takeIf { ProxyConfig.isValidSecret(it) },
                    cfWorkerDomain = "",
                    cfEnabled = true,
                    poolSize = 4,
                    smartStandby = true,
                    dcMappings = "",
                    webProxyEnabled = true,
                    webProxyServer = endpoint.serverField,
                    webProxySecret = endpoint.mtprotoSecretHex,
                )
            }

            // 2. Check JSON config
            if (text.startsWith("{") && text.endsWith("}")) {
                return try {
                    val json = JSONObject(text)
                    val secret = json.optString("secret").takeIf { ProxyConfig.isValidSecret(it) }
                    val wpServer = json.optString("web_proxy_server", "").trim()
                    val wpSecret = json.optString("web_proxy_secret", "").trim()
                    val wpEnabled = json.optBoolean("web_proxy_enabled", false) &&
                        WebProxyProtocol.parseEndpointInput(wpServer, wpSecret.ifBlank { secret.orEmpty() }) != null
                    ImportedConfig(
                        secret = secret,
                        cfWorkerDomain = ProxyConfig.cleanDomain(json.optString("cf_worker_domain", "")),
                        cfEnabled = json.optBoolean("cf_enabled", true),
                        poolSize = json.optInt("pool_size", 4).takeIf { it in setOf(2, 4, 6) } ?: 4,
                        smartStandby = json.optBoolean("smart_standby", true),
                        dcMappings = json.optString("dc_mappings", ""),
                        webProxyEnabled = wpEnabled,
                        webProxyServer = wpServer,
                        webProxySecret = wpSecret,
                    )
                } catch (_: Throwable) {
                    null
                }
            }

            // 3. Support tg://proxy?server=... or https://t.me/proxy?... or tgws://config?... link formats
            if (
                text.startsWith("tg://proxy", ignoreCase = true) ||
                text.startsWith("https://t.me/proxy", ignoreCase = true) ||
                text.startsWith("tgws://config", ignoreCase = true)
            ) {
                val secretPart = text.substringAfter("secret=", "").substringBefore("&").trim()
                val secretHex = when {
                    secretPart.startsWith("dd", ignoreCase = true) && secretPart.length == 34 -> secretPart.substring(2)
                    secretPart.length == 32 -> secretPart
                    else -> ""
                }
                if (ProxyConfig.isValidSecret(secretHex)) {
                    return ImportedConfig(
                        secret = secretHex.lowercase(),
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
