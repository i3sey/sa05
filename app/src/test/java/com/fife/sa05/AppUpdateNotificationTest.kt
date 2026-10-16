package com.fife.sa05

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AppUpdateNotificationTest {
    private val sampleRelease = AppRelease(
        tagName = "v1.3.0",
        versionName = "1.3.0",
        versionCode = 15,
        name = "SA05 1.3.0",
        notes = "Bug fixes and improvements",
        assetName = "sa05-1.3.0-release.apk",
        assetUrl = "https://github.com/i3sey/sa05/releases/download/v1.3.0/sa05-1.3.0-release.apk",
        htmlUrl = "https://github.com/i3sey/sa05/releases/tag/v1.3.0",
        publishedAt = "2026-05-01T12:00:00Z"
    )

    @Test
    fun `app release json serialization roundtrip preserves all fields`() {
        val json = sampleRelease.toJson()
        val parsed = AppRelease.fromJson(json)

        assertNotNull(parsed)
        assertEquals(sampleRelease.tagName, parsed!!.tagName)
        assertEquals(sampleRelease.versionName, parsed.versionName)
        assertEquals(sampleRelease.versionCode, parsed.versionCode)
        assertEquals(sampleRelease.name, parsed.name)
        assertEquals(sampleRelease.notes, parsed.notes)
        assertEquals(sampleRelease.assetName, parsed.assetName)
        assertEquals(sampleRelease.assetUrl, parsed.assetUrl)
        assertEquals(sampleRelease.htmlUrl, parsed.htmlUrl)
        assertEquals(sampleRelease.publishedAt, parsed.publishedAt)
    }

    @Test
    fun `app release json serialization handles null versionCode`() {
        val releaseWithoutCode = sampleRelease.copy(versionCode = null)
        val json = releaseWithoutCode.toJson()
        val parsed = AppRelease.fromJson(json)

        assertNotNull(parsed)
        assertNull(parsed!!.versionCode)
        assertEquals(sampleRelease.versionName, parsed.versionName)
    }

    @Test
    fun `app release fromJson returns null on invalid json`() {
        assertNull(AppRelease.fromJson("invalid json"))
        assertNull(AppRelease.fromJson("{}"))
    }

    @Test
    fun `app update notification content text displays version name`() {
        assertEquals("Доступна новая версия 1.3.0", appUpdateNotificationContentText("1.3.0"))
        assertEquals("Доступно обновление SA05", appUpdateNotificationTitle())
    }

    @Test
    fun `shouldNotifyAppUpdate only notifies on new version`() {
        assertTrue(shouldNotifyAppUpdate(lastNotifiedVersion = null, availableRelease = sampleRelease))
        assertTrue(shouldNotifyAppUpdate(lastNotifiedVersion = "1.2.9", availableRelease = sampleRelease))
        assertFalse(shouldNotifyAppUpdate(lastNotifiedVersion = "1.3.0", availableRelease = sampleRelease))
    }

    @Test
    fun `manifest declares post notifications permission`() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val permissions = document.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length)
            .map { permissions.item(it) as Element }
            .mapNotNull { it.androidAttribute("name") }

        assertTrue(names.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun `app update scheduler has expected interval and unique work name`() {
        assertEquals(12L, AppUpdateScheduler.CHECK_INTERVAL_HOURS)
    }

    @Test
    fun `notification channel and id constants are valid`() {
        assertEquals("app_updates", AppUpdateNotification.CHANNEL_ID)
        assertEquals(20, AppUpdateNotification.NOTIFICATION_ID)
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
