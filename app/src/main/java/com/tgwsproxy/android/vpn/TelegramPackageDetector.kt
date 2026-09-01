package com.tgwsproxy.android.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

data class DetectedTelegramApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isInstalled: Boolean,
)

object TelegramPackageDetector {

    val KNOWN_TELEGRAM_PACKAGES = listOf(
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram Web",
        "org.thunderdog.challegram" to "Telegram X",
        "org.telegram.plus" to "Plus Messenger",
        "tw.nekomimi.nekogram" to "Nekogram",
        "com.radolyn.ayugram" to "AyuGram",
        "com.iMe.android" to "iMe Messenger",
        "org.telegram.BifToGram" to "Biftogram",
        "nekox.messenger" to "NekoX",
        "org.forkgram.messenger" to "Forkgram",
        "com.exteragram.messenger" to "ExteraGram",
    )

    fun getInstalledTelegramApps(context: Context): List<DetectedTelegramApp> {
        val pm = context.packageManager
        val list = mutableListOf<DetectedTelegramApp>()

        for ((pkg, defaultName) in KNOWN_TELEGRAM_PACKAGES) {
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                val label = pm.getApplicationLabel(appInfo).toString().ifBlank { defaultName }
                val icon = pm.getApplicationIcon(appInfo)
                list.add(DetectedTelegramApp(pkg, label, icon, true))
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed
            }
        }

        if (list.isEmpty()) {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("tg://resolve?domain=telegram"))
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            for (ri in resolved) {
                val pkg = ri.activityInfo.packageName
                if (list.none { it.packageName == pkg }) {
                    val label = ri.loadLabel(pm).toString()
                    val icon = ri.loadIcon(pm)
                    list.add(DetectedTelegramApp(pkg, label, icon, true))
                }
            }
        }

        return list
    }
}
