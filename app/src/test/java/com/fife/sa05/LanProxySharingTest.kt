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
