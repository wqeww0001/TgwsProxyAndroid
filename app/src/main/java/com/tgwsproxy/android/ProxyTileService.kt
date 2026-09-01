package com.tgwsproxy.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tgwsproxy.android.proxy.ProxyLogger

class ProxyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = ProxyServiceStatus.isRunning
        val isStarting = ProxyServiceStatus.isStarting

        if (isStarting) {
            return
        }

        val tile = qsTile
        if (isRunning) {
            // Optimistic update
            if (tile != null) {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Остановка..."
                }
                tile.updateTile()
            }
            val stopIntent = Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_STOP
            }
            startService(stopIntent)
        } else {
            // Optimistic update
            if (tile != null) {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Запуск..."
                }
                tile.updateTile()
            }

            val prefs = getSharedPreferences("proxy", MODE_PRIVATE)
            val cleanSecret = SecureSecretStore.getOrCreate(this)
            val fakeTlsDomain = prefs.getString(ProxyService.EXTRA_FAKE_TLS_DOMAIN, "") ?: ""
            val cfWorkerDomain = prefs.getString(ProxyService.EXTRA_CF_WORKER_DOMAIN, "") ?: ""
            val cfEnabled = prefs.getBoolean(ProxyService.EXTRA_CF_ENABLED, true)
            val allowLan = prefs.getBoolean(ProxyService.EXTRA_ALLOW_LAN, false)
            val smartStandby = prefs.getBoolean(ProxyService.EXTRA_SMART_STANDBY, true)
            val poolSize = prefs.getString(ProxyService.EXTRA_POOL_SIZE, "4")?.toIntOrNull() ?: 4
            val dcIps = prefs.getString(ProxyService.EXTRA_DC_IPS, "") ?: ""

            val cleanFakeTls = ProxyConfig.normalizeDomain(fakeTlsDomain)
            val cleanCfDomain = ProxyConfig.normalizeDomain(cfWorkerDomain)

            val startIntent = Intent(this, ProxyService::class.java).apply {
                putExtra(ProxyService.EXTRA_SECRET, cleanSecret)
                putExtra(ProxyService.EXTRA_FAKE_TLS_DOMAIN, cleanFakeTls)
                putExtra(ProxyService.EXTRA_CF_WORKER_DOMAIN, cleanCfDomain)
                putExtra(ProxyService.EXTRA_CF_ENABLED, cfEnabled)
                putExtra(ProxyService.EXTRA_ALLOW_LAN, allowLan)
                putExtra(ProxyService.EXTRA_SMART_STANDBY, smartStandby)
                putExtra(ProxyService.EXTRA_POOL_SIZE, poolSize)
                putExtra(ProxyService.EXTRA_DC_IPS, ProxyConfig.normalizeDcMappings(dcIps))
                putExtra(ProxyService.EXTRA_CF_DOMAIN, cleanCfDomain)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
        }

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = ProxyServiceStatus.isRunning
        val isStarting = ProxyServiceStatus.isStarting

        when {
            isRunning -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "TgwsProxy"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val prefs = getSharedPreferences("proxy", MODE_PRIVATE)
                    val isLan = prefs.getBoolean(ProxyService.EXTRA_ALLOW_LAN, false)
                    tile.subtitle = if (isLan) "0.0.0.0:${ProxyConfig.PORT}" else "${ProxyConfig.HOST}:${ProxyConfig.PORT}"
                }
            }
            isStarting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "TgwsProxy"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Запуск..."
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "TgwsProxy"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Остановлен"
                }
            }
        }

        runCatching {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        }

        tile.updateTile()
    }

    companion object {
        fun requestTileUpdate(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, ProxyTileService::class.java))
            }.onFailure {
                ProxyLogger.w("Failed to request QS tile update", it)
            }
        }
    }
}
