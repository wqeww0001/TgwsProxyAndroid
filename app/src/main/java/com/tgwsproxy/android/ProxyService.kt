package com.tgwsproxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.tgwsproxy.android.proxy.ProxyRuntimeConfig
import com.tgwsproxy.android.proxy.TgProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProxyService : Service() {
    private var proxyServer: TgProxyServer? = null
    private var startTime: Long = 0
    private var lastPing: Long = -1
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val secret = intent?.getStringExtra(EXTRA_SECRET)?.takeIf { it.isNotBlank() }
            ?: prefs.getString(EXTRA_SECRET, "").orEmpty()
        if (secret.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val fakeTlsDomain = intent?.getStringExtra(EXTRA_FAKE_TLS_DOMAIN)
            ?: prefs.getString(EXTRA_FAKE_TLS_DOMAIN, "").orEmpty()
        val cfWorkerDomain = ProxyConfig.cleanDomain(
            intent?.getStringExtra(EXTRA_CF_WORKER_DOMAIN)
                ?: prefs.getString(EXTRA_CF_WORKER_DOMAIN, ProxyConfig.DEFAULT_CF_WORKER_DOMAIN).orEmpty(),
        ).ifBlank { ProxyConfig.DEFAULT_CF_WORKER_DOMAIN }
        val cfDomain = ""
        val cfEnabled = false

        prefs.edit()
            .putString(EXTRA_SECRET, secret)
            .putString(EXTRA_FAKE_TLS_DOMAIN, fakeTlsDomain)
            .putString(EXTRA_CF_WORKER_DOMAIN, cfWorkerDomain)
            .putString(EXTRA_CF_DOMAIN, cfDomain)
            .putBoolean(EXTRA_CF_ENABLED, cfEnabled)
            .apply()

        if (proxyServer == null) {
            ProxyServiceStatus.isRunning = true
            ProxyServiceStatus.startTime = System.currentTimeMillis()
            startTime = System.currentTimeMillis()
            serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            proxyServer = TgProxyServer(
                ProxyRuntimeConfig(
                    host = ProxyConfig.HOST,
                    port = ProxyConfig.PORT,
                    secretHex = secret,
                    fallbackCfProxy = false,
                    cfProxyUserDomain = "",
                    cfProxyWorkerDomain = cfWorkerDomain.trim(),
                    fakeTlsDomain = fakeTlsDomain.trim(),
                )
            ).also { it.start() }

            startPingMonitor()
            startNotificationUpdater()
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ProxyServiceStatus.isRunning = false
        ProxyServiceStatus.startTime = 0
        ProxyServiceStatus.lastPing = -1
        serviceScope.cancel()
        updateRunnable?.let { handler.removeCallbacks(it) }
        proxyServer?.close()
        proxyServer = null
        super.onDestroy()
    }

    private fun startPingMonitor() {
        serviceScope.launch {
            while (isActive) {
                lastPing = if (proxyServer != null) 0 else -1
                ProxyServiceStatus.lastPing = lastPing
                updateNotification()
                delay(5000)
            }
        }
    }

    private fun startNotificationUpdater() {
        updateRunnable = object : Runnable {
            override fun run() {
                if (proxyServer != null) updateNotification()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun formatUptime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60)) % 24
        return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    }

    private fun updateNotification() {
        if (proxyServer == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val uptime = formatUptime(System.currentTimeMillis() - startTime)
        val localPort = if (lastPing >= 0) "service online" else "service N/A"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.proxy_app_icon))
            .setContentTitle("TG WS Proxy active")
            .setContentText("${ProxyConfig.HOST}:${ProxyConfig.PORT} | $uptime | $localPort")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Proxy", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val EXTRA_SECRET = "secret"
        const val EXTRA_FAKE_TLS_DOMAIN = "fake_tls_domain"
        const val EXTRA_CF_WORKER_DOMAIN = "cf_worker_domain"
        const val EXTRA_CF_DOMAIN = "cf_domain"
        const val EXTRA_CF_ENABLED = "cf_enabled"
        private const val PREFS = "proxy"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1001
    }
}
