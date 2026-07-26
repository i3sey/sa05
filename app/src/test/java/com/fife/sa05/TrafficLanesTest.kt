package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дорожки должны совпадать с тем, что реально делает конфиг, иначе картинка обещает одно,
 * а маршрутизация делает другое.
 */
class TrafficLanesTest {
    @Test
    fun fullAutoSendsYoutubePastTheSubscriptionServerAndBlocksQuic() {
        val lanes = VpnBackend.FULL_AUTO.trafficLanes()

        val youtube = lanes.first { it.traffic.contains("YouTube") }
        assertEquals(TrafficLaneKind.BYPASS, youtube.kind)
        assertTrue(youtube.destination.contains("Напрямую"))

        val rest = lanes.first { it.traffic.contains("Остальной") }
        assertEquals(TrafficLaneKind.PROXY, rest.kind)

        val quic = lanes.first { it.traffic.contains("QUIC") }
        assertEquals(TrafficLaneKind.BLOCKED, quic.kind)
    }

    @Test
    fun localBypassNeverShowsTheSubscriptionServer() {
        val lanes = VpnBackend.LOCAL_BYPASS.trafficLanes()

        assertTrue(lanes.none { it.kind == TrafficLaneKind.PROXY })
        assertTrue(lanes.all { it.kind == TrafficLaneKind.BYPASS })
    }

    @Test
    fun proxyOnlyIsOneLaneThroughTheServer() {
        val lanes = VpnBackend.PROXY_ONLY.trafficLanes()

        assertEquals(1, lanes.size)
        assertEquals(TrafficLaneKind.PROXY, lanes.single().kind)
        assertEquals("Весь трафик", lanes.single().traffic)
    }

    @Test
    fun telegramLaneAppearsExactlyWhenTheLocalProxyRuns() {
        VpnBackend.entries.forEach { backend ->
            val hasTelegramLane = backend.trafficLanes().any { it.traffic == "Telegram" }
            assertEquals(backend.name, backend.usesTelegram, hasTelegramLane)
        }
    }
}
