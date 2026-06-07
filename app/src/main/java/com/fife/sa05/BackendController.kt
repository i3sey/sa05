package com.fife.sa05

import android.content.Context

object BackendController {
    fun startSelected(context: Context) {
        XrayVpnService.start(context)
    }

    fun stopRunning(context: Context) {
        XrayVpnService.stop(context)
    }
}
