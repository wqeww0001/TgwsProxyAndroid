package com.tgwsproxy.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.tgwsproxy.android.proxy.ProxyLogger
import java.io.File

object AppDiagnostics {
    private const val PREFS = "app_diagnostics"
    private const val LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private const val MAX_EXIT_RECORDS = 8
    private const val MAX_TRACE_BYTES = 512 * 1024

    fun recordPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        recordPreviousExitsApi30(context.applicationContext)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordPreviousExitsApi30(context: Context) {
        Thread({ recordPreviousExitsBlocking(context) }, "tgws-exit-info").apply {
            isDaemon = true
            start()
        }
    }

    fun setProcessState(context: Context, state: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        setProcessStateApi30(context, state)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun setProcessStateApi30(context: Context, state: String) {
        runCatching {
            val summary = state.take(120).toByteArray(Charsets.UTF_8)
            context.getSystemService(ActivityManager::class.java).setProcessStateSummary(summary)
        }.onFailure { ProxyLogger.w("Unable to set process state summary", it) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordPreviousExitsBlocking(context: Context) {
        runCatching {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastTimestamp = preferences.getLong(LAST_EXIT_TIMESTAMP, 0L)
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val exits = activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
                .filter { it.timestamp > lastTimestamp }
                .sortedBy { it.timestamp }

            if (exits.isNotEmpty()) {
                File(context.filesDir, "last-exit-trace.txt").delete()
            }
            exits.forEach { exit ->
                ProxyLogger.w(
                    "Previous process exit: reason=${reasonLabel(exit.reason)} " +
                        "status=${exit.status} importance=${exit.importance} " +
                        "pss=${exit.pss}KB rss=${exit.rss}KB description=${exit.description.orEmpty()}",
                )
                saveTraceIfPresent(context, exit)
            }
            exits.maxOfOrNull { it.timestamp }?.let { newest ->
                preferences.edit { putLong(LAST_EXIT_TIMESTAMP, newest) }
            }
        }.onFailure { ProxyLogger.w("Unable to read previous process exits", it) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun saveTraceIfPresent(context: Context, exit: ApplicationExitInfo) {
        val trace = runCatching { exit.traceInputStream }.getOrNull() ?: return
        runCatching {
            val bytes = trace.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = MAX_TRACE_BYTES
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.toByteArray()
            }
            File(context.filesDir, "last-exit-trace.txt").writeBytes(bytes)
            ProxyLogger.i("Saved previous ${reasonLabel(exit.reason)} trace (${bytes.size} bytes)")
        }.onFailure { ProxyLogger.w("Unable to save previous exit trace", it) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    internal fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_SIGNALED -> "signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_CRASH -> "java-crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_OTHER -> "system-other"
        else -> "unknown-$reason"
    }
}
