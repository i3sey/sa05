package com.fife.sa05

import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanProxyPasswordTest {
    @Test
    fun `is long enough to resist a guess on an open network`() {
        assertEquals(16, generateLanProxyPassword().length)
    }

    @Test
    fun `avoids characters that are misread when typed by hand`() {
        val forbidden = setOf('l', 'o', '0', '1')
        repeat(50) {
            assertTrue(generateLanProxyPassword().none { it in forbidden })
        }
    }

    @Test
    fun `differs between calls`() {
        assertNotEquals(generateLanProxyPassword(), generateLanProxyPassword())
    }

    @Test
    fun `is deterministic for a seeded source`() {
        fun seeded() = object : SecureRandom() {
            private var next = 0
            override fun nextInt(bound: Int): Int = (next++) % bound
        }

        assertEquals(generateLanProxyPassword(seeded()), generateLanProxyPassword(seeded()))
    }
}

class LanProxyShareUriTest {
    @Test
    fun `carries the credentials the client needs`() {
        val uri = lanProxyShareUri("192.168.1.5", 10809, "sa05", "abcd")

        assertEquals("socks5://sa05:abcd@192.168.1.5:10809", uri)
    }
}

class HardenInboundsTest {
    /** What providers actually ship: reachable from the whole Wi-Fi, with no authentication. */
    private val providerConfig = """
        {
          "inbounds": [
            {"tag": "socks", "listen": "0.0.0.0", "port": 10808, "protocol": "socks",
             "settings": {"udp": true, "auth": "noauth"}},
            {"tag": "http", "listen": "0.0.0.0", "port": 10809, "protocol": "http"}
          ],
          "outbounds": [{"tag": "direct", "protocol": "freedom"}]
        }
    """.trimIndent()

    private fun listens(json: String): List<String> {
        val inbounds = JSONObject(json).getJSONArray("inbounds")
        return (0 until inbounds.length()).map { inbounds.getJSONObject(it).optString("listen") }
    }

    @Test
    fun `pulls every exposed inbound back to loopback`() {
        val hardened = hardenInbounds(providerConfig)

        assertEquals(listOf("127.0.0.1", "127.0.0.1"), listens(hardened.json))
        assertEquals(listOf(10808, 10809), hardened.reboundPorts)
    }

    @Test
    fun `leaves loopback inbounds alone`() {
        val config = """
            {"inbounds": [
               {"tag": "a", "listen": "127.0.0.1", "port": 1, "protocol": "socks"},
               {"tag": "b", "listen": "localhost", "port": 2, "protocol": "socks"},
               {"tag": "c", "listen": "::1", "port": 3, "protocol": "socks"}
             ], "outbounds": []}
        """.trimIndent()

        val hardened = hardenInbounds(config)

        assertEquals(listOf("127.0.0.1", "localhost", "::1"), listens(hardened.json))
        assertTrue(hardened.reboundPorts.isEmpty())
    }

    @Test
    fun `keeps the sharing inbound on its LAN address`() {
        val shared = addLanProxyInbound(providerConfig, "192.168.1.5", 10890, "sa05", "secret")

        val hardened = hardenInbounds(shared)

        // Provider inbounds pulled in, sharing left exposed on purpose — it has a password.
        assertTrue(listens(hardened.json).contains("192.168.1.5"))
        assertEquals(2, listens(hardened.json).count { it == "127.0.0.1" })
    }

    @Test
    fun `ports and protocols are preserved`() {
        val hardened = hardenInbounds(providerConfig)
        val inbounds = JSONObject(hardened.json).getJSONArray("inbounds")

        assertEquals(10808, inbounds.getJSONObject(0).getInt("port"))
        assertEquals("socks", inbounds.getJSONObject(0).getString("protocol"))
        assertEquals(10809, inbounds.getJSONObject(1).getInt("port"))
    }

    @Test
    fun `an inbound without an explicit listen is left as it is`() {
        // Xray's own default is loopback, so rewriting would only add noise.
        val config = """{"inbounds": [{"tag": "a", "port": 1, "protocol": "socks"}], "outbounds": []}"""

        assertTrue(hardenInbounds(config).reboundPorts.isEmpty())
    }

    @Test
    fun `malformed json passes through untouched`() {
        assertEquals("not json", hardenInbounds("not json").json)
    }

