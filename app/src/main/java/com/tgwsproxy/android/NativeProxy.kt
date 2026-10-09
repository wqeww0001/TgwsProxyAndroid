package com.tgwsproxy.android

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

internal interface ProxyLibrary : Library {
    companion object {
        val INSTANCE: ProxyLibrary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            Native.load("tgwsproxy", ProxyLibrary::class.java) as ProxyLibrary
        }
    }

    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun IsListening(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun SetSecret(secret: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun GetLastTransportError(): Pointer?
    fun FreeString(p: Pointer)
}

object NativeProxy {
    private fun consumeNativeString(ptr: Pointer?): String? {
        val nonNull = ptr ?: return null
        return try {
            nonNull.getString(0, "UTF-8")
        } finally {
            ProxyLibrary.INSTANCE.FreeString(nonNull)
        }
    }

    fun startProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Boolean): Int {
        return ProxyLibrary.INSTANCE.StartProxy(host, port, dcIps, secret, if (verbose) 1 else 0)
    }

    fun stopProxy(): Int = ProxyLibrary.INSTANCE.StopProxy()

    fun isListening(): Boolean = ProxyLibrary.INSTANCE.IsListening() == 1

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

    fun setSecret(secret: String) {
        ProxyLibrary.INSTANCE.SetSecret(secret)
    }

    fun getSecretWithPrefix(): String? =
        consumeNativeString(ProxyLibrary.INSTANCE.GetSecretWithPrefix())

    fun getStats(): String? =
        consumeNativeString(ProxyLibrary.INSTANCE.GetStats())

    fun getLastError(): String? =
        consumeNativeString(ProxyLibrary.INSTANCE.GetLastTransportError())
}
