package com.fife.sa05

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YctunTest {
    private val baseUrl = "https://functions.yandexcloud.net/d4e4etbt3unqg8k9ac4n"

    private val profile = """
        {
          "remarks": "tunnel profile",
          "sa05_yctun": {
            "base_url": "$baseUrl",
            "psk": "0000000000000000000000000000000000000000000000000000000000000000",
            "server_pub": "b2f5d19261a2305fb6a39f1ed1133bb2cd46ce72efa11089d67c44324b69a101"
          },
          "inbounds": [{
            "tag": "socks",
            "listen": "127.0.0.1",
            "port": 10808,
            "protocol": "socks",
            "settings": {"udp": true}
          }],
          "outbounds": [{"tag": "proxy", "protocol": "vless"}]
        }
    """.trimIndent()

    @Test
    fun parseReadsBlockAndAppliesTuningDefaults() {
        val params = YctunParams.parse(profile)

        assertNotNull(params)
        assertEquals(baseUrl, params!!.baseUrl)
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000", params.psk)
        assertEquals("b2f5d19261a2305fb6a39f1ed1133bb2cd46ce72efa11089d67c44324b69a101", params.serverPub)
        assertFalse(params.stream)
        assertEquals(0, params.streams)
        assertEquals(1, params.workers)
        assertEquals(8192, params.chunk)
        assertEquals(5000, params.pollMs)
        assertTrue(params.postUplink)
    }

    @Test
    fun parseHonorsExplicitTuning() {
        val withTuning = JSONObject(profile)
            .put(
                "sa05_yctun",
                JSONObject()
                    .put("base_url", baseUrl)
                    .put("psk", "0000000000000000000000000000000000000000000000000000000000000000")
                    .put("server_pub", "b2f5d19261a2305fb6a39f1ed1133bb2cd46ce72efa11089d67c44324b69a101")
                    .put("stream", true)
                    .put("streams", 8)
                    .put("workers", 3)
                    .put("chunk", 9000)
                    .put("poll_ms", 250)
                    .put("post_uplink", false)
            )
            .toString()

        val params = YctunParams.parse(withTuning)!!

        assertTrue(params.stream)
        assertEquals(8, params.streams)
        assertEquals(3, params.workers)
        assertEquals(9000, params.chunk)
        assertEquals(250, params.pollMs)
        assertFalse(params.postUplink)
    }

    @Test
    fun parseReturnsNullWhenBlockMissing() {
        assertNull(YctunParams.parse(XrayPreferences.defaultConfig))
    }

    @Test
    fun parseSubscriptionReadsHeaderJsonWhenProfileHasNoBlock() {
        val subscription = SubscriptionState(
            profiles = listOf(SubscriptionProfile("id", "p", profile)),
            yctunJson = """
                {
                  "base_url": "$baseUrl",
                  "psk": "0000000000000000000000000000000000000000000000000000000000000000",
                  "server_pub": "b2f5d19261a2305fb6a39f1ed1133bb2cd46ce72efa11089d67c44324b69a101",
                  "streams": 8
                }
            """.trimIndent()
        )
        val profileWithoutBlock = XrayPreferences.defaultConfig

        assertNull(YctunParams.parseSubscription(SubscriptionState()))
        val params = YctunParams.parseSubscription(subscription)
        assertNotNull(params)
        assertEquals(baseUrl, params!!.baseUrl)
        assertEquals(8, params.streams)
        assertNull(YctunParams.parse(profileWithoutBlock))
        val resolved = YctunParams.resolve(profileWithoutBlock, subscription)
        assertEquals(baseUrl, resolved!!.baseUrl)
    }

    @Test
    fun resolvePrefersProfileBlockOverSubscriptionHeader() {
        val subscription = SubscriptionState(
            yctunJson = """
                {
                  "base_url": "https://sub.example.com",
                  "psk": "0000000000000000000000000000000000000000000000000000000000000000",
                  "server_pub": "b2f5d19261a2305fb6a39f1ed1133bb2cd46ce72efa11089d67c44324b69a101"
                }
            """.trimIndent()
        )

        val resolved = YctunParams.resolve(profile, subscription)

        assertEquals(baseUrl, resolved!!.baseUrl)
    }

    @Test
    fun resolveReturnsNullWithoutAnySource() {
        assertNull(
            YctunParams.resolve(XrayPreferences.defaultConfig, SubscriptionState())
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseSubscriptionRejectsMalformedJson() {
        YctunParams.parseSubscription(
            SubscriptionState(yctunJson = "{\"base_url\": broken")
        )
    }

    @Test
    fun subscriptionYctunJsonSurvivesEncodeDecodeRoundtrip() {
        val state = SubscriptionState(
            url = "https://sub.example.com/x",
            yctunJson = """
                {"base_url":"$baseUrl","psk":"0000000000000000000000000000000000000000000000000000000000000000","server_pub":"b2f5d19261a2305fb6a39f1ed1133bb2cd46ce72efa11089d67c44324b69a101"}
            """.trimIndent()
        )

        val decoded = XrayPreferences.decodeSubscription(
            XrayPreferences.encodeSubscription(state)
        )

        assertEquals(state.yctunJson, decoded.yctunJson)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseRejectsInvalidPsk() {
        YctunParams.parse(
            """{"sa05_yctun":{"base_url":"$baseUrl","psk":"zz","server_pub":"b2f5d19261a2305fb6a39f1ed1133bb2cd46ce72efa11089d67c44324b69a101"}}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseRejectsInvalidServerPub() {
        YctunParams.parse(
            """{"sa05_yctun":{"base_url":"$baseUrl","psk":"0000000000000000000000000000000000000000000000000000000000000000","server_pub":"short"}}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseRejectsNonHttpsBaseUrl() {
        YctunParams.parse(
            """{"sa05_yctun":{"base_url":"http://functions.yandexcloud.net/id","psk":"0000000000000000000000000000000000000000000000000000000000000000","server_pub":"b2f5d19261a2305fb6a39f1ed1133bb2cd46ce72efa11089d67c44324b69a101"}}"""
        )
    }

    @Test
    fun relaycConfigContainsAllClientSettings() {
        val config = YctunParams.parse(profile)!!.relaycConfig("127.0.0.1:10812")
        val root = JSONObject(config)

        assertEquals(baseUrl, root.getString("base_url"))
        assertEquals("127.0.0.1:10812", root.getString("listen"))
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000", root.getString("psk"))
        assertEquals("b2f5d19261a2305fb6a39f1ed1133bb2cd46ce72efa11089d67c44324b69a101", root.getString("server_pub"))
        assertFalse(root.getBoolean("stream"))
        assertEquals(0, root.getInt("streams"))
        assertEquals(1, root.getInt("workers"))
        assertEquals(8192, root.getInt("chunk"))
        assertEquals(5000, root.getInt("poll_ms"))
        assertTrue(root.getBoolean("post_uplink"))
    }

    @Test
    fun buildYctunConfigStripsBlockAndRoutesThroughRelayc() {
        val validated = XrayConfig.buildYctunConfig(profile, 10812)
        val root = JSONObject(validated.runtimeJson)

        assertFalse(root.has(YctunParams.BLOCK_KEY))
        assertEquals(10808, validated.socksPort)
        val outbounds = root.getJSONArray("outbounds")
        val tunnel = outbounds.getJSONObject(0)
        assertEquals("socks", tunnel.getString("protocol"))
        val server = tunnel.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("127.0.0.1", server.getString("address"))
        assertEquals(10812, server.getInt("port"))
        val protocols = (0 until outbounds.length()).map {
            outbounds.getJSONObject(it).getString("protocol")
        }
        assertTrue("dns" in protocols)
        assertTrue("freedom" in protocols)
        assertTrue("blackhole" in protocols)
        assertTrue((0 until outbounds.length()).any {
            outbounds.getJSONObject(it).optString("tag") == "proxy"
        })
        val dnsServers = root.getJSONObject("dns").getJSONArray("servers")
        assertEquals("https://8.8.8.8/dns-query", dnsServers.getJSONObject(0).getString("address"))
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        assertEquals(3, rules.length())
        assertEquals("geoip:private", rules.getJSONObject(0).getJSONArray("ip").getString(0))
        assertEquals("53", rules.getJSONObject(1).getString("port"))
        assertEquals("udp", rules.getJSONObject(2).getString("network"))
        assertTrue(outbounds.getJSONObject(0).getString("tag").startsWith("__sa05_yctun"))
    }

    @Test
    fun buildYctunConfigPreservesSocksInboundContract() {
        val validated = XrayConfig.buildYctunConfig(profile, 10812)
        val root = JSONObject(validated.runtimeJson)
        val inbound = root.getJSONArray("inbounds").getJSONObject(0)
        assertTrue(inbound.getJSONObject("settings").getBoolean("udp"))
    }

    @Test
    fun withBsProfileInjectsStableServerFromHeader() {
        val provider = SubscriptionProfile("p1", "DE", XrayPreferences.defaultConfig)
        val state = SubscriptionState(
            profiles = listOf(provider),
            activeProfileId = "p1",
            yctunJson = """
                {
                  "base_url": "$baseUrl",
                  "psk": "0000000000000000000000000000000000000000000000000000000000000000",
                  "server_pub": "b2f5d19261a2305fb6a39f1ed1133bb2cd46ce72efa11089d67c44324b69a101"
                }
            """.trimIndent()
        )

        val injected = state.withBsProfile()
        assertEquals(2, injected.profiles.size)
        assertTrue(BsProfile.isBs(injected.profiles.last()))
        assertEquals(BsProfile.ID, injected.profiles.last().id)
        assertEquals(BsProfile.REMARKS, injected.profiles.last().remarks)
        assertEquals("p1", injected.activeProfileId)

        val again = injected.withBsProfile()
        assertEquals(2, again.profiles.size)
        assertEquals(BsProfile.ID, again.profiles.last().id)
    }

    @Test
    fun withBsProfileInjectsFromProviderBlockWithoutHeader() {
        val provider = SubscriptionProfile("p1", "DE", profile)
        val injected = SubscriptionState(
            profiles = listOf(provider),
            activeProfileId = "p1"
        ).withBsProfile()

        assertEquals(2, injected.profiles.size)
        assertTrue(BsProfile.isBs(injected.profiles.last()))
        assertNotNull(YctunParams.parse(injected.profiles.last().json))
    }

    @Test
    fun withBsProfileRemovesPseudoServerWithoutCredentials() {
        val provider = SubscriptionProfile("p1", "DE", XrayPreferences.defaultConfig)
        val bs = BsProfile.build(
            YctunParams(
                baseUrl = baseUrl,
                psk = "0000000000000000000000000000000000000000000000000000000000000000",
                serverPub = "b2f5d19261a2305fb6a39f1ed1133bb2cd46ce72efa11089d67c44324b69a101"
            )
        )
        val stripped = SubscriptionState(
            profiles = listOf(provider, bs),
            activeProfileId = BsProfile.ID
        ).withBsProfile()

        assertEquals(1, stripped.profiles.size)
        assertEquals("p1", stripped.activeProfileId)
        assertFalse(stripped.profiles.any { BsProfile.isBs(it) })
    }

    @Test
    fun withBsProfileMigratesLegacyCdnId() {
        val provider = SubscriptionProfile("p1", "DE", XrayPreferences.defaultConfig)
        val state = SubscriptionState(
            profiles = listOf(provider),
            activeProfileId = BsProfile.LEGACY_ID,
            yctunJson = """
                {
                  "base_url": "$baseUrl",
                  "psk": "0000000000000000000000000000000000000000000000000000000000000000",
                  "server_pub": "b2f5d19261a2305fb6a39f1ed1133bb2cd46ce72efa11089d67c44324b69a101"
                }
            """.trimIndent()
        )

        val migrated = state.withBsProfile()

        assertEquals(BsProfile.ID, migrated.activeProfileId)
    }
}
