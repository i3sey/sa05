package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnModeTest {
    @Test
    fun compositeModesExposeRequiredComponents() {
        assertTrue(VpnBackend.FULL_AUTO.usesTelegram)
        assertTrue(VpnBackend.FULL_AUTO.usesXrayProfile)
        assertTrue(VpnBackend.LOCAL_BYPASS.usesTelegram)
        assertTrue(!VpnBackend.LOCAL_BYPASS.usesXrayProfile)
        assertTrue(!VpnBackend.PROXY_ONLY.usesTelegram)
        assertTrue(VpnBackend.PROXY_ONLY.usesXrayProfile)
    }

    @Test
    fun legacyModesMigrateToCompositeModes() {
        assertEquals(VpnBackend.PROXY_ONLY, VpnBackend.fromStoredName("XRAY"))
        assertEquals(VpnBackend.LOCAL_BYPASS, VpnBackend.fromStoredName("ZAPRET"))
        assertEquals(VpnBackend.LOCAL_BYPASS, VpnBackend.fromStoredName("TELEGRAM"))
        assertEquals(VpnBackend.FULL_AUTO, VpnBackend.fromStoredName("FULL_AUTO"))
        assertEquals(VpnBackend.PROXY_ONLY, VpnBackend.fromStoredName("unknown"))
    }

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
    fun currentYoutubeStrategiesAreIncludedInAutoSelection() {
        assertTrue(ZapretPreset.YOUTUBE_STABLE in ZapretPreset.testable)
        assertTrue(ZapretPreset.YOUTUBE_STABLE_AUTO in ZapretPreset.testable)
        assertTrue(ZapretPreset.AUTO_OOB_FAKE in ZapretPreset.testable)
        assertTrue(ZapretPreset.FAKE_TLS_ORIGINAL in ZapretPreset.testable)
        assertTrue(ZapretPreset.FAKE_TLS_RANDOM in ZapretPreset.testable)
        assertTrue(ZapretPreset.FAKE_TTL_ADAPTIVE in ZapretPreset.testable)
        assertTrue(ZapretPreset.TLS_MINOR in ZapretPreset.testable)
        assertEquals(ZapretPreset.YOUTUBE_STABLE, ZapretPreset.youtubeTestable.first())
        assertEquals(
            ZapretPreset.testable.toSet(),
            ZapretPreset.youtubeTestable.toSet()
        )
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
