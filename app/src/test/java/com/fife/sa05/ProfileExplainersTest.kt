package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileExplainersTest {
    @Test
    fun describesVlessRealityXhttpProfile() {
        val info = parseProfileRoute(
            """
            {
              "outbounds":[{
                "protocol":"vless",
                "settings":{"vnext":[{"address":"de.example", "port":443}]},
                "streamSettings":{"network":"xhttp", "security":"reality"}
              }]
            }
            """.trimIndent()
        )

        assertEquals("VLESS", info.protocol)
        assertEquals("XHTTP", info.transport)
        assertEquals("REALITY", info.security)
        assertEquals("de.example:443", info.endpoint)
    }

    @Test
    fun describesHysteriaTlsProfile() {
        val info = parseProfileRoute(
            """
            {
              "outbounds":[{
                "protocol":"hysteria",
                "settings":{"address":"hy.example", "port":8443, "version":2},
                "streamSettings":{"network":"hysteria", "security":"tls"}
              }]
            }
            """.trimIndent()
        )

        assertEquals("HYSTERIA", info.protocol)
        assertEquals("HYSTERIA", info.transport)
        assertEquals("TLS", info.security)
        assertEquals("hy.example:8443", info.endpoint)
    }
}
