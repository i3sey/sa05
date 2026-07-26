package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Test

class UidTrafficCounterTest {
    private class FakeCounters(var rx: Long = 0, var tx: Long = 0)

    private fun counter(counters: FakeCounters) = UidTrafficCounter(
        uid = 1000,
        readRx = { counters.rx },
        readTx = { counters.tx }
    )

    @Test
    fun `reports nothing before the tunnel starts`() {
        val counters = FakeCounters(rx = 5_000, tx = 4_000)

        assertEquals(VpnTraffic(), counter(counters).sinceStart())
    }

    @Test
    fun `counts only what moved after start`() {
        val counters = FakeCounters(rx = 5_000, tx = 4_000)
        val counter = counter(counters)

        counter.start()
        counters.rx += 1_500
        counters.tx += 700

        assertEquals(VpnTraffic(rxBytes = 1_500, txBytes = 700), counter.sinceStart())
    }

    @Test
    fun `a counter reset by the system never goes negative`() {
        val counters = FakeCounters(rx = 5_000, tx = 4_000)
        val counter = counter(counters)
        counter.start()

        // Reboot-style rollback: the kernel counters restart from zero.
        counters.rx = 10
        counters.tx = 0

        assertEquals(VpnTraffic(rxBytes = 0, txBytes = 0), counter.sinceStart())
    }

    @Test
    fun `unsupported counters read as zero`() {
        val counters = FakeCounters(rx = -1, tx = -1)
        val counter = counter(counters)

        counter.start()
        counters.rx = -1

        assertEquals(VpnTraffic(), counter.sinceStart())
    }

    @Test
    fun `reset stops reporting until the next start`() {
        val counters = FakeCounters(rx = 100, tx = 100)
        val counter = counter(counters)
        counter.start()
        counters.rx += 50

        counter.reset()

        assertEquals(VpnTraffic(), counter.sinceStart())
    }

    @Test
    fun `restart rebaselines`() {
        val counters = FakeCounters(rx = 100, tx = 100)
        val counter = counter(counters)
        counter.start()
        counters.rx += 500

        counter.start()
        counters.rx += 20

        assertEquals(VpnTraffic(rxBytes = 20, txBytes = 0), counter.sinceStart())
    }
}

class VpnNotificationContentTest {
    @Test
    fun `falls back to the selected profile name`() {
        assertEquals(
            "Профиль: Резервный",
            vpnNotificationContentText(runningProfileName = "", fallbackProfileName = "Резервный")
        )
    }

    @Test
    fun `prefers the running profile name`() {
        assertEquals(
            "Профиль: Активный",
            vpnNotificationContentText(
                runningProfileName = "Активный",
                fallbackProfileName = "Резервный"
            )
        )
    }

    @Test
    fun `omits traffic until something moved`() {
        assertEquals(
            "Профиль: Сервер",
            vpnNotificationContentText(
                runningProfileName = "Сервер",
                fallbackProfileName = "",
                traffic = VpnTraffic()
            )
        )
    }

    @Test
    fun `appends traffic once it moves`() {
        assertEquals(
            "Профиль: Сервер · ↓ 1,5 КБ ↑ 512 Б",
            vpnNotificationContentText(
                runningProfileName = "Сервер",
                fallbackProfileName = "",
                traffic = VpnTraffic(rxBytes = 1_536, txBytes = 512)
            )
        )
    }

    @Test
    fun `shows traffic even when only one direction moved`() {
        assertEquals(
            "Профиль: Сервер · ↓ 0 Б ↑ 2,0 КБ",
            vpnNotificationContentText(
                runningProfileName = "Сервер",
                fallbackProfileName = "",
                traffic = VpnTraffic(rxBytes = 0, txBytes = 2_048)
            )
        )
    }
}
