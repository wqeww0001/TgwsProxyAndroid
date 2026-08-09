package com.tgwsproxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
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
    private val nativeInitializing = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val nativeCallLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredAtMs: Long = 0
    private var startTime: Long = 0
    private var lastPing: Long = -1
    private var lastNotificationContent: String = ""
    private var lastNotificationAtMs: Long = 0
    private var statsJob: Job? = null
    private var watchdogJob: Job? = null
    private var networkRestartJob: Job? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var activeNetworkHandle: Long? = null
    @Volatile private var lastSecret: String = ""
    @Volatile private var lastCfDomain: String = ""
    @Volatile private var lastCfEnabled: Boolean = true
    @Volatile private var lastPoolSize: Int = DEFAULT_POOL_SIZE

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val handle = network.networkHandle
            val previous = activeNetworkHandle
            activeNetworkHandle = handle
            if (previous != handle) scheduleNetworkRestart()
        }

        override fun onLost(network: Network) {
            if (activeNetworkHandle == network.networkHandle) activeNetworkHandle = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val providedSecret = intent?.getStringExtra(EXTRA_SECRET)?.takeIf(ProxyConfig::isValidSecret)
        val cfEnabled = intent?.getBooleanExtra(EXTRA_CF_ENABLED, prefs.getBoolean(EXTRA_CF_ENABLED, true))
            ?: prefs.getBoolean(EXTRA_CF_ENABLED, true)
        val poolSize = (intent?.getIntExtra(EXTRA_POOL_SIZE, -1) ?: -1)
            .takeIf { it in SUPPORTED_POOL_SIZES }
            ?: prefs.getString(EXTRA_POOL_SIZE, DEFAULT_POOL_SIZE.toString())
                ?.toIntOrNull()
                ?.takeIf { it in SUPPORTED_POOL_SIZES }
            ?: DEFAULT_POOL_SIZE

        val cfDomain = normalizeNativeCfDomain(
            intent?.getStringExtra(EXTRA_CF_WORKER_DOMAIN)
                ?: prefs.getString(EXTRA_CF_WORKER_DOMAIN, "").orEmpty(),
        )
        val fakeTlsDomain = ProxyConfig.normalizeDomain(
            intent?.getStringExtra(EXTRA_FAKE_TLS_DOMAIN)
                ?: prefs.getString(EXTRA_FAKE_TLS_DOMAIN, "").orEmpty(),
        )

        prefs.edit {
            putString(EXTRA_FAKE_TLS_DOMAIN, fakeTlsDomain)
            putString(EXTRA_CF_WORKER_DOMAIN, cfDomain)
            putString(EXTRA_CF_DOMAIN, cfDomain)
            putBoolean(EXTRA_CF_ENABLED, cfEnabled)
            putString(EXTRA_POOL_SIZE, poolSize.toString())
        }

        if (startTime == 0L) startTime = System.currentTimeMillis()
        if (!promoteToForeground()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (!nativeRunning.get() && nativeInitializing.compareAndSet(false, true)) {
            ProxyServiceStatus.isStarting = true
            resolveSecretAndStart(providedSecret, cfDomain, cfEnabled, poolSize, startId)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed.set(true)
        statsJob?.cancel()
        watchdogJob?.cancel()
        networkRestartJob?.cancel()
        unregisterNetworkCallback()
        stopNativeProxy()
        releaseWakeLock()
        ProxyServiceStatus.isRunning = false
        ProxyServiceStatus.isStarting = false
        ProxyServiceStatus.startTime = 0
        ProxyServiceStatus.lastPing = -1
        lastSecret = ""
        AppDiagnostics.setProcessState(this, "proxy=stopped")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ProxyLogger.e("Foreground service timed out: type=$fgsType startId=$startId")
        stopNativeProxy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun promoteToForeground(): Boolean {
        return runCatching {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("starting"), type)
            true
        }.getOrElse {
            ProxyLogger.e("Unable to promote proxy service to foreground", it)
            false
        }
    }

    private fun resolveSecretAndStart(providedSecret: String?, cfDomain: String, cfEnabled: Boolean, poolSize: Int, startId: Int) {
        Thread({
            try {
                val secret = providedSecret ?: SecureSecretStore.load(this).orEmpty()
                if (providedSecret != null) SecureSecretStore.save(this, providedSecret)
                if (secret.isBlank() || destroyed.get()) {
                    nativeInitializing.set(false)
                    ProxyServiceStatus.isStarting = false
                    if (secret.isBlank()) ProxyLogger.e("Proxy cannot start: no valid secret is available")
                    stopSelfResult(startId)
                    return@Thread
                }
                startNativeProxy(secret, cfDomain, cfEnabled, poolSize)
            } catch (t: Throwable) {
                nativeInitializing.set(false)
                ProxyServiceStatus.isStarting = false
                ProxyLogger.e("Proxy initialization failed", t)
                stopSelfResult(startId)
            }
        }, "tgws-service-init").apply {
            isDaemon = true
            start()
        }
    }

    private fun startNativeProxy(secret: String, cfDomain: String, cfEnabled: Boolean, poolSize: Int) {
        nativeRunning.set(true)
        nativeInitializing.set(false)
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        startTime = System.currentTimeMillis()
        lastPing = -1
        ProxyServiceStatus.isRunning = false
        ProxyServiceStatus.isStarting = true
        ProxyServiceStatus.startTime = startTime
        ProxyServiceStatus.lastPing = lastPing
        AppDiagnostics.setProcessState(this, "proxy=starting")
        ProxyLogger.i("Starting Rust/Tokio proxy core")
        lastSecret = secret
        lastCfDomain = cfDomain
        lastCfEnabled = cfEnabled
        lastPoolSize = poolSize
        acquireWakeLock()

        Thread({
            try {
                val result = synchronized(nativeCallLock) { configureAndStartNative(secret, cfDomain, cfEnabled, poolSize) }
                if (result != 0) {
                    ProxyLogger.e("Rust core failed to start: code $result")
                    nativeRunning.set(false)
                    ProxyServiceStatus.isRunning = false
                    ProxyServiceStatus.isStarting = false
                    stopSelf()
                } else {
                    ProxyServiceStatus.isRunning = true
                    ProxyServiceStatus.isStarting = false
                    ProxyLogger.i("Rust core started on ${ProxyConfig.HOST}:${ProxyConfig.PORT}")
                    AppDiagnostics.setProcessState(this, "proxy=running")
                }
            } catch (t: Throwable) {
                ProxyLogger.e("Rust core startup crashed", t)
                nativeRunning.set(false)
                ProxyServiceStatus.isRunning = false
                ProxyServiceStatus.isStarting = false
                stopSelf()
            }
        }, "tgws-native-start").apply {
            isDaemon = true
            start()
        }

        startWatchdog()
        startStatsUpdater()
    }

    private fun configureAndStartNative(secret: String, cfDomain: String, cfEnabled: Boolean, poolSize: Int): Int {
        NativeProxy.setPoolSize(poolSize)
        NativeProxy.setCfProxyCacheDir(cacheDir.absolutePath)
        NativeProxy.setCfProxyConfig(enabled = cfEnabled, priority = true, userDomain = cfDomain)
        return NativeProxy.startProxy(
            host = ProxyConfig.HOST,
            port = ProxyConfig.PORT,
            dcIps = "",
            secret = secret,
            verbose = true,
        )
    }

    private fun scheduleNetworkRestart() {
        if (!nativeRunning.get() || destroyed.get() || System.currentTimeMillis() - startTime < NETWORK_RESTART_GRACE_MS) return
        networkRestartJob?.cancel()
        networkRestartJob = serviceScope.launch {
            delay(NETWORK_RESTART_DEBOUNCE_MS)
            if (!nativeRunning.get() || destroyed.get() || lastSecret.isBlank()) return@launch
            ProxyLogger.i("Network changed; rebuilding proxy routes and WS pool")
            updateNotification("network changed, reconnecting", force = true)
            val result = runCatching {
                synchronized(nativeCallLock) {
                    NativeProxy.stopProxy()
                    if (!nativeRunning.get() || destroyed.get()) return@synchronized -1
                    configureAndStartNative(lastSecret, lastCfDomain, lastCfEnabled, lastPoolSize)
                }
            }.getOrElse {
                ProxyLogger.e("Proxy restart after network change failed", it)
                -100
            }
            if (result == 0) {
                ProxyLogger.i("Proxy routes rebuilt after network change")
                updateNotification("service online", force = true)
            } else if (!destroyed.get()) {
                ProxyLogger.e("Proxy could not recover after network change: code $result")
                stopSelf()
            }
        }
    }

    private fun registerNetworkCallback() {
        runCatching {
            val manager = getSystemService(ConnectivityManager::class.java)
            activeNetworkHandle = manager.activeNetwork?.networkHandle
            manager.registerDefaultNetworkCallback(networkCallback)
        }.onFailure { ProxyLogger.w("Unable to monitor network changes", it) }
    }

    private fun unregisterNetworkCallback() {
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
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
                renewWakeLockIfNeeded()
                val online = isPortOpen(ProxyConfig.HOST, ProxyConfig.PORT, 700)
                lastPing = if (online) 0 else -1
                ProxyServiceStatus.lastPing = lastPing
                val stats = runCatching {
                    synchronized(nativeCallLock) { NativeProxy.getStats().orEmpty() }
                }.getOrDefault("")
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
                synchronized(nativeCallLock) { NativeProxy.stopProxy() }
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
            wakeLockAcquiredAtMs = System.currentTimeMillis()
        }.onFailure {
            ProxyLogger.w("WakeLock acquire failed", it)
        }
    }

    private fun renewWakeLockIfNeeded() {
        val age = System.currentTimeMillis() - wakeLockAcquiredAtMs
        if (wakeLock?.isHeld == true && age < WAKELOCK_RENEW_AFTER_MS) return
        acquireWakeLock()
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
        wakeLockAcquiredAtMs = 0
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
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content))
        }.onFailure { ProxyLogger.w("Foreground notification update failed", it) }
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
        val channel = NotificationChannel(CHANNEL_ID, "Proxy", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun normalizeNativeCfDomain(value: String): String {
        return ProxyConfig.normalizeDomain(value)
    }

    companion object {
        const val ACTION_STOP = "com.tgwsproxy.android.STOP"
        const val EXTRA_SECRET = "secret"
        const val EXTRA_FAKE_TLS_DOMAIN = "fake_tls_domain"
        const val EXTRA_CF_WORKER_DOMAIN = "cf_worker_domain"
        const val EXTRA_CF_DOMAIN = "cf_domain"
        const val EXTRA_CF_ENABLED = "cf_enabled"
        const val EXTRA_POOL_SIZE = "pool_size"
        private const val PREFS = "proxy"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_POOL_SIZE = 4
        private val SUPPORTED_POOL_SIZES = setOf(2, 4, 6)
        private const val NOTIFICATION_MIN_UPDATE_MS = 3000L
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60 * 1000
        private const val WAKELOCK_RENEW_AFTER_MS = 20L * 60 * 1000
        private const val NETWORK_RESTART_GRACE_MS = 10_000L
        private const val NETWORK_RESTART_DEBOUNCE_MS = 1_500L
    }
}
