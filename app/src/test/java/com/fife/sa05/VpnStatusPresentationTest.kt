package com.fife.sa05

import com.fife.sa05.components.VpnHeroState
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnHeroStateTest {
    @Test
    fun `off only when disconnected`() {
        assertEquals(VpnHeroState.OFF, vpnHeroState(VpnRunStatus.DISCONNECTED))
    }

    @Test
    fun `on only when connected`() {
        assertEquals(VpnHeroState.ON, vpnHeroState(VpnRunStatus.CONNECTED))
    }

    @Test
    fun `working states read as busy`() {
        listOf(
            VpnRunStatus.CONNECTING,
            VpnRunStatus.RECOVERING,
            // Still up and will resume by itself; presenting it as a failure would send the
            // user off to fix something that is not broken.
            VpnRunStatus.WAITING_FOR_NETWORK
        ).forEach {
            assertEquals("$it", VpnHeroState.BUSY, vpnHeroState(it))
        }
    }

    @Test
    fun `only a real error reads as failed`() {
        assertEquals(VpnHeroState.FAILED, vpnHeroState(VpnRunStatus.ERROR))
    }

    @Test
    fun `every status maps to something`() {
        VpnRunStatus.entries.forEach { vpnHeroState(it) }
    }
}

class VpnStatusPresentationTest {
    @Test
    fun `network failure offers retry network settings and diagnostics`() {
        val presentation = vpnStatusPresentation(
            VpnRuntimeSnapshot(
                status = VpnRunStatus.ERROR,
                backend = VpnBackend.PROXY_ONLY,
                profileId = "profile",
                profileName = "Server",
                message = "Сеть недоступна",
                failureKind = VpnFailureKind.NETWORK
            )
        )

        assertEquals("Не удалось подключить VPN", presentation.title)
        assertEquals(VpnPrimaryAction.RETRY, presentation.primaryAction)
        assertEquals(
            listOf(VpnSecondaryAction.NETWORK_SETTINGS, VpnSecondaryAction.DIAGNOSTICS),
            presentation.secondaryActions
        )
    }

    @Test
    fun `local bypass failure offers strategy change`() {
        val presentation = vpnStatusPresentation(
            VpnRuntimeSnapshot(
                status = VpnRunStatus.ERROR,
                backend = VpnBackend.LOCAL_BYPASS,
                profileId = "",
                profileName = "Локальный обход",
                message = "ByeDPI остановился",
                failureKind = VpnFailureKind.BACKEND
            )
        )

        assertEquals(VpnPrimaryAction.RETRY, presentation.primaryAction)
        assertEquals(
            listOf(VpnSecondaryAction.CHANGE_STRATEGY, VpnSecondaryAction.DIAGNOSTICS),
            presentation.secondaryActions
        )
    }

    @Test
    fun `waiting for a network keeps stop available`() {
        val presentation = vpnStatusPresentation(
            VpnRuntimeSnapshot(
                status = VpnRunStatus.WAITING_FOR_NETWORK,
                backend = VpnBackend.FULL_AUTO,
                profileId = "profile",
                profileName = "Server",
                failureKind = VpnFailureKind.NETWORK
            )
        )

        assertEquals(VpnPrimaryAction.STOP, presentation.primaryAction)
        assertEquals(
            listOf(VpnSecondaryAction.NETWORK_SETTINGS),
            presentation.secondaryActions
        )
    }

    @Test
    fun `authorization failure opens subscription instead of retrying`() {
        val presentation = vpnStatusPresentation(
            VpnRuntimeSnapshot(
                status = VpnRunStatus.ERROR,
                backend = VpnBackend.FULL_AUTO,
                profileId = "",
                profileName = "",
                message = "Нужна действующая подписка",
                failureKind = VpnFailureKind.AUTHORIZATION
            )
        )

        assertEquals("Нужна действующая подписка", presentation.title)
        assertEquals(VpnPrimaryAction.OPEN_SUBSCRIPTION, presentation.primaryAction)
        assertEquals(emptyList<VpnSecondaryAction>(), presentation.secondaryActions)
    }
}
