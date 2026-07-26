package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunPlanTest {
    @Test
    fun `blackholes IPv6 by default`() {
        val plan = tunPlan(allowIpv6Bypass = false)

        assertEquals(
            listOf(
                TunAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX),
                TunAddress(TUN_IPV6_ADDRESS, TUN_IPV6_PREFIX)
            ),
            plan.addresses
        )
        assertEquals(
            listOf(TunRoute("0.0.0.0", 0), TunRoute("::", 0)),
            plan.routes
        )
    }

    @Test
    fun `hands IPv6 back to the system when bypass is enabled`() {
        val plan = tunPlan(allowIpv6Bypass = true)

        assertEquals(listOf(TunAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX)), plan.addresses)
        assertEquals(listOf(TunRoute("0.0.0.0", 0)), plan.routes)
    }

    @Test
    fun `always captures all IPv4 traffic`() {
        listOf(true, false).forEach { bypass ->
            val plan = tunPlan(allowIpv6Bypass = bypass)
            assertTrue(
                "IPv4 default route missing for allowIpv6Bypass=$bypass",
                plan.routes.contains(TunRoute("0.0.0.0", 0))
            )
            assertTrue(
                "IPv4 address missing for allowIpv6Bypass=$bypass",
                plan.addresses.contains(TunAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX))
            )
        }
    }

    @Test
    fun `an IPv6 address is never assigned without the matching route`() {
        // An address without ::/0 would leave IPv6 on the underlying network — the leak
        // this plan exists to close.
        listOf(true, false).forEach { bypass ->
            val plan = tunPlan(allowIpv6Bypass = bypass)
            val hasAddress = plan.addresses.any { it.address == TUN_IPV6_ADDRESS }
            val hasRoute = plan.routes.any { it.address == "::" }
            assertEquals("allowIpv6Bypass=$bypass", hasAddress, hasRoute)
        }
    }

    @Test
    fun `bypass drops every IPv6 entry`() {
        val plan = tunPlan(allowIpv6Bypass = true)

        assertFalse(plan.addresses.any { it.address.contains(':') })
        assertFalse(plan.routes.any { it.address.contains(':') })
    }
}
