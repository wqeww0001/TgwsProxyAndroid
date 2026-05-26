package com.tgwsproxy.android.proxy

data class ProxyRuntimeConfig(
    val host: String,
    val port: Int,
    val secretHex: String,
    val dcRedirects: Map<Int, String> = mapOf(2 to "149.154.167.220", 4 to "149.154.167.220"),
    val bufferSize: Int = 256 * 1024,
    val poolSize: Int = 0,
    val fallbackCfProxy: Boolean = true,
    val fallbackCfProxyPriority: Boolean = true,
    val cfProxyUserDomain: String = "",
    val fakeTlsDomain: String = "",
)
