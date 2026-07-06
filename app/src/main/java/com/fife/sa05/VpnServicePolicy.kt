package com.fife.sa05

internal enum class VpnServiceCommand {
    START,
    STOP,
    IGNORE
}

internal fun vpnServiceCommand(action: String?): VpnServiceCommand = when (action) {
    XrayVpnService.ACTION_START,
    XrayVpnService.ACTION_RECONNECT -> VpnServiceCommand.START
    XrayVpnService.ACTION_STOP -> VpnServiceCommand.STOP
    else -> VpnServiceCommand.IGNORE
}

internal enum class XrayRuntime {
    STOPPED,
    PLAIN_PROFILE,
    FULL_AUTO_YOUTUBE
}

internal data class VpnProcessHealth(
    val tun: Boolean,
    val tun2socks: Boolean,
    val proxy: Boolean,
    val bridge: Boolean,
    val auxiliary: Boolean,
    val telegram: Boolean
)

internal fun requiredProcessesRunning(
    backend: VpnBackend,
    xrayRuntime: XrayRuntime,
    health: VpnProcessHealth
): Boolean {
    if (!health.tun || !health.tun2socks) return false
    return when (backend) {
        VpnBackend.PROXY_ONLY -> health.proxy
        VpnBackend.LOCAL_BYPASS -> health.proxy && health.bridge && health.telegram
        VpnBackend.FULL_AUTO -> {
            val base = health.proxy && health.telegram
            if (xrayRuntime == XrayRuntime.FULL_AUTO_YOUTUBE) {
                base && health.auxiliary && health.bridge
            } else {
                base
            }
        }
    }
}
