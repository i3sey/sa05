package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnModeTest {
    @Test
    fun zapretCommandBindsOnlyToLoopback() {
        val command = ZapretCommand.build("/native/libciadpi.so", 10810, ZapretPreset.DISORDER)

        assertEquals("/native/libciadpi.so", command.first())
        assertTrue(command.windowed(2).contains(listOf("--ip", "127.0.0.1")))
        assertTrue(command.windowed(2).contains(listOf("--port", "10810")))
        assertTrue(command.windowed(2).contains(listOf("--conn-ip", "0.0.0.0")))
        assertTrue(command.windowed(2).contains(listOf("--disorder", "1")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun autoPresetCannotBeStartedDirectly() {
        ZapretCommand.build("/native/libciadpi.so", 10810, ZapretPreset.AUTO)
    }

    @Test
    fun autoSelectionKeepsFirstPresetWithBestScore() {
        val result = ZapretAutoSelection.best(
            listOf(
                ZapretPreset.ADAPTIVE to 2,
                ZapretPreset.DISORDER to 3,
                ZapretPreset.TLS_RECORD to 3
            )
        )

        assertEquals(ZapretPreset.DISORDER, result.first)
        assertEquals(3, result.second)
    }

    @Test
    fun customArgumentsSupportQuotes() {
        val command = ZapretCommand.build(
            "/native/libciadpi.so",
            10810,
            ZapretPreset.CUSTOM,
            "--fake-data ':GET / HTTP/1.1' --ttl 8"
        )

        assertTrue(command.contains(":GET / HTTP/1.1"))
        assertTrue(command.windowed(2).contains(listOf("--ttl", "8")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun customArgumentsCannotOverrideListenPort() {
        ZapretCommand.build(
            "/native/libciadpi.so",
            10810,
            ZapretPreset.CUSTOM,
            "--port=9999 --disorder 1"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun customArgumentsCannotUseCompactListenPort() {
        ZapretArguments.parse("-p9999 --disorder 1")
    }

    @Test
    fun zapretBridgeSendsOnlyTcpThroughByeDpi() {
        val config = ZapretBridgeConfig.build(10811, 10810)

        assertTrue(config.contains("\"port\": 10811"))
        assertTrue(config.contains("\"address\": \"127.0.0.1\""))
        assertTrue(config.contains("\"port\": 10810"))
        assertTrue(config.contains("\"network\": \"tcp\""))
        assertTrue(config.contains("\"outboundTag\": \"byedpi\""))
        assertTrue(config.contains("\"network\": \"udp\""))
        assertTrue(config.contains("\"port\": \"443\""))
        assertTrue(config.contains("\"outboundTag\": \"block\""))
        assertTrue(config.contains("\"outboundTag\": \"direct\""))
    }
}
