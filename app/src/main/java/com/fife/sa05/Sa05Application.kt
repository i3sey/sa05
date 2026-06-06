package com.fife.sa05

import android.app.Application

class Sa05Application : Application() {
    override fun onCreate() {
        super.onCreate()
        // A live VPN service shares this process. A fresh process means the
        // previous TUN and native child processes no longer exist.
        VpnRuntimeState.clear(this)
    }
}
