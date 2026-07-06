package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnServicePolicyTest {
    @Test
    fun `service actions map to start stop or ignore`() {
        assertEquals(
            VpnServiceCommand.START,
            vpnServiceCommand("com.fife.sa05.START")
        )
        assertEquals(
            VpnServiceCommand.START,
            vpnServiceCommand("com.fife.sa05.RECONNECT")
        )
        assertEquals(
            VpnServiceCommand.STOP,
            vpnServiceCommand("com.fife.sa05.STOP")
        )
        assertEquals(VpnServiceCommand.IGNORE, vpnServiceCommand(null))
        assertEquals(VpnServiceCommand.IGNORE, vpnServiceCommand("unknown"))
    }

    @Test
    fun `proxy only requires tun tun2socks and provider xray`() {
        val healthy = VpnProcessHealth(
            tun = true,
            tun2socks = true,
            proxy = true,
            bridge = false,
            auxiliary = false,
            telegram = false
        )

        assertEquals(
            true,
            requiredProcessesRunning(
                backend = VpnBackend.PROXY_ONLY,
                xrayRuntime = XrayRuntime.PLAIN_PROFILE,
                health = healthy
            )
        )
        assertEquals(
            false,
            requiredProcessesRunning(
                backend = VpnBackend.PROXY_ONLY,
                xrayRuntime = XrayRuntime.PLAIN_PROFILE,
                health = healthy.copy(proxy = false)
            )
        )
    }

    @Test
    fun `every backend requires tun and tun2socks`() {
        val healthy = healthyProcesses()

        VpnBackend.entries.forEach { backend ->
            assertEquals(
                false,
                requiredProcessesRunning(
                    backend,
                    XrayRuntime.PLAIN_PROFILE,
                    healthy.copy(tun = false)
                )
            )
            assertEquals(
                false,
                requiredProcessesRunning(
                    backend,
                    XrayRuntime.PLAIN_PROFILE,
                    healthy.copy(tun2socks = false)
                )
            )
        }
    }

    @Test
    fun `local bypass requires byedpi bridge and telegram`() {
        val healthy = healthyProcesses()

        assertEquals(
            true,
            requiredProcessesRunning(
                VpnBackend.LOCAL_BYPASS,
                XrayRuntime.PLAIN_PROFILE,
                healthy
            )
        )
        assertEquals(
            false,
            requiredProcessesRunning(
                VpnBackend.LOCAL_BYPASS,
                XrayRuntime.PLAIN_PROFILE,
                healthy.copy(proxy = false)
            )
        )
        assertEquals(
            false,
            requiredProcessesRunning(
                VpnBackend.LOCAL_BYPASS,
                XrayRuntime.PLAIN_PROFILE,
                healthy.copy(bridge = false)
            )
        )
        assertEquals(
            false,
            requiredProcessesRunning(
                VpnBackend.LOCAL_BYPASS,
                XrayRuntime.PLAIN_PROFILE,
                healthy.copy(telegram = false)
            )
        )
    }

    @Test
    fun `full auto fallback and optimized routes require different processes`() {
        val providerFallback = healthyProcesses().copy(
            bridge = false,
            auxiliary = false
        )

        assertEquals(
            true,
            requiredProcessesRunning(
                VpnBackend.FULL_AUTO,
                XrayRuntime.PLAIN_PROFILE,
                providerFallback
            )
        )
        assertEquals(
            false,
            requiredProcessesRunning(
                VpnBackend.FULL_AUTO,
                XrayRuntime.PLAIN_PROFILE,
                providerFallback.copy(telegram = false)
            )
        )
        assertEquals(
            false,
            requiredProcessesRunning(
                VpnBackend.FULL_AUTO,
                XrayRuntime.FULL_AUTO_YOUTUBE,
                providerFallback
            )
        )
        assertEquals(
            true,
            requiredProcessesRunning(
                VpnBackend.FULL_AUTO,
                XrayRuntime.FULL_AUTO_YOUTUBE,
                providerFallback.copy(bridge = true, auxiliary = true)
            )
        )
    }

    private fun healthyProcesses() = VpnProcessHealth(
        tun = true,
        tun2socks = true,
        proxy = true,
        bridge = true,
        auxiliary = true,
        telegram = true
    )
}
