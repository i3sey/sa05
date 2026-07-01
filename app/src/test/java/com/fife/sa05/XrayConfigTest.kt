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

    private val beelineConfig = """
        {
          "inbounds": [{
            "tag": "socks",
            "listen": "127.0.0.1",
            "port": 20808,
            "protocol": "socks",
            "settings": {"udp": true, "auth": "noauth"}
          }],
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [{
                  "address": "48typmw3qq.a.trbcdn.net",
                  "port": 443,
                  "users": [{"id": "user-uuid", "encryption": "mlkem768x25519plus.native.0rtt.SECRET"}]
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
            {"tag": "direct", "protocol": "freedom"},
            {"tag": "block", "protocol": "blackhole"}
          ]
        }
    """.trimIndent()

    private fun JSONObject.xhttpOf(outboundIndex: Int) =
        getJSONArray("outbounds")
            .getJSONObject(outboundIndex)
            .getJSONObject("streamSettings")
            .getJSONObject("xhttpSettings")

    @Test
    fun validatePreservesXhttpAndPaddingAndTls() {
        val root = JSONObject(XrayConfig.validate(beelineConfig).runtimeJson)
        val stream = root.getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("streamSettings")

        assertEquals("xhttp", stream.getString("network"))
        assertEquals("tls", stream.getString("security"))
        val xhttp = stream.getJSONObject("xhttpSettings")
        assertEquals("packet-up", xhttp.getString("mode"))
        assertEquals("/assets/api/v1/", xhttp.getString("path"))
        assertEquals("48typmw3qq.a.trbcdn.net", xhttp.getString("host"))
        assertEquals("100-500", xhttp.getString("xPaddingBytes"))
        assertTrue(xhttp.getBoolean("xPaddingObfsMode"))
        assertEquals("header", xhttp.getString("xPaddingPlacement"))
        assertEquals("tokenish", xhttp.getString("xPaddingMethod"))
        val tls = stream.getJSONObject("tlsSettings")
        assertEquals("48typmw3qq.a.trbcdn.net", tls.getString("serverName"))
        val alpn = tls.getJSONArray("alpn")
        assertEquals(2, alpn.length())
        assertEquals("h2", alpn.getString(0))
        assertEquals("http/1.1", alpn.getString(1))
    }

    @Test
    fun fullAutoKeepsBeelineOutboundIntact() {
        val runtime = JSONObject(
            XrayConfig.buildFullAutoConfig(beelineConfig, 10811).runtimeJson
        )
        val xhttp = runtime.xhttpOf(0)

        assertEquals("packet-up", xhttp.getString("mode"))
        assertEquals("100-500", xhttp.getString("xPaddingBytes"))
        assertEquals("tokenish", xhttp.getString("xPaddingMethod"))
        assertEquals("xhttp", runtime.getJSONArray("outbounds")
            .getJSONObject(0).getJSONObject("streamSettings").getString("network"))
    }

    @Test
    fun pingConfigPreservesXhttpSettings() {
        val host = XrayConfig.extractHosts(beelineConfig).first()
        val root = JSONObject(XrayConfig.buildPingConfig(beelineConfig, host, 32130).runtimeJson)
        val xhttp = root.xhttpOf(0)

        assertEquals("packet-up", xhttp.getString("mode"))
        assertEquals("header", xhttp.getString("xPaddingPlacement"))
        assertEquals("100-500", xhttp.getString("xPaddingBytes"))
    }

    @Test
    fun validateLeavesNonXhttpOutboundShapeUnchanged() {
        // Regression: existing profiles keep their exact outbound structure.
        val root = JSONObject(XrayConfig.validate(config).runtimeJson)
        val vless = root.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("vless", vless.getString("protocol"))
        assertEquals(
            "reality",
            vless.getJSONObject("streamSettings").getString("security")
        )
        assertEquals(
            2,
            vless.getJSONObject("settings").getJSONArray("vnext").length()
        )
        assertFalse(vless.getJSONObject("streamSettings").has("xhttpSettings"))
    }

    @Test
    fun applyBeelinePaddingFillsMissingParams() {
        // Subscription profile without padding (Beeline answers 403 for this).
        val noPad = JSONObject(beelineConfig).also { root ->
            root.getJSONArray("outbounds")
                .getJSONObject(0)
                .getJSONObject("streamSettings")
                .getJSONObject("xhttpSettings")
                .apply {
                    remove("xPaddingBytes"); remove("xPaddingObfsMode")
                    remove("xPaddingPlacement"); remove("xPaddingMethod")
                }
        }.toString()

        val xhttp = JSONObject(XrayConfig.applyBeelinePadding(noPad))
            .getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("xhttpSettings")

        assertEquals("100-500", xhttp.getString("xPaddingBytes"))
        assertTrue(xhttp.getBoolean("xPaddingObfsMode"))
        assertEquals("header", xhttp.getString("xPaddingPlacement"))
        assertEquals("tokenish", xhttp.getString("xPaddingMethod"))
    }

    @Test
    fun applyBeelinePaddingKeepsProviderValues() {
        val custom = JSONObject(beelineConfig).also { root ->
            root.getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("streamSettings").getJSONObject("xhttpSettings")
                .put("xPaddingBytes", "50-200")
        }.toString()

        val xhttp = JSONObject(XrayConfig.applyBeelinePadding(custom))
            .getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("xhttpSettings")
        // Provider value preserved, remaining gaps filled.
        assertEquals("50-200", xhttp.getString("xPaddingBytes"))
        assertEquals("tokenish", xhttp.getString("xPaddingMethod"))
    }

    @Test
    fun applyBeelinePaddingIgnoresNonXhttpProfiles() {
        // The Reality/Hysteria fixture has no xhttp outbound -> untouched.
        assertEquals(config, XrayConfig.applyBeelinePadding(config))
    }

    @Test
    fun extractsEveryProxyEndpoint() {
        val hosts = XrayConfig.extractHosts(config)

        assertEquals(3, hosts.size)
        assertEquals("one.example", hosts[0].address)
        assertEquals(8443, hosts[1].port)
        assertEquals("hysteria", hosts[2].protocol)
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
    fun pingConfigPreservesProviderHysteriaShape() {
        val host = XrayConfig.extractHosts(config)[2]
        val root = JSONObject(XrayConfig.buildPingConfig(config, host, 32124).runtimeJson)
        val outbound = root.getJSONArray("outbounds").getJSONObject(1)

        assertEquals("hysteria", outbound.getString("protocol"))
        val settings = outbound.getJSONObject("settings")
        assertEquals("hy.example", settings.getString("address"))
        assertEquals(2, settings.getInt("version"))
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("hysteria", stream.getString("network"))
        assertEquals(
            "secret",
            stream.getJSONObject("hysteriaSettings").getString("auth")
        )
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

    @Test
    fun fullAutoPrependsYoutubeRoutesWithoutChangingSourceConfig() {
        val source = config
        val runtime = XrayConfig.buildFullAutoConfig(source, 10811)
        val root = JSONObject(runtime.runtimeJson)
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        val udpRule = rules.getJSONObject(0)
        val tcpRule = rules.getJSONObject(1)

        assertEquals("udp", udpRule.getString("network"))
        assertEquals("443", udpRule.getString("port"))
        assertFalse(udpRule.has("domain"))
        assertEquals("tcp", tcpRule.getString("network"))
        assertTrue(
            tcpRule.getJSONArray("domain").toString().contains("geosite:youtube")
        )
        assertTrue(
            tcpRule.getJSONArray("domain").toString()
                .contains("domain:youtubei.googleapis.com")
        )
        assertTrue(
            tcpRule.getJSONArray("domain").toString()
                .contains("domain:googlevideo.com")
        )
        val outboundTag = tcpRule.getString("outboundTag")
        val outbounds = root.getJSONArray("outbounds")
        val byeDpi = (0 until outbounds.length())
            .map { outbounds.getJSONObject(it) }
            .single { it.optString("tag") == outboundTag }
        assertEquals("socks", byeDpi.getString("protocol"))
        assertEquals(
            10811,
            byeDpi.getJSONObject("settings")
                .getJSONArray("servers")
                .getJSONObject(0)
                .getInt("port")
        )
        assertEquals(config, source)
    }

    @Test
    fun fullAutoKeepsProviderSocksPort() {
        val validated = XrayConfig.validate(config)
        val runtime = XrayConfig.buildFullAutoConfig(config, 10811)

        assertEquals(validated.socksPort, runtime.socksPort)
    }

    @Test
    fun fullAutoEnablesSniffingAndAvoidsProviderTagCollisions() {
        val root = JSONObject(config)
        root.getJSONArray("outbounds")
            .put(
                JSONObject()
                    .put("tag", "__sa05_youtube_byedpi")
                    .put("protocol", "freedom")
            )
        val runtime = JSONObject(
            XrayConfig.buildFullAutoConfig(root.toString(), 10811).runtimeJson
        )
        val inbound = runtime.getJSONArray("inbounds").getJSONObject(0)
        val sniffing = inbound.getJSONObject("sniffing")
        val overrides = sniffing.getJSONArray("destOverride").toString()
        val tcpTag = runtime.getJSONObject("routing")
            .getJSONArray("rules")
            .getJSONObject(1)
            .getString("outboundTag")

        assertTrue(sniffing.getBoolean("enabled"))
        assertTrue(sniffing.getBoolean("routeOnly"))
        assertTrue(overrides.contains("tls"))
        assertTrue(overrides.contains("quic"))
        assertEquals("__sa05_youtube_byedpi-2", tcpTag)
    }
}
