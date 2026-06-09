package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayPreferencesTest {
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
