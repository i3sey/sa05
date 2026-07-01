package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeelineDiagnosticsTest {
    private val secretKey = "mlkem768x25519plus.native.0rtt.SECRETKEYVALUE"
    private val userUuid = "33ad02b5-d5dd-4b2b-ba76-5a506ddd32cc"

    private val beelineProfile = """
        {
          "inbounds": [{"protocol": "socks", "port": 10808, "settings": {"udp": true}}],
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [{
                  "address": "48typmw3qq.a.trbcdn.net",
                  "port": 443,
                  "users": [{"id": "$userUuid", "encryption": "$secretKey"}]
                }]
              },
              "streamSettings": {
                "network": "xhttp",
                "security": "tls",
                "xhttpSettings": {
                  "mode": "packet-up",
                  "host": "48typmw3qq.a.trbcdn.net",
                  "path": "/assets/api/v1/",
                  "xPaddingBytes": "100-500",
                  "xPaddingObfsMode": true,
                  "xPaddingPlacement": "header",
                  "xPaddingMethod": "tokenish"
                },
                "tlsSettings": {
                  "serverName": "48typmw3qq.a.trbcdn.net",
                  "alpn": ["h2", "http/1.1"]
                }
              }
            },
            {"tag": "direct", "protocol": "freedom"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesAllPaddingAndTransportFields() {
        val info = beelineProfileInfo(beelineProfile)!!

        assertEquals("48typmw3qq.a.trbcdn.net", info.cdnHost)
        assertEquals("/assets/api/v1/", info.path)
        assertEquals("packet-up", info.mode)
        assertEquals("100-500", info.paddingBytes)
        assertEquals("header", info.paddingPlacement)
        assertEquals("tokenish", info.paddingMethod)
        assertTrue(info.paddingObfs)
        assertEquals(listOf("h2", "http/1.1"), info.alpn)
        assertEquals("base64url/12", info.sessionIdFormat)
        assertEquals("header/tokenish/100-500", info.paddingSummary)
    }

    @Test
    fun addressHostAndServerNameAllEqualCdnDomain() {
        // §10.2: the client must never substitute origin.sa05.tech.
        val info = beelineProfileInfo(beelineProfile)!!

        assertEquals("48typmw3qq.a.trbcdn.net", info.cdnHost)
        assertEquals("48typmw3qq.a.trbcdn.net", info.tlsServerName)
    }

    @Test
    fun encryptionIsReportedEnabledAndNotDowngraded() {
        // §10.3: VLESS Encryption must not be replaced with "none".
        val info = beelineProfileInfo(beelineProfile)!!
        assertTrue(info.encryptionEnabled)
    }

    @Test
    fun doesNotLeakUuidOrEncryptionKey() {
        // §12: the panel object must never carry secrets.
        val rendered = beelineProfileInfo(beelineProfile)!!.toString()
        assertFalse(rendered.contains(secretKey))
        assertFalse(rendered.contains(userUuid))
    }

    @Test
    fun encryptionNoneCountsAsDisabled() {
        val plain = beelineProfile.replace(secretKey, "none")
        assertFalse(beelineProfileInfo(plain)!!.encryptionEnabled)
    }

    @Test
    fun returnsNullForNonXhttpProfile() {
        val reality = """
            {
              "outbounds": [{
                "tag": "proxy",
                "protocol": "vless",
                "settings": {"vnext": [{"address": "a.example", "port": 443, "users": [{"id": "u"}]}]},
                "streamSettings": {"network": "tcp", "security": "reality"}
              }]
            }
        """.trimIndent()
        assertNull(beelineProfileInfo(reality))
    }

    @Test
    fun returnsNullForMalformedJson() {
        assertNull(beelineProfileInfo("not json"))
        assertNull(beelineProfileInfo(""))
    }
}
