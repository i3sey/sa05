package com.fife.sa05

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Sa05Application : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // A live VPN service shares this process. A fresh process means the
        // previous TUN and native child processes no longer exist.
        // Isolated diagnostics has no access to app storage or preferences.
        runCatching { VpnRuntimeState.clear(this) }
        runCatching { TelegramProxyRuntimeState.clear(this) }
        applicationScope.launch {
            runCatching {
                SubscriptionRefreshScheduler.sync(
                    this@Sa05Application,
                    XrayPreferences.snapshot(this@Sa05Application).subscription
                )
            }
        }
    }
}
