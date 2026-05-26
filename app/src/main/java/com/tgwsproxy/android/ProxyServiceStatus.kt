package com.tgwsproxy.android

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ProxyServiceStatus {
    private val _isRunning = AtomicBoolean(false)
    private val _startTime = AtomicLong(0)
    private val _lastPing = AtomicLong(-1)

    var isRunning: Boolean
        get() = _isRunning.get()
        set(value) {
            _isRunning.set(value)
            println("ProxyServiceStatus.isRunning = $value") // Для отладки
        }

    var startTime: Long
        get() = _startTime.get()
        set(value) = _startTime.set(value)

    var lastPing: Long
        get() = _lastPing.get()
        set(value) = _lastPing.set(value)

    fun getUptime(): String {
        if (!isRunning) return "00:00"
        val uptimeMs = System.currentTimeMillis() - startTime
        return formatUptime(uptimeMs)
    }

    private fun formatUptime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60)) % 24
        return when {
            hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes, seconds)
            minutes > 0 -> String.format("%02d:%02d", minutes, seconds)
            else -> String.format("%02d sec", seconds)
        }
    }
}