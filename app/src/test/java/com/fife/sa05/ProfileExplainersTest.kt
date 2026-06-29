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

    @Test
    fun describesEveryUsedBalancerAndItsOutboundPool() {
        val info = parseProfileRoute(
            """
            {
              "outbounds":[
                {
                  "tag":"proxy-de",
                  "protocol":"vless",
                  "settings":{"vnext":[{"address":"de.example", "port":443}]},
                  "streamSettings":{"network":"xhttp", "security":"reality"}
                },
                {
                  "tag":"proxy-nl",
                  "protocol":"trojan",
                  "settings":{"servers":[{"address":"nl.example", "port":8443}]},
                  "streamSettings":{"network":"grpc", "security":"tls"}
                },
                {"tag":"direct", "protocol":"freedom", "settings":{}}
              ],
              "routing":{
                "rules":[
                  {"type":"field", "balancerTag":"main-balance"},
                  {"type":"field", "balancerTag":"backup-balance"},
                  {"type":"field", "balancerTag":"main-balance"}
                ],
                "balancers":[
                  {
                    "tag":"main-balance",
                    "selector":["proxy-"],
                    "fallbackTag":"direct",
                    "strategy":{"type":"leastPing"}
                  },
                  {
                    "tag":"backup-balance",
                    "selector":["proxy-nl"],
                    "strategy":{"type":"roundRobin"}
                  },
                  {
                    "tag":"unused-balance",
                    "selector":["proxy-de"],
                    "strategy":{"type":"random"}
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("main-balance", "backup-balance"), info.balancers.map { it.tag })
        assertEquals(listOf("proxy-de", "proxy-nl"), info.balancers[0].candidates.map { it.tag })
        assertEquals("leastPing", info.balancers[0].strategy)
        assertEquals("direct", info.balancers[0].fallbackTag)
        assertEquals(listOf("proxy-nl"), info.balancers[1].candidates.map { it.tag })
    }

    @Test
    fun keepsUsedBalancerVisibleWhenItsSelectorHasNoCandidates() {
        val info = parseProfileRoute(
            """
            {
              "outbounds":[
                {
                  "tag":"proxy-main",
                  "protocol":"vless",
                  "settings":{"vnext":[{"address":"main.example", "port":443}]}
                }
              ],
              "routing":{
                "rules":[{"type":"field", "balancerTag":"empty-balance"}],
                "balancers":[{"tag":"empty-balance", "selector":["missing-"]}]
              }
            }
            """.trimIndent()
        )

        assertEquals(1, info.balancers.size)
        assertEquals("random", info.balancers.single().strategy)
        assertEquals(emptyList<ProfileOutboundInfo>(), info.balancers.single().candidates)
    }

    @Test
    fun balancerPacketFinishesCurrentBranchBeforeSelectingTheNextOne() {
        val currentBranch = listOf(0.05f, 0.35f, 0.7f, 0.99f)
            .map { balancerPacketState(progress = it, branchCount = 3) }

        assertEquals(listOf(0, 0, 0, 0), currentBranch.map { it.branchIndex })
        assertEquals(0.99f, currentBranch.last().progress, 0.0001f)

        val branchEnd = balancerPacketState(progress = 1f, branchCount = 3)
        assertEquals(0, branchEnd.branchIndex)
        assertEquals(1f, branchEnd.progress, 0.0001f)

        val nextBranch = balancerPacketState(progress = 1.01f, branchCount = 3)
        assertEquals(1, nextBranch.branchIndex)
        assertEquals(0.01f, nextBranch.progress, 0.0001f)

        val offsetPacketBeforeEnd = balancerPacketState(
            progress = 0.49f,
            branchCount = 3,
            offset = 0.5f
        )
        val offsetPacketAfterEnd = balancerPacketState(
            progress = 0.51f,
            branchCount = 3,
            offset = 0.5f
        )
        assertEquals(0, offsetPacketBeforeEnd.branchIndex)
        assertEquals(0.99f, offsetPacketBeforeEnd.progress, 0.0001f)
        assertEquals(1, offsetPacketAfterEnd.branchIndex)
        assertEquals(0.01f, offsetPacketAfterEnd.progress, 0.0001f)
    }
}
