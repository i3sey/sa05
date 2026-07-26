package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectWhitelistTest {
    private fun result(id: String, reachable: Boolean) = DiagnosticResult(
        target = ConnectivityDiagnostics.target(id),
        status = if (reachable) DiagnosticStatus.SUCCESS else DiagnosticStatus.FAILED
    )

    private fun verdict(vararg pairs: Pair<String, Boolean>) =
        detectWhitelist(pairs.map { result(it.first, it.second) })

    @Test
    fun `domestic works while everything foreign is dead`() {
        val v = verdict(
            "yandex" to true,
            "google" to false,
            "youtube" to false,
            "telegram" to false
        )

        assertTrue(v.suspected)
        assertEquals(listOf("yandex"), v.workingDomestic)
        assertEquals(listOf("google", "telegram", "youtube"), v.blockedForeign)
    }

    @Test
    fun `a healthy network is not an allow-list`() {
        assertFalse(
            verdict("yandex" to true, "google" to true, "youtube" to true).suspected
        )
    }

    @Test
    fun `being offline is not an allow-list`() {
        // Nothing answers, including the domestic probe: that is a dead connection, and
        // telling the user to change profile would send them chasing the wrong thing.
        assertFalse(
            verdict("yandex" to false, "google" to false, "youtube" to false).suspected
        )
    }

    @Test
    fun `ordinary filtering is not an allow-list`() {
        // Foreign control still answers; only the usual blocked trackers do not.
        assertFalse(
            verdict(
                "yandex" to true,
                "google" to true,
                "youtube" to true,
                "kinozal" to false,
                "nnmclub" to false
            ).suspected
        )
    }

    @Test
    fun `one foreign failure is not enough evidence`() {
        assertFalse(verdict("yandex" to true, "google" to false).suspected)
    }

    @Test
    fun `a single reachable foreign probe clears the suspicion`() {
        assertFalse(
            verdict(
                "yandex" to true,
                "google" to false,
                "youtube" to false,
                "telegram" to true
            ).suspected
        )
    }

    @Test
    fun `blocked trackers neither raise nor clear the verdict`() {
        // They are blocked in the normal case too, so they carry no signal either way.
        val withTrackers = verdict(
            "yandex" to true,
            "google" to false,
            "youtube" to false,
            "kinozal" to false,
            "rutracker" to false
        )

        assertTrue(withTrackers.suspected)
        assertTrue(withTrackers.blockedForeign.none { it == "kinozal" || it == "rutracker" })
    }

    @Test
    fun `no probes at all means no verdict`() {
        assertFalse(detectWhitelist(emptyList()).suspected)
    }

    @Test
    fun `an inconclusive foreign probe still counts as unreachable`() {
        val results = listOf(
            result("yandex", true),
            result("google", false),
            DiagnosticResult(
                target = ConnectivityDiagnostics.target("youtube"),
                status = DiagnosticStatus.INCONCLUSIVE
            )
        )

        assertTrue(detectWhitelist(results).suspected)
    }
}

class FindWhitelistBypassProfileTest {
    private fun profile(remarks: String) =
        SubscriptionProfile(id = remarks, remarks = remarks, json = "{}")

    @Test
    fun `finds the profile despite the provider's typo`() {
        // The live subscription really does say "списоков".
        val profiles = listOf(
            profile("🇩🇪 Авто"),
            profile("Обход белых списоков (только SA05 beta)"),
            profile("🇩🇪 Германия")
        )

        assertEquals(
            "Обход белых списоков (только SA05 beta)",
            findWhitelistBypassProfile(profiles)?.remarks
        )
    }

    @Test
    fun `matches other spellings a provider might use`() {
        listOf(
            "Обход белого списка",
            "БЕЛЫЕ СПИСКИ",
            "whitelist / белый список",
            "обход белых списков"
        ).forEach { remark ->
            assertEquals(remark, findWhitelistBypassProfile(listOf(profile(remark)))?.remarks)
        }
    }

    @Test
    fun `returns nothing when the subscription has no such profile`() {
        val profiles = listOf(profile("🇩🇪 Германия"), profile("🇸🇪 Швеция"))

        assertNull(findWhitelistBypassProfile(profiles))
    }

    @Test
    fun `does not match an ordinary profile that merely mentions one word`() {
        assertNull(findWhitelistBypassProfile(listOf(profile("Белград"))))
        assertNull(findWhitelistBypassProfile(listOf(profile("Список серверов"))))
    }

    @Test
    fun `an empty subscription yields nothing`() {
        assertNull(findWhitelistBypassProfile(emptyList()))
    }
}
