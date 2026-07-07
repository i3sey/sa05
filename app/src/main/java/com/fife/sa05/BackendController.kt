package com.fife.sa05

import android.content.Context

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

    fun stopRunning(context: Context) {
        XrayVpnService.stop(context)
    }
}
