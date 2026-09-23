package com.fife.sa05

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class QuickSettingsAndNotificationTest {
    @Test
    fun `quick settings long press opens main activity`() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map { activities.item(it) as Element }
            .single { it.androidAttribute("name") == ".MainActivity" }
        val intentFilters = mainActivity.getElementsByTagName("intent-filter")

        assertTrue(
            (0 until intentFilters.length)
                .map { intentFilters.item(it) as Element }
                .any { filter ->
                    val actions = filter.getElementsByTagName("action")
                    (0 until actions.length)
                        .map { actions.item(it) as Element }
                        .any {
                            it.androidAttribute("name") ==
                                "android.service.quicksettings.action.QS_TILE_PREFERENCES"
                        }
                }
        )
    }

    @Test
    fun `persistent notification always shows running profile`() {
        assertEquals(
            "Профиль: Germany Reality",
            vpnNotificationContentText(
                runningProfileName = "Germany Reality",
                fallbackProfileName = "Selected fallback"
            )
        )
    }

    @Test
    fun `persistent notification falls back when runtime profile is unavailable`() {
        assertEquals(
            "Профиль: Local Bypass",
            vpnNotificationContentText(
                runningProfileName = "",
                fallbackProfileName = "Local Bypass"
            )
        )
    }

    @Test
    fun `tile presentation reflects connected active state`() {
        val presentation = vpnTilePresentation(
            VpnRuntimeSnapshot(
                status = VpnRunStatus.CONNECTED,
                backend = VpnBackend.PROXY_ONLY,
                profileId = "prof-1",
                profileName = "Germany Reality"
            ),
            settings = null
        )

        assertEquals(VpnTileVisualState.ACTIVE, presentation.state)
        assertEquals("Germany Reality", presentation.subtitle)
        assertEquals("SA05 подключён: Germany Reality", presentation.contentDescription)
    }

    @Test
    fun `tile presentation reflects connecting active state`() {
        val presentation = vpnTilePresentation(
            VpnRuntimeSnapshot(
                status = VpnRunStatus.CONNECTING,
                backend = VpnBackend.PROXY_ONLY,
                profileId = "",
                profileName = "Xray"
            ),
            settings = null
        )

        assertEquals(VpnTileVisualState.ACTIVE, presentation.state)
        assertEquals("Xray", presentation.subtitle)
        assertEquals("SA05 подключается: Xray", presentation.contentDescription)
    }

    @Test
    fun `tile presentation reflects error inactive state`() {
        val presentation = vpnTilePresentation(
            VpnRuntimeSnapshot(
                status = VpnRunStatus.ERROR,
                backend = VpnBackend.PROXY_ONLY,
                profileId = "",
                profileName = "",
                message = "Сеть недоступна"
            ),
            settings = null
        )

        assertEquals(VpnTileVisualState.INACTIVE, presentation.state)
        assertEquals("Нужна проверка", presentation.subtitle)
        assertEquals("SA05: Сеть недоступна", presentation.contentDescription)
    }

    @Test
    fun `tile presentation reflects disconnected with authorized subscription`() {
        val settings = XraySettings(
            config = "",
            subscription = SubscriptionState(
                url = "https://example.com/sub",
                profiles = listOf(
                    SubscriptionProfile("p1", "Netherlands", "{}")
                ),
                activeProfileId = "p1"
            )
        )
        val presentation = vpnTilePresentation(
            VpnRuntimeSnapshot(
                status = VpnRunStatus.DISCONNECTED,
                backend = VpnBackend.PROXY_ONLY,
                profileId = "",
                profileName = ""
            ),
            settings = settings
        )

        assertEquals(VpnTileVisualState.INACTIVE, presentation.state)
        assertEquals("Netherlands", presentation.subtitle)
        assertEquals("SA05 отключён", presentation.contentDescription)
    }

    @Test
    fun `tile presentation reflects disconnected without subscription`() {
        val settings = XraySettings(config = "")
        val presentation = vpnTilePresentation(
            VpnRuntimeSnapshot(
                status = VpnRunStatus.DISCONNECTED,
                backend = VpnBackend.PROXY_ONLY,
                profileId = "",
                profileName = ""
            ),
            settings = settings
        )

        assertEquals(VpnTileVisualState.INACTIVE, presentation.state)
        assertEquals("Нужна ссылка", presentation.subtitle)
        assertEquals("SA05 отключён", presentation.contentDescription)
    }

    @Test
    fun `tile presentation reflects waiting for network active state`() {
        val presentation = vpnTilePresentation(
            VpnRuntimeSnapshot(
                status = VpnRunStatus.WAITING_FOR_NETWORK,
                backend = VpnBackend.FULL_AUTO,
                profileId = "",
                profileName = ""
            ),
            settings = null
        )

        assertEquals(VpnTileVisualState.ACTIVE, presentation.state)
        assertEquals("Ожидание сети", presentation.subtitle)
    }

    @Test
    fun `tile presentation reflects recovering active state`() {
        val presentation = vpnTilePresentation(
            VpnRuntimeSnapshot(
                status = VpnRunStatus.RECOVERING,
                backend = VpnBackend.FULL_AUTO,
                profileId = "",
                profileName = ""
            ),
            settings = null
        )

        assertEquals(VpnTileVisualState.ACTIVE, presentation.state)
        assertEquals("Восстановление", presentation.subtitle)
    }

    @Test
    fun `shouldStopVpnOnTileClick returns true only for active or transition states`() {
        assertTrue(shouldStopVpnOnTileClick(VpnRunStatus.CONNECTING))
        assertTrue(shouldStopVpnOnTileClick(VpnRunStatus.CONNECTED))
        assertTrue(shouldStopVpnOnTileClick(VpnRunStatus.RECOVERING))
        assertTrue(shouldStopVpnOnTileClick(VpnRunStatus.WAITING_FOR_NETWORK))
        org.junit.Assert.assertFalse(shouldStopVpnOnTileClick(VpnRunStatus.DISCONNECTED))
        org.junit.Assert.assertFalse(shouldStopVpnOnTileClick(VpnRunStatus.ERROR))
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
