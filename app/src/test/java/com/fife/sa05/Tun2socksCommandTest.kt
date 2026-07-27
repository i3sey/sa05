package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Tun2socksCommandTest {
    private fun build(ipv6: Boolean) = Tun2socksCommand.build(
        binary = "/lib/libtun2socks.so",
        socksPort = 10808,
        socketPath = "/data/tun2socks.sock",
        ipv6 = ipv6
    )

    @Test
    fun `configures an IPv6 address when the TUN captures IPv6`() {
        val command = build(ipv6 = true)

        val index = command.indexOf("--netif-ip6addr")
        assertTrue("--netif-ip6addr missing: $command", index >= 0)
        assertEquals(TUN2SOCKS_IPV6_ADDRESS, command[index + 1])
    }

    @Test
    fun `leaves IPv6 alone when it bypasses the tunnel`() {
        assertFalse(build(ipv6 = false).contains("--netif-ip6addr"))
    }

    @Test
    fun `keeps the IPv4 transport arguments in both modes`() {
        listOf(true, false).forEach { ipv6 ->
            val command = build(ipv6)

            assertEquals("/lib/libtun2socks.so", command.first())
            assertEquals(TUN2SOCKS_IPV4_ADDRESS, command[command.indexOf("--netif-ipaddr") + 1])
            assertEquals(TUN2SOCKS_IPV4_NETMASK, command[command.indexOf("--netif-netmask") + 1])
            assertEquals("127.0.0.1:10808", command[command.indexOf("--socks-server-addr") + 1])
            assertEquals(TUN_MTU.toString(), command[command.indexOf("--tunmtu") + 1])
            assertEquals("/data/tun2socks.sock", command[command.indexOf("--sock-path") + 1])
            assertTrue(command.contains("--enable-udprelay"))
            assertEquals("notice", command[command.indexOf("--loglevel") + 1])
        }
    }

    @Test
    fun `rejects a port outside the valid range`() {
        listOf(0, 65536).forEach { port ->
            runCatching {
                Tun2socksCommand.build("/lib/libtun2socks.so", port, "/data/s.sock", ipv6 = true)
            }.onSuccess { error("port $port should have been rejected") }
        }
    }
}
