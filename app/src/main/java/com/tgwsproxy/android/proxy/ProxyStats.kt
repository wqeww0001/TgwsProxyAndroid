package com.tgwsproxy.android.proxy

import java.util.concurrent.atomic.AtomicLong

object ProxyStats {
    val connectionsTotal = AtomicLong(0)
    val connectionsActive = AtomicLong(0)
    val connectionsWs = AtomicLong(0)
    val connectionsTcpFallback = AtomicLong(0)
    val connectionsCfProxy = AtomicLong(0)
    val connectionsBad = AtomicLong(0)
    val connectionsMasked = AtomicLong(0)
    val wsErrors = AtomicLong(0)
    val bytesUp = AtomicLong(0)
    val bytesDown = AtomicLong(0)
    val poolHits = AtomicLong(0)
    val poolMisses = AtomicLong(0)

    fun reset() {
        listOf(
            connectionsTotal,
            connectionsActive,
            connectionsWs,
            connectionsTcpFallback,
            connectionsCfProxy,
            connectionsBad,
            connectionsMasked,
            wsErrors,
            bytesUp,
            bytesDown,
            poolHits,
            poolMisses,
        ).forEach { it.set(0) }
    }

    fun summary(): String {
        val poolTotal = poolHits.get() + poolMisses.get()
        val pool = if (poolTotal > 0) "${poolHits.get()}/$poolTotal" else "n/a"
        return "total=${connectionsTotal.get()} active=${connectionsActive.get()} " +
            "ws=${connectionsWs.get()} tcp=${connectionsTcpFallback.get()} " +
            "cf=${connectionsCfProxy.get()} bad=${connectionsBad.get()} " +
            "masked=${connectionsMasked.get()} err=${wsErrors.get()} " +
            "pool=$pool up=${humanBytes(bytesUp.get())} down=${humanBytes(bytesDown.get())}"
    }

    private fun humanBytes(value: Long): String {
        var n = value.toDouble()
        for (unit in listOf("B", "KB", "MB", "GB")) {
            if (kotlin.math.abs(n) < 1024.0) return "%.1f%s".format(n, unit)
            n /= 1024.0
        }
        return "%.1fTB".format(n)
    }
}
