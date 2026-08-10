package com.tgwsproxy.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tgwsproxy.android.proxy.ProxyLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val preferences = context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(AUTO_START_PROXY_PREF, false)) return

        runCatching {
            val serviceIntent = Intent(context, ProxyService::class.java).apply {
                putExtra(ProxyService.EXTRA_FAKE_TLS_DOMAIN, preferences.getString(ProxyService.EXTRA_FAKE_TLS_DOMAIN, "").orEmpty())
                putExtra(ProxyService.EXTRA_CF_WORKER_DOMAIN, preferences.getString(ProxyService.EXTRA_CF_WORKER_DOMAIN, ProxyConfig.DEFAULT_CF_WORKER_DOMAIN).orEmpty())
                putExtra(ProxyService.EXTRA_CF_ENABLED, preferences.getBoolean(ProxyService.EXTRA_CF_ENABLED, true))
                putExtra(ProxyService.EXTRA_POOL_SIZE, preferences.getString(ProxyService.EXTRA_POOL_SIZE, "4")?.toIntOrNull() ?: 4)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            ProxyLogger.i("Auto-start requested after ${intent.action}")
        }.onFailure { ProxyLogger.e("Auto-start failed: ${it.message ?: it.javaClass.simpleName}") }
    }
}
