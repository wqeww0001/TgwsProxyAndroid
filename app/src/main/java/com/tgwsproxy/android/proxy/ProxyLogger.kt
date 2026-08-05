package com.tgwsproxy.android.proxy

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

object ProxyLogger {
    private const val TAG = "TgWsProxy"
    private const val MAX_LINES = 1200
    private const val MAX_LOG_FILE_BYTES = 1024 * 1024L
    private const val TRIMMED_LOG_FILE_BYTES = 512 * 1024
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val lines = ConcurrentLinkedDeque<String>()
    private val fileLock = Any()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var traceFile: File? = null

    fun initialize(context: Context) {
        synchronized(fileLock) {
            logFile = File(context.applicationContext.filesDir, "tgwsproxy.log")
            traceFile = File(context.applicationContext.filesDir, "last-exit-trace.txt")
        }
    }

    fun d(message: String) = log(Log.DEBUG, "D", message, null)
    fun i(message: String) = log(Log.INFO, "I", message, null)
    fun w(message: String, throwable: Throwable? = null) = log(Log.WARN, "W", message, throwable)
    fun e(message: String, throwable: Throwable? = null) = log(Log.ERROR, "E", message, throwable)

    fun snapshot(): List<String> = lines.toList()

    fun exportText(): String {
        val persisted = synchronized(fileLock) {
            runCatching { logFile?.takeIf(File::exists)?.readText().orEmpty() }.getOrDefault("")
        }
        val current = snapshot().joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n")
        val trace = synchronized(fileLock) {
            runCatching { traceFile?.takeIf(File::exists)?.readText().orEmpty() }.getOrDefault("")
        }
        return buildString {
            append(if (persisted.isNotBlank()) persisted else current)
            if (trace.isNotBlank()) {
                if (isNotEmpty() && last() != '\n') append('\n')
                append("\n===== LAST SYSTEM EXIT TRACE =====\n")
                append(trace)
                if (lastOrNull() != '\n') append('\n')
            }
        }
    }

    private fun log(priority: Int, level: String, message: String, throwable: Throwable?) {
        val line = "${timeFormat.format(Instant.now())} $level $message${throwable?.message?.let { ": $it" } ?: ""}"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.pollFirst()
        persist(line, throwable)
        if (throwable == null) {
            Log.println(priority, TAG, message)
        } else {
            Log.println(priority, TAG, "$message: ${Log.getStackTraceString(throwable)}")
        }
    }

    private fun persist(line: String, throwable: Throwable?) {
        synchronized(fileLock) {
            val file = logFile ?: return
            runCatching {
                rotateIfNeeded(file)
                file.appendText(
                    buildString {
                        append(line).append('\n')
                        if (throwable != null) append(Log.getStackTraceString(throwable)).append('\n')
                    },
                )
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_LOG_FILE_BYTES) return
        val bytes = file.readBytes()
        file.writeBytes(bytes.takeLast(TRIMMED_LOG_FILE_BYTES).toByteArray())
    }
}
