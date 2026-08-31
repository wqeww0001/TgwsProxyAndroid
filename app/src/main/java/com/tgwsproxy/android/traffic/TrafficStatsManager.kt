package com.tgwsproxy.android.traffic

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrafficSummary(
    val todayDown: Long,
    val todayUp: Long,
    val totalDown: Long,
    val totalUp: Long,
) {
    fun formattedTodayDown(): String = formatBytes(todayDown)
    fun formattedTodayUp(): String = formatBytes(todayUp)
    fun formattedTotalDown(): String = formatBytes(totalDown)
    fun formattedTotalUp(): String = formatBytes(totalUp)

    companion object {
        fun formatBytes(bytes: Long): String {
            val b = bytes.coerceAtLeast(0)
            return when {
                b >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.2f GB", b.toDouble() / (1024.0 * 1024.0 * 1024.0))
                b >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", b.toDouble() / (1024.0 * 1024.0))
                b >= 1024L -> String.format(Locale.ROOT, "%.0f KB", b.toDouble() / 1024.0)
                else -> "$b B"
            }
        }
    }
}

object TrafficStatsManager {
    private const val PREFS_NAME = "traffic_stats"
    private const val KEY_TODAY_DATE = "today_date"
    private const val KEY_TODAY_DOWN = "today_down"
    private const val KEY_TODAY_UP = "today_up"
    private const val KEY_TOTAL_DOWN = "total_down"
    private const val KEY_TOTAL_UP = "total_up"

    private val lock = Any()
    @Volatile private var prevRustDown: Long = 0
    @Volatile private var prevRustUp: Long = 0

    private fun getCurrentDayKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
    }

    fun recordTraffic(context: Context, rustDownBytes: Long, rustUpBytes: Long) {
        synchronized(lock) {
            if (rustDownBytes < prevRustDown || rustUpBytes < prevRustUp) {
                // Core restarted, reset baseline
                prevRustDown = rustDownBytes
                prevRustUp = rustUpBytes
                return
            }

            val deltaDown = (rustDownBytes - prevRustDown).coerceAtLeast(0)
            val deltaUp = (rustUpBytes - prevRustUp).coerceAtLeast(0)
            prevRustDown = rustDownBytes
            prevRustUp = rustUpBytes

            if (deltaDown == 0L && deltaUp == 0L) return

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentDay = getCurrentDayKey()
            val savedDay = prefs.getString(KEY_TODAY_DATE, "") ?: ""

            val isSameDay = savedDay == currentDay
            val currentTodayDown = if (isSameDay) prefs.getLong(KEY_TODAY_DOWN, 0) else 0L
            val currentTodayUp = if (isSameDay) prefs.getLong(KEY_TODAY_UP, 0) else 0L
            val currentTotalDown = prefs.getLong(KEY_TOTAL_DOWN, 0)
            val currentTotalUp = prefs.getLong(KEY_TOTAL_UP, 0)

            prefs.edit {
                putString(KEY_TODAY_DATE, currentDay)
                putLong(KEY_TODAY_DOWN, currentTodayDown + deltaDown)
                putLong(KEY_TODAY_UP, currentTodayUp + deltaUp)
                putLong(KEY_TOTAL_DOWN, currentTotalDown + deltaDown)
                putLong(KEY_TOTAL_UP, currentTotalUp + deltaUp)
            }
        }
    }

    fun getSummary(context: Context): TrafficSummary = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentDay = getCurrentDayKey()
        val savedDay = prefs.getString(KEY_TODAY_DATE, "") ?: ""

        val isSameDay = savedDay == currentDay
        val todayDown = if (isSameDay) prefs.getLong(KEY_TODAY_DOWN, 0) else 0L
        val todayUp = if (isSameDay) prefs.getLong(KEY_TODAY_UP, 0) else 0L
        val totalDown = prefs.getLong(KEY_TOTAL_DOWN, 0)
        val totalUp = prefs.getLong(KEY_TOTAL_UP, 0)

        TrafficSummary(todayDown, todayUp, totalDown, totalUp)
    }

    fun resetStats(context: Context) {
        synchronized(lock) {
            prevRustDown = 0
            prevRustUp = 0
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { clear() }
        }
    }
}
