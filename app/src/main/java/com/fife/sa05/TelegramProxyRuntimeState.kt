package com.fife.sa05

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TelegramProxyRunStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class TelegramProxyRuntimeSnapshot(
    val status: TelegramProxyRunStatus = TelegramProxyRunStatus.STOPPED,
    val message: String = ""
) {
    val running: Boolean
        get() = status == TelegramProxyRunStatus.RUNNING
}

internal data class TelegramProxyRequests(
    val standalone: Boolean = false,
    val vpn: Boolean = false
) {
    val required: Boolean
        get() = standalone || vpn

    fun withStandalone(enabled: Boolean) = copy(standalone = enabled)
    fun withVpn(enabled: Boolean) = copy(vpn = enabled)
}

/** Persists the actual local TG WS Proxy state independently from Android VPN state. */
object TelegramProxyRuntimeState {
    private const val FILE = "telegram_proxy_runtime"
    private const val KEY_STATUS = "status"
    private const val KEY_MESSAGE = "message"

    private val updates = MutableStateFlow(TelegramProxyRuntimeSnapshot())
    private val state = updates.asStateFlow()
    @Volatile private var initialized = false

    fun observe(context: Context): StateFlow<TelegramProxyRuntimeSnapshot> {
        ensureInitialized(context)
        return state
    }

    fun read(context: Context): TelegramProxyRuntimeSnapshot {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val status = preferences.getString(KEY_STATUS, null)?.let { stored ->
            TelegramProxyRunStatus.entries.firstOrNull { it.name == stored }
        } ?: TelegramProxyRunStatus.STOPPED
        return TelegramProxyRuntimeSnapshot(
            status = status,
            message = preferences.getString(KEY_MESSAGE, "").orEmpty()
        )
    }

    fun publish(context: Context, snapshot: TelegramProxyRuntimeSnapshot) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, snapshot.status.name)
            .putString(KEY_MESSAGE, snapshot.message)
            .commit()
        initialized = true
        updates.value = snapshot
    }

    fun clear(context: Context) = publish(context, TelegramProxyRuntimeSnapshot())

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                updates.value = read(context)
                initialized = true
            }
        }
    }
}
