package com.fife.sa05

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

private interface TelegramProxyLibrary : Library {
    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun GetStats(): Pointer?
    fun FreeString(pointer: Pointer)
}

object TelegramNativeProxy {
    private val library: TelegramProxyLibrary by lazy {
        Native.load("tgwsproxy", TelegramProxyLibrary::class.java)
            as TelegramProxyLibrary
    }

    fun start(
        cacheDir: String,
        secret: String,
        cloudflareEnabled: Boolean,
        cloudflareDomain: String
    ): Int {
        library.SetPoolSize(TelegramProxyConfig.POOL_SIZE)
        library.SetCfProxyCacheDir(cacheDir)
        library.SetCfProxyConfig(
            if (cloudflareEnabled) 1 else 0,
            1,
            cloudflareDomain
        )
        return library.StartProxy(
            "127.0.0.1",
            TelegramProxyConfig.PORT,
            "",
            secret,
            1
        )
    }

    fun stop(): Int = library.StopProxy()

    fun stats(): String? {
        val pointer = library.GetStats() ?: return null
        return try {
            pointer.getString(0)
        } finally {
            library.FreeString(pointer)
        }
    }
}
