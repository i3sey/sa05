package com.fife.sa05

internal enum class ProfileSwitchAction {
    NO_CHANGE,
    SAVE_ONLY,
    SAVE_AND_RECONNECT
}

internal fun profileSwitchAction(
    currentProfileId: String,
    selectedProfileId: String,
    runtimeStatus: VpnRunStatus
): ProfileSwitchAction = when {
    currentProfileId == selectedProfileId -> ProfileSwitchAction.NO_CHANGE
    runtimeStatus != VpnRunStatus.DISCONNECTED -> ProfileSwitchAction.SAVE_AND_RECONNECT
    else -> ProfileSwitchAction.SAVE_ONLY
}
