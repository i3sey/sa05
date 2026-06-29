package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSelectionTest {
    @Test
    fun connectedVpnReconnectsAfterProfileChange() {
        assertEquals(
            ProfileSwitchAction.SAVE_AND_RECONNECT,
            profileSwitchAction(
                currentProfileId = "profile-a",
                selectedProfileId = "profile-b",
                runtimeStatus = VpnRunStatus.CONNECTED
            )
        )
    }

    @Test
    fun disconnectedVpnOnlySavesProfileChange() {
        assertEquals(
            ProfileSwitchAction.SAVE_ONLY,
            profileSwitchAction(
                currentProfileId = "profile-a",
                selectedProfileId = "profile-b",
                runtimeStatus = VpnRunStatus.DISCONNECTED
            )
        )
    }

    @Test
    fun connectingVpnRestartsWithSelectedProfile() {
        assertEquals(
            ProfileSwitchAction.SAVE_AND_RECONNECT,
            profileSwitchAction(
                currentProfileId = "profile-a",
                selectedProfileId = "profile-b",
                runtimeStatus = VpnRunStatus.CONNECTING
            )
        )
    }

    @Test
    fun selectingCurrentProfileDoesNothing() {
        assertEquals(
            ProfileSwitchAction.NO_CHANGE,
            profileSwitchAction(
                currentProfileId = "profile-a",
                selectedProfileId = "profile-a",
                runtimeStatus = VpnRunStatus.CONNECTED
            )
        )
    }
}
