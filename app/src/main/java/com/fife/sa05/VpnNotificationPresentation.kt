package com.fife.sa05

internal fun vpnNotificationContentText(
    runningProfileName: String,
    fallbackProfileName: String
): String = "Профиль: ${runningProfileName.ifBlank { fallbackProfileName }}"
