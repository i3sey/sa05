package com.fife.sa05

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigTest {
    private val config = """
        {
          "inbounds": [{
            "tag": "socks",
            "listen": "127.0.0.1",
            "port": 10808,
            "protocol": "socks",
            "settings": {"udp": true}
          }],
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {"address": "one.example", "port": 443, "users": [{"id": "uuid"}]},
                  {"address": "two.example", "port": 8443, "users": [{"id": "uuid"}]}
                ]
              },
              "streamSettings": {"security": "reality"}
            },
            {
              "tag": "hy2",
              "protocol": "hysteria",
              "settings": {"address": "hy.example", "port": 443, "version": 2},
              "streamSettings": {
                "network": "hysteria",
                "hysteriaSettings": {"version": 2, "auth": "secret"},
                "security": "tls"
              }
            },
            {"tag": "direct", "protocol": "freedom"}
          ],
          "burstObservatory": {
            "pingConfig": {
              "timeout": "3s",
              "destination": "https://example.com/generate_204"
            }
          }
        }
    """.trimIndent()

    @Test
    fun extractsEveryProxyEndpoint() {
        val hosts = XrayConfig.extractHosts(config)

        assertEquals(3, hosts.size)
        assertEquals("one.example", hosts[0].address)
        assertEquals(8443, hosts[1].port)
        assertEquals("hysteria2", hosts[2].protocol)
        assertEquals("hy.example", hosts[2].address)
    }

    @Test
    fun pingConfigKeepsOnlySelectedEndpointAndForcesRouting() {
        val host = XrayConfig.extractHosts(config)[1]
        val ping = XrayConfig.buildPingConfig(config, host, 32123)
        val root = JSONObject(ping.runtimeJson)

        val inbound = root.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(32123, inbound.getInt("port"))
        val vnext = root.getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("settings")
            .getJSONArray("vnext")
        assertEquals(1, vnext.length())
        assertEquals("two.example", vnext.getJSONObject(0).getString("address"))
        val rule = root.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("proxy", rule.getString("outboundTag"))
        assertEquals("https://example.com/generate_204", ping.probeUrl)
        assertEquals(3_000, ping.timeoutMs)
        assertFalse(root.has("burstObservatory"))
    }

    @Test
    fun pingConfigNormalizesLegacyHysteria2() {
        val host = XrayConfig.extractHosts(config)[2]
        val root = JSONObject(XrayConfig.buildPingConfig(config, host, 32124).runtimeJson)
        val outbound = root.getJSONArray("outbounds").getJSONObject(1)

        assertEquals("hysteria2", outbound.getString("protocol"))
        val server = outbound.getJSONObject("settings")
            .getJSONArray("servers")
            .getJSONObject(0)
        assertEquals("hy.example", server.getString("address"))
        assertEquals("secret", server.getString("password"))
        val stream = outbound.getJSONObject("streamSettings")
        assertFalse(stream.has("network"))
        assertFalse(stream.has("hysteriaSettings"))
        assertEquals("tls", stream.getString("security"))
    }

    @Test
    fun defaultProbeSettingsAreApplied() {
        val withoutObservatory = JSONObject(config)
            .apply { remove("burstObservatory") }
            .toString()
        val host = XrayConfig.extractHosts(withoutObservatory).first()
        val ping = XrayConfig.buildPingConfig(withoutObservatory, host, 32125)

        assertEquals("https://www.gstatic.com/generate_204", ping.probeUrl)
        assertEquals(8_000, ping.timeoutMs)
        assertTrue(ping.runtimeJson.contains("__ping_in"))
    }
}
