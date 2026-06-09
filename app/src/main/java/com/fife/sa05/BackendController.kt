package com.fife.sa05

import android.content.Context

object BackendController {
    fun startSelected(context: Context): Boolean {
        if (!SubscriptionAuth.isAuthorized(context)) return false
        XrayVpnService.start(context)
        return true
    }

    fun stopRunning(context: Context) {
        XrayVpnService.stop(context)
    }
}
