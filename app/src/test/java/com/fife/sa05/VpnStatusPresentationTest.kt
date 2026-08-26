package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Test

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

    @Test
    fun `quota failure keeps connect blocked`() {
        val presentation = vpnStatusPresentation(
            VpnRuntimeSnapshot(
                status = VpnRunStatus.ERROR,
                backend = VpnBackend.YCTUN,
                profileId = BsProfile.ID,
                profileName = BsProfile.REMARKS,
                message = "Лимит трафика БС",
                failureKind = VpnFailureKind.QUOTA
            )
        )

        assertEquals("Лимит трафика БС исчерпан", presentation.title)
        assertEquals(VpnPrimaryAction.CONNECT, presentation.primaryAction)
        assertEquals(emptyList<VpnSecondaryAction>(), presentation.secondaryActions)
    }
}
