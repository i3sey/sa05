package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficUsageTest {
    @Test
    fun totalBytesSumsKnownCounters() {
        val usage = TrafficUsage(rxBytes = 5L, txBytes = 7L)
        assertEquals(12L, usage.totalBytes)
        assertEquals(true, usage.isKnown)
    }

    @Test
    fun totalBytesIsUnknownWhenAnyCounterUnknown() {
        assertEquals(TrafficUsage.UNKNOWN, TrafficUsage(TrafficUsage.UNKNOWN, 7L).totalBytes)
        assertEquals(TrafficUsage.UNKNOWN, TrafficUsage(5L, TrafficUsage.UNKNOWN).totalBytes)
        assertEquals(false, TrafficUsage(5L, TrafficUsage.UNKNOWN).isKnown)
    }

    @Test
    fun formatBytesKeepsRawBytesBelowKilo() {
        assertEquals("0 Б", formatTrafficBytes(0))
        assertEquals("1023 Б", formatTrafficBytes(1023))
    }

    @Test
    fun formatBytesScalesToKilo() {
        assertEquals("1,0 КБ", formatTrafficBytes(1024))
        assertEquals("1,5 КБ", formatTrafficBytes(1536))
    }

    @Test
    fun formatBytesScalesToMegaAndGiga() {
        assertEquals("1,0 МБ", formatTrafficBytes(1024L * 1024L))
        assertEquals("100 МБ", formatTrafficBytes(100L * 1024L * 1024L))
        assertEquals("1,0 ГБ", formatTrafficBytes(1024L * 1024L * 1024L))
        assertEquals("2,5 ГБ", formatTrafficBytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatBytesShowsDashForUnknown() {
        assertEquals("—", formatTrafficBytes(TrafficUsage.UNKNOWN))
        assertEquals("—", formatTrafficBytes(-42L))
    }
}
