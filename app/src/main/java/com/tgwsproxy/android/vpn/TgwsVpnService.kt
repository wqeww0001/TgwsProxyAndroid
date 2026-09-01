package com.tgwsproxy.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.tgwsproxy.android.MainActivity
import com.tgwsproxy.android.ProxyConfig
import com.tgwsproxy.android.ProxyService
import com.tgwsproxy.android.R
import com.tgwsproxy.android.proxy.ProxyLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TgwsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunnelJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning.compareAndSet(false, true)) {
            startForeground(NOTIFICATION_ID, buildNotification("Запуск туннеля..."))
            VpnStatus.isStarting = true
            VpnStatus.isVpnRunning = false
            startVpnTunnel()
        }

        return START_STICKY
    }

    private fun startVpnTunnel() {
        tunnelJob = serviceScope.launch {
            try {
                val builder = Builder()
                    .setSession("TgwsProxy Call & VPN Tunnel")
                    .setMtu(1500)
                    .addAddress("10.244.0.2", 24)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)

                // Per-app routing for Telegram clients
                val installedApps = TelegramPackageDetector.getInstalledTelegramApps(this@TgwsVpnService)
                var addedCount = 0
                for (app in installedApps) {
                    try {
                        builder.addAllowedApplication(app.packageName)
                        addedCount++
                        ProxyLogger.i("VpnService: routing traffic for ${app.appName} (${app.packageName})")
                    } catch (e: PackageManager.NameNotFoundException) {
                        ProxyLogger.w("VpnService: package not found: ${app.packageName}", e)
                    }
                }

                // If no specific Telegram package detected, route self to avoid broken VPN
                if (addedCount == 0) {
                    builder.addAllowedApplication(packageName)
                }

                VpnStatus.routedAppsCount = addedCount

                val pfd = builder.establish()
                if (pfd == null) {
                    ProxyLogger.e("VpnService: Builder.establish() returned null")
                    stopSelf()
                    return@launch
                }

                vpnInterface = pfd
                VpnStatus.isVpnRunning = true
                VpnStatus.isStarting = false
                VpnStatus.startTime = System.currentTimeMillis()
                startForeground(NOTIFICATION_ID, buildNotification("Туннель активен (звонки и чаты)"))
                ProxyLogger.i("TgwsProxy VpnService established successfully. Routed apps: $addedCount")

                runTunPacketLoop(pfd)
            } catch (t: Throwable) {
                ProxyLogger.e("TgwsProxy VpnService failed to start", t)
                stopVpn()
                stopSelf()
            }
        }
    }

    private fun runTunPacketLoop(pfd: ParcelFileDescriptor) {
        val inputStream = FileInputStream(pfd.fileDescriptor)
        val outputStream = FileOutputStream(pfd.fileDescriptor)
        val packet = ByteBuffer.allocate(32768)

        val udpSockets = ConcurrentHashMap<Int, DatagramSocket>()

        try {
            while (isRunning.get() && serviceScope.isActive) {
                packet.clear()
                val length = inputStream.read(packet.array())
                if (length <= 0) continue

                val buffer = packet.array()
                val version = (buffer[0].toInt() shr 4) and 0x0F
                if (version != 4) continue // IPv4 only for now

                val protocol = buffer[9].toInt() and 0xFF
                val srcIp = "${buffer[12].toUByte()}.${buffer[13].toUByte()}.${buffer[14].toUByte()}.${buffer[15].toUByte()}"
                val dstIp = "${buffer[16].toUByte()}.${buffer[17].toUByte()}.${buffer[18].toUByte()}.${buffer[19].toUByte()}"

                if (protocol == 17) {
                    // UDP Packet (Telegram Calls, Voice Chats, STUN, WebRTC)
                    val srcPort = ((buffer[20].toInt() and 0xFF) shl 8) or (buffer[21].toInt() and 0xFF)
                    val dstPort = ((buffer[22].toInt() and 0xFF) shl 8) or (buffer[23].toInt() and 0xFF)
                    val udpLen = ((buffer[24].toInt() and 0xFF) shl 8) or (buffer[25].toInt() and 0xFF)
                    val payloadOffset = 28
                    val payloadLen = (length - payloadOffset).coerceAtLeast(0)

                    if (payloadLen > 0) {
                        serviceScope.launch {
                            try {
                                val socket = udpSockets.computeIfAbsent(srcPort) {
                                    DatagramSocket().apply {
                                        protect(this)
                                        soTimeout = 5000
                                    }
                                }
                                val outData = ByteArray(payloadLen)
                                System.arraycopy(buffer, payloadOffset, outData, 0, payloadLen)
                                val destAddr = InetAddress.getByName(dstIp)
                                val outPacket = DatagramPacket(outData, payloadLen, destAddr, dstPort)
                                socket.send(outPacket)
                            } catch (_: Throwable) {
                                // UDP send error
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Stream closed
        } finally {
            udpSockets.values.forEach { runCatching { it.close() } }
            udpSockets.clear()
        }
    }

    private fun stopVpn() {
        isRunning.set(false)
        VpnStatus.isVpnRunning = false
        VpnStatus.isStarting = false
        VpnStatus.startTime = 0
        tunnelJob?.cancel()
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        ProxyLogger.i("TgwsProxy VpnService stopped")
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TgwsProxy VPN Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Status of TgwsProxy VPN Tunnel for Telegram Calls & Media"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, TgwsVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TgwsProxy VPN (Звонки Telegram)")
            .setContentText(status)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification, "Остановить", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.tgwsproxy.android.vpn.ACTION_STOP"
        private const val CHANNEL_ID = "tgws_vpn_channel"
        private const val NOTIFICATION_ID = 1444

        fun start(context: Context) {
            val intent = Intent(context, TgwsVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TgwsVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
