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
    @Volatile private var prevNativeDown: Long = 0
    @Volatile private var prevNativeUp: Long = 0
    @Volatile private var cachedDay: String = ""
    @Volatile private var cachedSummary: TrafficSummary? = null

    private fun getCurrentDayKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
    }

    fun resetBaseline() {
        synchronized(lock) {
            prevNativeDown = 0
            prevNativeUp = 0
        }
    }

    fun recordTraffic(
        context: Context,
        downBytes: Long,
        upBytes: Long,
    ) {
        val cleanDown = downBytes.coerceAtLeast(0)
        val cleanUp = upBytes.coerceAtLeast(0)
        synchronized(lock) {
            val prevDown = prevNativeDown
            val prevUp = prevNativeUp

            val deltaDown = if (cleanDown < prevDown) cleanDown else (cleanDown - prevDown)
            val deltaUp = if (cleanUp < prevUp) cleanUp else (cleanUp - prevUp)

            prevNativeDown = cleanDown
            prevNativeUp = cleanUp

            if (deltaDown == 0L && deltaUp == 0L) return

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentDay = getCurrentDayKey()
            val savedDay = prefs.getString(KEY_TODAY_DATE, "") ?: ""

            val isSameDay = savedDay == currentDay
            val currentTodayDown = if (isSameDay) prefs.getLong(KEY_TODAY_DOWN, 0) else 0L
            val currentTodayUp = if (isSameDay) prefs.getLong(KEY_TODAY_UP, 0) else 0L
            val currentTotalDown = prefs.getLong(KEY_TOTAL_DOWN, 0)
            val currentTotalUp = prefs.getLong(KEY_TOTAL_UP, 0)

            val newTodayDown = currentTodayDown + deltaDown
            val newTodayUp = currentTodayUp + deltaUp
            val newTotalDown = currentTotalDown + deltaDown
            val newTotalUp = currentTotalUp + deltaUp

            cachedDay = currentDay
            cachedSummary = TrafficSummary(newTodayDown, newTodayUp, newTotalDown, newTotalUp)

            prefs.edit {
                putString(KEY_TODAY_DATE, currentDay)
                putLong(KEY_TODAY_DOWN, newTodayDown)
                putLong(KEY_TODAY_UP, newTodayUp)
                putLong(KEY_TOTAL_DOWN, newTotalDown)
                putLong(KEY_TOTAL_UP, newTotalUp)
            }
        }
    }

    fun getSummary(context: Context): TrafficSummary {
        val currentDay = getCurrentDayKey()
        val fast = cachedSummary
        if (fast != null && cachedDay == currentDay) {
            return fast
        }
        return synchronized(lock) {
            val existing = cachedSummary
            if (existing != null && cachedDay == currentDay) {
                return@synchronized existing
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedDay = prefs.getString(KEY_TODAY_DATE, "") ?: ""

            val isSameDay = savedDay == currentDay
            val todayDown = if (isSameDay) prefs.getLong(KEY_TODAY_DOWN, 0) else 0L
            val todayUp = if (isSameDay) prefs.getLong(KEY_TODAY_UP, 0) else 0L
            val totalDown = prefs.getLong(KEY_TOTAL_DOWN, 0)
            val totalUp = prefs.getLong(KEY_TOTAL_UP, 0)

            TrafficSummary(todayDown, todayUp, totalDown, totalUp).also {
                cachedDay = currentDay
                cachedSummary = it
            }
        }
    }

    fun resetStats(context: Context) {
        synchronized(lock) {
            prevNativeDown = 0
            prevNativeUp = 0
            cachedDay = getCurrentDayKey()
            cachedSummary = TrafficSummary(0, 0, 0, 0)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { clear() }
        }
    }
}
