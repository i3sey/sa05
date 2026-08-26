package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BsTrafficTest {
    @Test
    fun normalizeCodeStripsSeparatorsAndUppercases() {
        assertEquals(
            "A03HPGHIZN4RRYGD",
            BsTraffic.normalizeCode(" a03h-pghi-zn4r-rygd ")
        )
        assertEquals(
            "EN9UPCZ1P4MI1B6U",
            BsTraffic.normalizeCode("en9u pcz1 p4mi 1b6u")
        )
    }

    @Test
    fun hashCodeMatchesKnownVector() {
        assertEquals(
            "ab29ab58ff18b4563c498e4f650954457941f78c37c2a37c2d4711b2002c63eb",
            BsTraffic.hashCode("A03HPGHIZN4RRYGD")
        )
    }

    @Test
    fun internetCodeHashesContainBonuses() {
        assertEquals(536_870_912L, BsTraffic.INTERNET_CODE_HASHES[
            BsTraffic.hashCode("A03HPGHIZN4RRYGD")
        ])
        assertEquals(
            10_737_418_240L,
            BsTraffic.INTERNET_CODE_HASHES[
                BsTraffic.hashCode("80N4CJF7N61ZJDDM")
            ]
        )
    }

    @Test
    fun lookupReturnsNullForUnknownCode() {
        assertNull(
            BsTraffic.INTERNET_CODE_HASHES[
                BsTraffic.hashCode(BsTraffic.normalizeCode("ZZZZ-ZZZZ-ZZZZ-ZZZZ"))
            ]
        )
    }

    @Test
    fun snapshotFlagsWarningAndExceeded() {
        val warning = BsTraffic.Snapshot(
            session = TrafficUsage.EMPTY,
            periodUsedBytes = 900L * 1024L * 1024L,
            limitBytes = 1024L * 1024L * 1024L,
            expireAtSeconds = 1_735_689_600L
        )
        assertTrue(warning.isWarning)
        assertFalse(warning.isCritical)
        assertFalse(warning.isExceeded)
        assertEquals(87, warning.usagePercent)

        val critical = warning.copy(periodUsedBytes = 950L * 1024L * 1024L)
        assertTrue(critical.isCritical)

        val exceeded = warning.copy(periodUsedBytes = 1024L * 1024L * 1024L)
        assertTrue(exceeded.isExceeded)
        assertFalse(exceeded.isWarning)
    }

    @Test
    fun defaultLimitIsOneGb() {
        assertEquals(1024L * 1024L * 1024L, BsTraffic.DEFAULT_LIMIT_BYTES)
    }
}
