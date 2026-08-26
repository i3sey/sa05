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
    suspend fun startSelected(context: Context): Boolean {
        val settings = XrayPreferences.snapshot(context)
        if (!SubscriptionAuth.isAuthorized(settings.subscription)) return false
        if (effectiveVpnBackend(settings) == VpnBackend.YCTUN &&
            BsTraffic.isExceeded(context)
        ) {
            val policy = BsTraffic.policy(context)
            val used = BsTraffic.reconcileUsedBytes(context, policy)
            VpnRuntimeState.publish(
                context,
                VpnRuntimeSnapshot(
                    status = VpnRunStatus.ERROR,
                    backend = VpnBackend.YCTUN,
                    profileId = settings.subscription.activeProfileId,
                    profileName = settings.subscription.activeProfile?.remarks.orEmpty(),
                    message = "Лимит трафика БС: ${formatTrafficBytes(used)} из " +
                        formatTrafficBytes(policy.limitBytes),
                    failureKind = VpnFailureKind.QUOTA
                )
            )
            return false
        }
        XrayVpnService.start(context)
        return true
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
