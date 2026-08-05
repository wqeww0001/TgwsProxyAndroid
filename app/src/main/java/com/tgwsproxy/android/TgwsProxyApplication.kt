package com.tgwsproxy.android

import android.app.Application
import com.tgwsproxy.android.proxy.ProxyLogger

class TgwsProxyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProxyLogger.initialize(this)
        AppDiagnostics.recordPreviousExits(this)
    }
}