    @Test
    fun `hardening is idempotent`() {
        val once = hardenInbounds(providerConfig).json

        assertEquals(once, hardenInbounds(once).json)
        assertTrue(hardenInbounds(once).reboundPorts.isEmpty())
    }
}

class LanProxyPortTest {
    @Test
    fun `avoids ports a profile already uses`() {
        val taken = inboundPorts(
            """{"inbounds":[{"port":10890},{"port":10891}],"outbounds":[]}"""
        )

        assertEquals(10892, pickLanProxyPort(taken))
    }

    @Test
    fun `uses the base port when nothing conflicts`() {
        assertEquals(LAN_PROXY_BASE_PORT, pickLanProxyPort(emptySet()))
    }

    @Test
    fun `does not land on the provider http port that caused the first collision`() {
        val taken = inboundPorts(
            """{"inbounds":[{"port":10808},{"port":10809}],"outbounds":[]}"""
        )

        val port = pickLanProxyPort(taken)

        assertTrue(port != 10808 && port != 10809)
    }

    @Test
    fun `reads ports out of a real config`() {
        assertEquals(
            setOf(10808, 10809),
            inboundPorts(
                """{"inbounds":[{"port":10808},{"port":10809}],"outbounds":[]}"""
            )
        )
    }

    @Test
    fun `garbage yields no ports rather than throwing`() {
        assertTrue(inboundPorts("not json").isEmpty())
    }
}

class AddLanProxyInboundTest {
    private val base = """
        {
          "inbounds": [
            {"tag": "socks", "listen": "127.0.0.1", "port": 10808, "protocol": "socks",
             "settings": {"udp": true, "auth": "noauth"}}
          ],
          "outbounds": [{"tag": "direct", "protocol": "freedom"}]
        }
    """.trimIndent()

    private fun inboundsOf(json: String) = JSONObject(json).getJSONArray("inbounds")

    private fun sharedInbound(json: String): JSONObject {
        val inbounds = inboundsOf(json)
        return (0 until inbounds.length())
            .map(inbounds::getJSONObject)
            .single { it.optString("tag").startsWith("__sa05_lan_share") }
    }

    @Test
    fun `adds an inbound without touching the existing one`() {
        val result = addLanProxyInbound(base, "192.168.1.5", 10809, "sa05", "secret")

        assertEquals(2, inboundsOf(result).length())
        assertEquals(10808, inboundsOf(result).getJSONObject(0).getInt("port"))
    }

    @Test
    fun `always requires a password`() {
        val inbound = sharedInbound(addLanProxyInbound(base, "192.168.1.5", 10809, "sa05", "s3cr3t"))
        val settings = inbound.getJSONObject("settings")

        assertEquals("password", settings.getString("auth"))
        val account = settings.getJSONArray("accounts").getJSONObject(0)
        assertEquals("sa05", account.getString("user"))
        assertEquals("s3cr3t", account.getString("pass"))
    }

    @Test
    fun `binds to the given address only`() {
        val inbound = sharedInbound(addLanProxyInbound(base, "192.168.1.5", 10809, "sa05", "s"))

        assertEquals("192.168.1.5", inbound.getString("listen"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses to listen on every interface`() {
        addLanProxyInbound(base, "0.0.0.0", 10809, "sa05", "s")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses an empty password`() {
        addLanProxyInbound(base, "192.168.1.5", 10809, "sa05", "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses a blank address`() {
        addLanProxyInbound(base, "", 10809, "sa05", "s")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses an out-of-range port`() {
        addLanProxyInbound(base, "192.168.1.5", 70000, "sa05", "s")
    }

    @Test
    fun `does not collide with an existing tag`() {
        val collidingBase = """
            {"inbounds": [{"tag": "__sa05_lan_share", "protocol": "socks", "port": 1}],
             "outbounds": []}
        """.trimIndent()

        val result = addLanProxyInbound(collidingBase, "192.168.1.5", 10809, "sa05", "s")
        val tags = inboundsOf(result).let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("tag") }
        }

        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun `creates the inbounds array when the profile has none`() {
        val result = addLanProxyInbound("""{"outbounds": []}""", "192.168.1.5", 10809, "u", "p")

        assertEquals(1, inboundsOf(result).length())
    }

    @Test
    fun `leaves the original json untouched`() {
        val before = base
        addLanProxyInbound(base, "192.168.1.5", 10809, "sa05", "s")

        assertEquals(before, base)
        assertFalse(base.contains("__sa05_lan_share"))
    }
}
