package com.tgwsproxy.android.config

import com.tgwsproxy.android.ProxyConfig
import org.json.JSONObject

data class ProxyProfile(
    val id: String,
    val ruName: String,
    val enName: String,
    val ruDesc: String,
    val enDesc: String,
    val cfWorkerDomain: String,
    val cfEnabled: Boolean,
    val cfPriority: Boolean = true,
    val poolSize: Int,
    val smartStandby: Boolean,
    val dcMappings: String = "",
) {
    fun name(isRu: Boolean): String = if (isRu) ruName else enName
    fun description(isRu: Boolean): String = if (isRu) ruDesc else enDesc

    fun exportToJson(secret: String): String {
        return JSONObject().apply {
            put("v", 2)
            put("secret", secret)
            put("cf_worker_domain", cfWorkerDomain)
            put("cf_enabled", cfEnabled)
            put("cf_priority", cfPriority)
            put("pool_size", poolSize)
            put("smart_standby", smartStandby)
            put("dc_mappings", dcMappings)
        }.toString(2)
    }

    companion object {
        data class ImportedConfig(
            val secret: String?,
            val cfWorkerDomain: String,
            val cfEnabled: Boolean,
            val cfPriority: Boolean = true,
            val poolSize: Int,
            val smartStandby: Boolean,
            val dcMappings: String,
        )

        fun parseImport(raw: String): ImportedConfig? {
            val text = raw.trim()
            if (text.isEmpty()) return null

            // JSON config (exported by this app; unknown legacy keys are ignored)
            if (text.startsWith("{") && text.endsWith("}")) {
                return try {
                    val json = JSONObject(text)
                    ImportedConfig(
                        secret = json.optString("secret").takeIf { ProxyConfig.isValidSecret(it) },
                        cfWorkerDomain = ProxyConfig.cleanDomain(json.optString("cf_worker_domain", "")),
                        cfEnabled = json.optBoolean("cf_enabled", true),
                        cfPriority = json.optBoolean("cf_priority", true),
                        poolSize = json.optInt("pool_size", 4).takeIf { it in setOf(2, 4, 6) } ?: 4,
                        smartStandby = json.optBoolean("smart_standby", true),
                        dcMappings = json.optString("dc_mappings", ""),
                    )
                } catch (_: Throwable) {
                    null
                }
            }

            // tg://proxy or https://t.me/proxy or tgws://config links
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
