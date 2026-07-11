package com.fife.sa05

import android.content.Context
import kotlinx.coroutines.delay

internal fun startAuthorizedBackend(
    authorized: Boolean,
    start: () -> Unit
): Boolean {
    if (!authorized) return false
    start()
    return true
}

object BackendController {
    suspend fun startSelected(context: Context): Boolean =
        startAuthorizedBackend(
            SubscriptionAuth.isAuthorized(XrayPreferences.snapshot(context).subscription)
        ) {
            XrayVpnService.start(context)
        }

    /** Starts only the local TG WS Proxy; no subscription or VPN permission is involved. */
    suspend fun startTelegramOnly(context: Context): Boolean {
        stopRunning(context)
        repeat(60) {
            if (VpnRuntimeState.read(context).status == VpnRunStatus.DISCONNECTED) {
                TelegramProxyService.startStandalone(context)
                return TelegramProxyService.awaitRunning(context)
            }
            delay(50)
        }
        return false
    }

    fun stopTelegramOnly(context: Context) {
        TelegramProxyService.stopStandalone(context)
    }

    fun stopRunning(context: Context) {
        if (VpnRuntimeState.read(context).status != VpnRunStatus.DISCONNECTED) {
            XrayVpnService.stop(context)
        }
    }
}
