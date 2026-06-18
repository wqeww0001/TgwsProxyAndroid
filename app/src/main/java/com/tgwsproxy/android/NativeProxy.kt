package com.tgwsproxy.android

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

private interface ProxyLibrary : Library {
    companion object {
        val INSTANCE: ProxyLibrary = Native.load("tgwsproxy", ProxyLibrary::class.java) as ProxyLibrary
    }

    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)
}

object NativeProxy {
    fun startProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Boolean): Int {
        return ProxyLibrary.INSTANCE.StartProxy(host, port, dcIps, secret, if (verbose) 1 else 0)
    }

    fun stopProxy(): Int = ProxyLibrary.INSTANCE.StopProxy()

    fun setPoolSize(size: Int) {
        ProxyLibrary.INSTANCE.SetPoolSize(size)
    }

    fun setCfProxyCacheDir(cacheDir: String) {
        ProxyLibrary.INSTANCE.SetCfProxyCacheDir(cacheDir)
    }

    fun setCfProxyConfig(enabled: Boolean, priority: Boolean, userDomain: String) {
        ProxyLibrary.INSTANCE.SetCfProxyConfig(
            if (enabled) 1 else 0,
            if (priority) 1 else 0,
            userDomain,
        )
    }

    fun getSecretWithPrefix(): String? {
        val ptr = ProxyLibrary.INSTANCE.GetSecretWithPrefix() ?: return null
        val result = ptr.getString(0)
        ProxyLibrary.INSTANCE.FreeString(ptr)
        return result
    }

    fun getStats(): String? {
        val ptr = ProxyLibrary.INSTANCE.GetStats() ?: return null
        val result = ptr.getString(0)
        ProxyLibrary.INSTANCE.FreeString(ptr)
        return result
    }
}
