package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRestartPolicyTest {
    @Test
    fun `backoff grows exponentially from the base delay`() {
        assertEquals(500L, ProcessRestartPolicy.backoffMs(1))
        assertEquals(1_000L, ProcessRestartPolicy.backoffMs(2))
        assertEquals(2_000L, ProcessRestartPolicy.backoffMs(3))
        assertEquals(4_000L, ProcessRestartPolicy.backoffMs(4))
    }

    @Test
    fun `backoff is capped`() {
        listOf(5, 9, 40, 1_000).forEach { attempt ->
            assertEquals(
                "attempt=$attempt",
                ProcessRestartPolicy.MAX_DELAY_MS,
                ProcessRestartPolicy.backoffMs(attempt)
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `attempts are 1-based`() {
        ProcessRestartPolicy.backoffMs(0)
    }
}

class SupervisedRolesTest {
    @Test
    fun `proxy only supervises xray and tun2socks`() {
        assertEquals(
            listOf(SupervisedRole.XRAY, SupervisedRole.TUN2SOCKS),
            supervisedRoles(VpnBackend.PROXY_ONLY, XrayRuntime.PLAIN_PROFILE)
        )
    }

    @Test
    fun `local bypass supervises byedpi bridge and tun2socks`() {
        assertEquals(
            listOf(SupervisedRole.BYEDPI, SupervisedRole.BRIDGE, SupervisedRole.TUN2SOCKS),
            supervisedRoles(VpnBackend.LOCAL_BYPASS, XrayRuntime.PLAIN_PROFILE)
        )
    }

    @Test
    fun `full auto picks up byedpi only after the youtube swap`() {
        assertEquals(
            listOf(SupervisedRole.XRAY, SupervisedRole.TUN2SOCKS),
            supervisedRoles(VpnBackend.FULL_AUTO, XrayRuntime.PLAIN_PROFILE)
        )
        assertEquals(
            listOf(
                SupervisedRole.XRAY,
                SupervisedRole.BYEDPI,
                SupervisedRole.BRIDGE,
                SupervisedRole.TUN2SOCKS
            ),
            supervisedRoles(VpnBackend.FULL_AUTO, XrayRuntime.FULL_AUTO_YOUTUBE)
        )
    }
}

class RestartCascadeTest {
    @Test
    fun `local bypass restarts the whole chain below byedpi`() {
        assertEquals(
            listOf(SupervisedRole.BYEDPI, SupervisedRole.BRIDGE, SupervisedRole.TUN2SOCKS),
            restartCascade(
                VpnBackend.LOCAL_BYPASS,
                XrayRuntime.PLAIN_PROFILE,
                SupervisedRole.BYEDPI
            )
        )
    }

    @Test
    fun `local bypass bridge drags only tun2socks`() {
        assertEquals(
            listOf(SupervisedRole.BRIDGE, SupervisedRole.TUN2SOCKS),
            restartCascade(
                VpnBackend.LOCAL_BYPASS,
                XrayRuntime.PLAIN_PROFILE,
                SupervisedRole.BRIDGE
            )
        )
    }

    @Test
    fun `tun2socks restarts alone everywhere`() {
        VpnBackend.entries.forEach { backend ->
            assertEquals(
                "backend=$backend",
                listOf(SupervisedRole.TUN2SOCKS),
                restartCascade(backend, XrayRuntime.FULL_AUTO_YOUTUBE, SupervisedRole.TUN2SOCKS)
            )
        }
    }

    @Test
    fun `full auto byedpi drags the bridge but not xray`() {
        val cascade = restartCascade(
            VpnBackend.FULL_AUTO,
            XrayRuntime.FULL_AUTO_YOUTUBE,
            SupervisedRole.BYEDPI
        )

        assertEquals(listOf(SupervisedRole.BYEDPI, SupervisedRole.BRIDGE), cascade)
    }

    @Test
    fun `xray drags tun2socks`() {
        assertEquals(
            listOf(SupervisedRole.XRAY, SupervisedRole.TUN2SOCKS),
            restartCascade(VpnBackend.PROXY_ONLY, XrayRuntime.PLAIN_PROFILE, SupervisedRole.XRAY)
        )
    }

    @Test
    fun `a role the backend does not run has no cascade`() {
        assertTrue(
            restartCascade(
                VpnBackend.PROXY_ONLY,
                XrayRuntime.PLAIN_PROFILE,
                SupervisedRole.BYEDPI
            ).isEmpty()
        )
    }

    @Test
    fun `cascade never names a role the backend does not run`() {
        VpnBackend.entries.forEach { backend ->
            XrayRuntime.entries.forEach { runtime ->
                val roles = supervisedRoles(backend, runtime)
                roles.forEach { failed ->
                    restartCascade(backend, runtime, failed).forEach { role ->
                        assertTrue("$backend/$runtime: $role", role in roles)
                    }
                }
            }
        }
    }

    @Test
    fun `cascade always starts with the failed role`() {
        VpnBackend.entries.forEach { backend ->
            XrayRuntime.entries.forEach { runtime ->
                supervisedRoles(backend, runtime).forEach { failed ->
                    assertEquals(
                        "$backend/$runtime",
                        failed,
                        restartCascade(backend, runtime, failed).first()
                    )
                }
            }
        }
    }
}

class ProcessSupervisorBudgetTest {
    @Test
    fun `spends the budget then gives up`() {
        val supervisor = ProcessSupervisor(maxAttempts = 3)

        assertEquals(500L, supervisor.nextBackoffMs(SupervisedRole.XRAY))
        assertEquals(1_000L, supervisor.nextBackoffMs(SupervisedRole.XRAY))
        assertEquals(2_000L, supervisor.nextBackoffMs(SupervisedRole.XRAY))
        assertNull(supervisor.nextBackoffMs(SupervisedRole.XRAY))
    }

    @Test
    fun `budgets are per role`() {
        val supervisor = ProcessSupervisor(maxAttempts = 1)

        assertNotNull(supervisor.nextBackoffMs(SupervisedRole.XRAY))
        assertNotNull(supervisor.nextBackoffMs(SupervisedRole.TUN2SOCKS))
        assertNull(supervisor.nextBackoffMs(SupervisedRole.XRAY))
    }

    @Test
    fun `a healthy role earns a fresh budget`() {
        val supervisor = ProcessSupervisor(maxAttempts = 2)

        supervisor.nextBackoffMs(SupervisedRole.BRIDGE)
        supervisor.nextBackoffMs(SupervisedRole.BRIDGE)
        assertNull(supervisor.nextBackoffMs(SupervisedRole.BRIDGE))

        supervisor.noteHealthy(SupervisedRole.BRIDGE)

        assertEquals(0, supervisor.attemptsFor(SupervisedRole.BRIDGE))
        assertEquals(500L, supervisor.nextBackoffMs(SupervisedRole.BRIDGE))
    }

    @Test
    fun `reset clears every role`() {
        val supervisor = ProcessSupervisor(maxAttempts = 1)
        supervisor.nextBackoffMs(SupervisedRole.XRAY)
        supervisor.nextBackoffMs(SupervisedRole.BYEDPI)

        supervisor.reset()

        assertEquals(0, supervisor.attemptsFor(SupervisedRole.XRAY))
        assertEquals(0, supervisor.attemptsFor(SupervisedRole.BYEDPI))
    }

    @Test
    fun `a crash looping role cannot restart forever`() {
        val supervisor = ProcessSupervisor()
        var granted = 0
        while (supervisor.nextBackoffMs(SupervisedRole.BYEDPI) != null) {
            granted++
            check(granted < 100) { "budget never ran out" }
        }

        assertEquals(ProcessRestartPolicy.MAX_ATTEMPTS, granted)
    }
}
