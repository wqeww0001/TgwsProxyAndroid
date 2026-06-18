package com.tgwsproxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.tgwsproxy.android.proxy.ProxyLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ProxyService : Service() {
    private val nativeRunning = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var startTime: Long = 0
    private var lastPing: Long = -1
    private var lastNotificationContent: String = ""
    private var lastNotificationAtMs: Long = 0
    private var statsJob: Job? = null
    private var watchdogJob: Job? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val largeIcon: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.proxy_app_icon)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val secret = intent?.getStringExtra(EXTRA_SECRET)?.takeIf { it.isNotBlank() }
            ?: prefs.getString(EXTRA_SECRET, "").orEmpty()
        if (secret.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val cfDomain = normalizeNativeCfDomain(
            intent?.getStringExtra(EXTRA_CF_WORKER_DOMAIN)
                ?: prefs.getString(EXTRA_CF_WORKER_DOMAIN, "").orEmpty(),
        )

        prefs.edit()
            .putString(EXTRA_SECRET, secret)
            .putString(EXTRA_FAKE_TLS_DOMAIN, "")
            .putString(EXTRA_CF_WORKER_DOMAIN, cfDomain)
            .putString(EXTRA_CF_DOMAIN, cfDomain)
            .putBoolean(EXTRA_CF_ENABLED, true)
            .apply()

        startForeground(NOTIFICATION_ID, buildNotification("starting"))

        if (!nativeRunning.get()) {
            startNativeProxy(secret, cfDomain)
        }

        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        statsJob?.cancel()
        watchdogJob?.cancel()
        stopNativeProxy()
        releaseWakeLock()
        ProxyServiceStatus.isRunning = false
        ProxyServiceStatus.startTime = 0
        ProxyServiceStatus.lastPing = -1
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startNativeProxy(secret: String, cfDomain: String) {
        nativeRunning.set(true)
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        startTime = System.currentTimeMillis()
        lastPing = -1
        ProxyServiceStatus.isRunning = true
        ProxyServiceStatus.startTime = startTime
        ProxyServiceStatus.lastPing = lastPing
        ProxyLogger.i("Starting Rust/Tokio proxy core")
        acquireWakeLock()

        Thread({
            try {
                NativeProxy.setPoolSize(DEFAULT_POOL_SIZE)
                NativeProxy.setCfProxyCacheDir(cacheDir.absolutePath)
                NativeProxy.setCfProxyConfig(enabled = true, priority = true, userDomain = cfDomain)
                val result = NativeProxy.startProxy(
                    host = ProxyConfig.HOST,
                    port = ProxyConfig.PORT,
                    dcIps = "",
                    secret = secret,
                    verbose = true,
                )
                if (result != 0) {
                    ProxyLogger.e("Rust core failed to start: code $result")
                    nativeRunning.set(false)
                    ProxyServiceStatus.isRunning = false
                    stopSelf()
                } else {
                    ProxyLogger.i("Rust core started on ${ProxyConfig.HOST}:${ProxyConfig.PORT}")
                }
            } catch (t: Throwable) {
                ProxyLogger.e("Rust core startup crashed", t)
                nativeRunning.set(false)
                ProxyServiceStatus.isRunning = false
                stopSelf()
            }
        }, "tgws-native-start").apply {
            isDaemon = true
            start()
        }

        startWatchdog()
        startStatsUpdater()
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            delay(3000)
            if (!nativeRunning.get()) return@launch
            val online = isPortOpen(ProxyConfig.HOST, ProxyConfig.PORT, 2000)
            lastPing = if (online) 0 else -1
            ProxyServiceStatus.lastPing = lastPing
            if (online) {
                ProxyLogger.i("Watchdog: local proxy port is listening")
                updateNotification("service online", force = true)
            } else {
                ProxyLogger.w("Watchdog: proxy port is not responding yet")
                updateNotification("service starting", force = true)
            }
        }
    }

    private fun startStatsUpdater() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(3000)
                if (!nativeRunning.get()) continue
                val online = isPortOpen(ProxyConfig.HOST, ProxyConfig.PORT, 700)
                lastPing = if (online) 0 else -1
                ProxyServiceStatus.lastPing = lastPing
                val stats = runCatching { NativeProxy.getStats().orEmpty() }.getOrDefault("")
                if (stats.isNotBlank()) {
                    ProxyLogger.d("Rust stats: $stats")
                }
                updateNotification(if (online) compactStats(stats) else "service N/A")
            }
        }
    }

    private fun stopNativeProxy() {
        if (!nativeRunning.getAndSet(false)) return
        ProxyLogger.i("Stopping Rust/Tokio proxy core")
        val completed = CompletableDeferred<Unit>()
        Thread({
            try {
                NativeProxy.stopProxy()
            } catch (t: Throwable) {
                ProxyLogger.w("Rust core stop failed", t)
            } finally {
                completed.complete(Unit)
            }
        }, "tgws-native-stop").apply {
            isDaemon = true
            start()
        }
        serviceScope.launch {
            withTimeoutOrNull(3000) { completed.await() }
            ProxyLogger.i("Rust core stopped")
        }
    }

    private fun compactStats(stats: String): String {
        if (stats.isBlank()) return "service online"
        val active = stats.substringAfter("active=", "").substringBefore(" ").ifBlank { "0" }
        val up = stats.substringAfter("up=", "").substringBefore(" ").ifBlank { "0B" }
        val down = stats.substringAfter("down=", "").substringBefore(" ").ifBlank { "0B" }
        return "active=$active up=$up down=$down"
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    private fun acquireWakeLock() {
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWsProxy::RustCore").apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        }.onFailure {
            ProxyLogger.w("WakeLock acquire failed", it)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
    }

    private fun formatUptime(millis: Long): String {
        val days = millis / (1000 * 60 * 60 * 24)
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60)) % 24
        return when {
            days > 0 -> "%dd %02d:%02d:%02d".format(days, hours, minutes, seconds)
            hours > 0 -> "%02d:%02d:%02d".format(hours, minutes, seconds)
            else -> "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun updateNotification(content: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && content == lastNotificationContent) return
        if (!force && now - lastNotificationAtMs < NOTIFICATION_MIN_UPDATE_MS) return
        lastNotificationContent = content
        lastNotificationAtMs = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val uptime = formatUptime(System.currentTimeMillis() - startTime)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIcon)
            .setContentTitle("TG WS Proxy active")
            .setContentText("${ProxyConfig.HOST}:${ProxyConfig.PORT} | $uptime | $content")
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Proxy", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun normalizeNativeCfDomain(value: String): String {
        val clean = ProxyConfig.cleanDomain(value).lowercase()
        return if (clean.endsWith(".co.uk")) clean else ""
    }

    companion object {
        const val ACTION_STOP = "com.tgwsproxy.android.STOP"
        const val EXTRA_SECRET = "secret"
        const val EXTRA_FAKE_TLS_DOMAIN = "fake_tls_domain"
        const val EXTRA_CF_WORKER_DOMAIN = "cf_worker_domain"
        const val EXTRA_CF_DOMAIN = "cf_domain"
        const val EXTRA_CF_ENABLED = "cf_enabled"
        private const val PREFS = "proxy"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_POOL_SIZE = 4
        private const val NOTIFICATION_MIN_UPDATE_MS = 3000L
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60 * 1000
    }
}
