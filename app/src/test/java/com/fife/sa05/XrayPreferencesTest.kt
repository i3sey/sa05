package com.fife.sa05

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayPreferencesTest {
    @Test
    fun dataStoreDefaultsAreSafe() {
        val settings = XrayPreferences.decodeSettings(emptyPreferences())

        assertEquals(VpnBackend.PROXY_ONLY, settings.vpnBackend)
        assertEquals(ZapretPreset.AUTO, settings.zapretPreset)
        assertEquals(true, settings.dynamicColor)
        assertEquals(false, settings.advancedModeEnabled)
        assertEquals(true, settings.telegramCfEnabled)
        assertEquals(XrayPreferences.defaultConfig, settings.config)
    }

    @Test
    fun dataStoreSnapshotReadsMigratedPreferences() {
        val settings = XrayPreferences.decodeSettings(
            preferencesOf(
                stringPreferencesKey("vpn_backend") to VpnBackend.PROXY_ONLY.name,
                stringPreferencesKey("subscription") to
                    """{"url":"https://example.com/sub","profiles":[]}""",
                stringSetPreferencesKey("excluded") to setOf("org.example.browser"),
                booleanPreferencesKey("dynamic_color") to false,
                booleanPreferencesKey("advanced_mode") to true
            )
        )

        assertEquals(VpnBackend.PROXY_ONLY, settings.vpnBackend)
        assertEquals("https://example.com/sub", settings.subscription.url)
        assertEquals(setOf("org.example.browser"), settings.excludedApps)
        assertEquals(false, settings.dynamicColor)
        assertEquals(true, settings.advancedModeEnabled)
    }

    @Test
    fun subscriptionEncodingRoundTripsAllFields() {
        val state = SubscriptionState(
            url = "https://example.com/sub",
            title = "Test",
            profiles = listOf(
                SubscriptionProfile("profile-1", "Primary", "{\"inbounds\":[]}")
            ),
            activeProfileId = "profile-1",
            updatedAt = 123L,
            etag = "etag",
            userInfo = "upload=1",
            updateIntervalHours = 12,
            suggestedBypassApps = setOf("org.example.browser")
        )

        assertEquals(
            state,
            XrayPreferences.decodeSubscription(XrayPreferences.encodeSubscription(state))
        )
    }

    @Test
    fun autoCacheMapKeepsSeparateNetworks() {
        val caches = listOf(
            ZapretAutoCache("wifi-a", ZapretPreset.ADAPTIVE, 1, 4),
            ZapretAutoCache("mobile-b", ZapretPreset.YOUTUBE_STABLE, 2, 4)
        )

        val decoded = XrayPreferences.decodeAutoCacheMap(
            XrayPreferences.encodeAutoCacheMap(caches)
        )

        assertEquals(2, decoded.size)
        assertEquals(ZapretPreset.ADAPTIVE, decoded.single { it.networkKey == "wifi-a" }.preset)
        assertEquals(
            ZapretPreset.YOUTUBE_STABLE,
            decoded.single { it.networkKey == "mobile-b" }.preset
        )
    }

    @Test
    fun autoCacheMapUpdatesExistingNetworkAsMostRecent() {
        val updated = XrayPreferences.upsertCache(
            listOf(
                ZapretAutoCache("wifi-a", ZapretPreset.ADAPTIVE, 1, 4),
                ZapretAutoCache("mobile-b", ZapretPreset.YOUTUBE_STABLE, 2, 4)
            ),
            ZapretAutoCache("wifi-a", ZapretPreset.TLS_MINOR, 3, 5)
        )

        assertEquals(2, updated.size)
        assertEquals("mobile-b", updated.first().networkKey)
        assertEquals("wifi-a", updated.last().networkKey)
        assertEquals(ZapretPreset.TLS_MINOR, updated.last().preset)
        assertEquals(5, updated.last().algorithmVersion)
    }

    @Test
    fun autoCacheMapDropsOldestEntries() {
        val caches = (1..4).map {
            ZapretAutoCache("network-$it", ZapretPreset.ADAPTIVE, it, 4)
        }

        val decoded = XrayPreferences.decodeAutoCacheMap(
            XrayPreferences.encodeAutoCacheMap(caches, maxEntries = 2),
            maxEntries = 2
        )

        assertEquals(listOf("network-3", "network-4"), decoded.map { it.networkKey })
    }

    @Test
    fun autoCacheMapIgnoresInvalidEntries() {
        val decoded = XrayPreferences.decodeAutoCacheMap(
            """
                {
                  "bad": {"preset":"AUTO","reachableCount":9,"algorithmVersion":4},
                  "good": {"preset":"DISORDER","reachableCount":1,"algorithmVersion":4}
                }
            """.trimIndent()
        )

        assertEquals(1, decoded.size)
        assertEquals("good", decoded.single().networkKey)
        assertTrue(decoded.none { it.preset == ZapretPreset.AUTO })
    }
}
