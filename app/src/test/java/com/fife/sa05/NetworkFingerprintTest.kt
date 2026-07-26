package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFingerprintTest {
    @Test
    fun `same operator and blocking pattern yields the same key`() {
        val a = NetworkFingerprint(VpnNetworkType.MOBILE, "25099", "kinozal-,google+")
        val b = NetworkFingerprint(VpnNetworkType.MOBILE, "25099", "kinozal-,google+")

        assertEquals(a.key, b.key)
    }

    @Test
    fun `a different operator is a different network`() {
        val a = NetworkFingerprint(VpnNetworkType.MOBILE, "25099", "s")
        val b = NetworkFingerprint(VpnNetworkType.MOBILE, "25002", "s")

        assertNotEquals(a.key, b.key)
    }

    @Test
    fun `a different transport is a different network`() {
        val a = NetworkFingerprint(VpnNetworkType.MOBILE, "25099", "s")
        val b = NetworkFingerprint(VpnNetworkType.WIFI, "25099", "s")

        assertNotEquals(a.key, b.key)
    }

    @Test
    fun `unknown parts still produce a usable key`() {
        val key = NetworkFingerprint(VpnNetworkType.OTHER, "", "").key

        assertTrue(key.contains("unknown"))
        assertTrue(key.contains("?"))
    }
}

class DpiSignatureTest {
    private fun result(id: String, status: DiagnosticStatus) = DiagnosticResult(
        target = ConnectivityDiagnostics.target(id),
        status = status
    )

    @Test
    fun `is empty without probes`() {
        assertEquals("", dpiSignature(emptyList()))
    }

    @Test
    fun `does not depend on probe order`() {
        val forward = dpiSignature(
            listOf(
                result("google", DiagnosticStatus.SUCCESS),
                result("kinozal", DiagnosticStatus.FAILED)
            )
        )
        val backward = dpiSignature(
            listOf(
                result("kinozal", DiagnosticStatus.FAILED),
                result("google", DiagnosticStatus.SUCCESS)
            )
        )

        assertEquals(forward, backward)
    }

    @Test
    fun `a newly blocked site changes the signature`() {
        val before = dpiSignature(listOf(result("youtube", DiagnosticStatus.SUCCESS)))
        val after = dpiSignature(listOf(result("youtube", DiagnosticStatus.FAILED)))

        assertNotEquals(before, after)
    }

    @Test
    fun `inconclusive is distinct from both success and failure`() {
        val values = listOf(
            DiagnosticStatus.SUCCESS,
            DiagnosticStatus.FAILED,
            DiagnosticStatus.INCONCLUSIVE
        ).map { dpiSignature(listOf(result("rutracker", it))) }

        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `duplicate probes for one target collapse`() {
        val signature = dpiSignature(
            listOf(
                result("google", DiagnosticStatus.SUCCESS),
                result("google", DiagnosticStatus.FAILED)
            )
        )

        assertEquals(1, signature.split(",").size)
    }
}

class ResolverDigestTest {
    @Test
    fun `is blank without resolvers`() {
        assertEquals("", resolverDigest(emptyList()))
        assertEquals("", resolverDigest(listOf("", "  ")))
    }

    @Test
    fun `does not depend on resolver order`() {
        assertEquals(
            resolverDigest(listOf("1.1.1.1", "8.8.8.8")),
            resolverDigest(listOf("8.8.8.8", "1.1.1.1"))
        )
    }

    @Test
    fun `different resolvers digest differently`() {
        assertNotEquals(
            resolverDigest(listOf("1.1.1.1")),
            resolverDigest(listOf("9.9.9.9"))
        )
    }

    @Test
    fun `does not carry the resolver addresses verbatim`() {
        // The digest travels in an exportable database; raw resolvers would say more about
        // the user's network than the feature needs.
        assertTrue(!resolverDigest(listOf("192.168.7.1")).contains("192.168.7.1"))
    }
}

class StrategyDatabaseTest {
    private val now = 1_700_000_000_000L

    /** AUTO is "let the app decide" and is never a remembered answer, so tests avoid it. */
    private val presets = ZapretPreset.selectable.filter { it != ZapretPreset.AUTO }

