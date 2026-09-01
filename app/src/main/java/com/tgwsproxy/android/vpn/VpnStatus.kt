package com.tgwsproxy.android.vpn

enum class ServiceOperationMode(val key: String, val ruTitle: String, val enTitle: String, val ruDesc: String, val enDesc: String) {
    ProxyOnly("proxy_only", "Только прокси (MTProto)", "Proxy Only (MTProto)", "Лёгкий режим без прав VPN. Работает через tg://proxy", "Lightweight mode without VPN permission. Works via tg://proxy"),
    VpnTunnel("vpn_tunnel", "Прокси + Звонки (VPN TUN)", "Proxy + Calls (VPN TUN)", "Системный туннель для Telegram. Звонки, видео и чаты в 1 клик", "System tunnel for Telegram. Calls, video and chats in 1 tap"),
}

object VpnStatus {
    @Volatile
    var isVpnRunning: Boolean = false

    @Volatile
    var isStarting: Boolean = false

    @Volatile
    var activeMode: ServiceOperationMode = ServiceOperationMode.ProxyOnly

    @Volatile
    var routedAppsCount: Int = 0

    @Volatile
    var startTime: Long = 0

    fun getUptime(): Long = if (startTime > 0) (System.currentTimeMillis() - startTime) / 1000 else 0
}
