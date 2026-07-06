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

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
