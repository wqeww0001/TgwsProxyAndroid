package com.tgwsproxy.android.proxy

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object ProxyLogger {
    private const val TAG = "TgWsProxy"
    private const val MAX_LINES = 300
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ConcurrentLinkedDeque<String>()

    fun d(message: String) = log(Log.DEBUG, "D", message, null)
    fun i(message: String) = log(Log.INFO, "I", message, null)
    fun w(message: String, throwable: Throwable? = null) = log(Log.WARN, "W", message, throwable)
    fun e(message: String, throwable: Throwable? = null) = log(Log.ERROR, "E", message, throwable)

    fun snapshot(): List<String> = lines.toList()

    private fun log(priority: Int, level: String, message: String, throwable: Throwable?) {
        val line = "${timeFormat.format(Date())} $level $message${throwable?.message?.let { ": $it" } ?: ""}"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.pollFirst()
        if (throwable == null) Log.println(priority, TAG, message) else Log.println(priority, TAG, "$message: ${throwable.message}")
    }
}