    private fun entry(
        key: String = "MOBILE|25099|s",
        preset: ZapretPreset = presets.first(),
        successCount: Int = 1,
        version: Int = 4,
        updatedAt: Long = now
    ) = StrategyMemory(key, preset, successCount, version, updatedAt)

    @Test
    fun `finds a fresh entry`() {
        val found = StrategyDatabase.lookup(listOf(entry()), "MOBILE|25099|s", 4, now)

        assertEquals(presets.first(), found?.preset)
    }

    @Test
    fun `ignores an expired entry`() {
        val stale = entry(updatedAt = now - StrategyDatabase.TTL_MILLIS - 1)

        assertNull(StrategyDatabase.lookup(listOf(stale), "MOBILE|25099|s", 4, now))
    }

    @Test
    fun `ignores an entry from an older algorithm`() {
        assertNull(StrategyDatabase.lookup(listOf(entry(version = 3)), "MOBILE|25099|s", 4, now))
    }

    @Test
    fun `ignores an entry written in the future`() {
        val skewed = entry(updatedAt = now + 60_000)

        assertNull(StrategyDatabase.lookup(listOf(skewed), "MOBILE|25099|s", 4, now))
    }

    @Test
    fun `prefers the most often confirmed preset`() {
        val entries = listOf(
            entry(preset = presets[0], successCount = 1),
            entry(preset = presets[1], successCount = 5)
        )

        assertEquals(presets[1], StrategyDatabase.lookup(entries, "MOBILE|25099|s", 4, now)?.preset)
    }

    @Test
    fun `upsert accumulates confirmations`() {
        var entries = StrategyDatabase.upsert(emptyList(), entry(successCount = 1))
        entries = StrategyDatabase.upsert(entries, entry(successCount = 1))

        assertEquals(1, entries.size)
        assertEquals(2, entries.single().successCount)
    }

    @Test
    fun `upsert is bounded`() {
        var entries = emptyList<StrategyMemory>()
        repeat(200) { index ->
            entries = StrategyDatabase.upsert(entries, entry(key = "net$index"), maxEntries = 10)
        }

        assertEquals(10, entries.size)
    }

    @Test
    fun `prune drops expired entries only`() {
        val fresh = entry()
        val stale = entry(key = "old", updatedAt = now - StrategyDatabase.TTL_MILLIS - 1)

        assertEquals(listOf(fresh), StrategyDatabase.prune(listOf(fresh, stale), now))
    }

    @Test
    fun `encode and decode round-trip`() {
        val entries = listOf(entry(successCount = 3))
        val decoded = StrategyDatabase.decode(StrategyDatabase.encode(entries))

        assertEquals(entries, decoded)
    }

    @Test
    fun `decode survives garbage`() {
        assertEquals(emptyList<StrategyMemory>(), StrategyDatabase.decode("not json"))
        assertEquals(emptyList<StrategyMemory>(), StrategyDatabase.decode(""))
        assertEquals(emptyList<StrategyMemory>(), StrategyDatabase.decode(null))
    }

    @Test
    fun `encode skips entries that carry no decision`() {
        val useless = listOf(
            entry(key = ""),
            entry(preset = ZapretPreset.AUTO)
        )

        assertEquals(emptyList<StrategyMemory>(), StrategyDatabase.decode(StrategyDatabase.encode(useless)))
    }

    @Test
    fun `an import cannot override better local knowledge`() {
        val local = listOf(entry(successCount = 9))
        val imported = listOf(entry(successCount = 2))

        assertEquals(9, StrategyDatabase.merge(local, imported).single().successCount)
    }

    @Test
    fun `an import contributes networks the device has not seen`() {
        val local = listOf(entry(key = "MOBILE|25099|s"))
        val imported = listOf(entry(key = "MOBILE|25002|s"))

        assertEquals(2, StrategyDatabase.merge(local, imported).size)
    }

    @Test
    fun `a better imported entry wins`() {
        val local = listOf(entry(successCount = 1))
        val imported = listOf(entry(successCount = 7))

        assertEquals(7, StrategyDatabase.merge(local, imported).single().successCount)
    }

    @Test
    fun `merge stays bounded`() {
        val local = (1..40).map { entry(key = "local$it") }
        val imported = (1..40).map { entry(key = "imported$it") }

        assertEquals(
            StrategyDatabase.MAX_ENTRIES,
            StrategyDatabase.merge(local, imported).size
        )
    }
}
