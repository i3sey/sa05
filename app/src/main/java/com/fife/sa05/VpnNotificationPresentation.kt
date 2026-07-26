package com.fife.sa05

internal fun vpnNotificationContentText(
    runningProfileName: String,
    fallbackProfileName: String,
    traffic: VpnTraffic = VpnTraffic()
): String {
    val profile = "Профиль: ${runningProfileName.ifBlank { fallbackProfileName }}"
    if (traffic.rxBytes == 0L && traffic.txBytes == 0L) return profile
    return "$profile · ↓ ${formatBytes(traffic.rxBytes)} ↑ ${formatBytes(traffic.txBytes)}"
}
