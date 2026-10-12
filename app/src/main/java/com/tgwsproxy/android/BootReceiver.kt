package com.tgwsproxy.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tgwsproxy.android.proxy.ProxyLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val preferences = context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE)
        val isPackageReplaced = action == Intent.ACTION_MY_PACKAGE_REPLACED
        val reopenAfterUpdate = isPackageReplaced && preferences.getBoolean(UpdateChecker.PREF_REOPEN_AFTER_UPDATE, false)
        val wasRunningBeforeUpdate = isPackageReplaced && preferences.getBoolean(UpdateChecker.PREF_WAS_RUNNING_BEFORE_UPDATE, false)
        val autoStartEnabled = preferences.getBoolean(AUTO_START_PROXY_PREF, false)

        if (isPackageReplaced) {
            preferences.edit()
                .putBoolean(UpdateChecker.PREF_REOPEN_AFTER_UPDATE, false)
                .putBoolean(UpdateChecker.PREF_WAS_RUNNING_BEFORE_UPDATE, false)
                .apply()
            runCatching {
                java.io.File(context.cacheDir, "updates").listFiles()?.forEach { f ->
                    if (f.isFile && (f.name.endsWith(".apk") || f.name.endsWith(".part"))) {
                        f.delete()
                    }
                }
            }
        }

        if (autoStartEnabled || wasRunningBeforeUpdate) {
            runCatching {
                val serviceIntent = Intent(context, ProxyService::class.java).apply {
                    putExtra(ProxyService.EXTRA_CF_WORKER_DOMAIN, preferences.getString(ProxyService.EXTRA_CF_WORKER_DOMAIN, ProxyConfig.DEFAULT_CF_WORKER_DOMAIN).orEmpty())
                    putExtra(ProxyService.EXTRA_CF_ENABLED, preferences.getBoolean(ProxyService.EXTRA_CF_ENABLED, true))
                    putExtra(ProxyService.EXTRA_CF_PRIORITY, preferences.getBoolean(ProxyService.EXTRA_CF_PRIORITY, true))
                    putExtra(ProxyService.EXTRA_SMART_STANDBY, preferences.getBoolean(ProxyService.EXTRA_SMART_STANDBY, true))
                    putExtra(ProxyService.EXTRA_POOL_SIZE, preferences.getString(ProxyService.EXTRA_POOL_SIZE, "4")?.toIntOrNull() ?: 4)
                    putExtra(ProxyService.EXTRA_DC_IPS, preferences.getString(ProxyService.EXTRA_DC_IPS, "").orEmpty())
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                ProxyLogger.i("Auto-start requested after $action")
            }.onFailure {
                val reason = it.message ?: it.javaClass.simpleName
                ProxyLogger.e("Auto-start failed: $reason")
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    val ru = preferences.getString("ui_language", "ru") != "en"
                    notifyAutostartFailed(context, ru, reason)
                }
            }
        }

        if (reopenAfterUpdate) {
            runCatching {
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("updated_just_now", true)
                }
                context.startActivity(activityIntent)
            }.onFailure { ProxyLogger.w("Reopen after update failed: ${it.message}") }
        }
    }

    private fun notifyAutostartFailed(context: Context, ru: Boolean, reason: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(AUTOSTART_CHANNEL_ID, "TgwsProxy", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, AUTOSTART_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (ru) "Автозапуск прокси не выполнен" else "Proxy autostart failed")
                .setContentText(
                    if (ru) "Откройте приложение и запустите прокси вручную. $reason"
                    else "Open the app and start the proxy manually. $reason",
                )
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            manager.notify(AUTOSTART_FAILED_NOTIFICATION_ID, notification)
        }.onFailure { ProxyLogger.w("Could not post autostart failure notification: ${it.message}") }
    }

    private companion object {
        const val AUTOSTART_CHANNEL_ID = "proxy"
        const val AUTOSTART_FAILED_NOTIFICATION_ID = 9001
    }
}
