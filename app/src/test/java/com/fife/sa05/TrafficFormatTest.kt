package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficFormatTest {
    @Test
    fun `uptime uses seconds under a minute`() {
        assertEquals("0 с", formatUptime(0))
        assertEquals("47 с", formatUptime(47_000))
        assertEquals("59 с", formatUptime(59_999))
    }

    @Test
    fun `uptime uses minutes and seconds under an hour`() {
        assertEquals("1 мин 00 с", formatUptime(60_000))
        assertEquals("12 мин 03 с", formatUptime(723_000))
        assertEquals("59 мин 59 с", formatUptime(3_599_000))
    }

    @Test
    fun `uptime uses hours and minutes above an hour`() {
        assertEquals("1 ч 00 мин", formatUptime(3_600_000))
        assertEquals("1 ч 05 мин", formatUptime(3_900_000))
        assertEquals("26 ч 01 мин", formatUptime(93_660_000))
    }

    @Test
    fun `negative uptime is clamped`() {
        assertEquals("0 с", formatUptime(-5_000))
    }

    @Test
    fun `bytes stay raw below a kilobyte`() {
        assertEquals("0 Б", formatBytes(0))
        assertEquals("512 Б", formatBytes(512))
        assertEquals("1023 Б", formatBytes(1023))
    }

    @Test
    fun `bytes scale through binary units`() {
        assertEquals("1,0 КБ", formatBytes(1024))
        assertEquals("1,5 КБ", formatBytes(1536))
        assertEquals("1,0 МБ", formatBytes(1024L * 1024))
        assertEquals("1,0 ГБ", formatBytes(1024L * 1024 * 1024))
        assertEquals("1,0 ТБ", formatBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `large values drop the decimal`() {
        assertEquals("100 КБ", formatBytes(1024L * 100))
        assertEquals("512 МБ", formatBytes(1024L * 1024 * 512))
    }

    @Test
    fun `terabytes do not roll over into a missing unit`() {
        val huge = 1024L * 1024 * 1024 * 1024 * 5000
        assertEquals("5000 ТБ", formatBytes(huge))
    }

    @Test
    fun `negative bytes are clamped`() {
        assertEquals("0 Б", formatBytes(-1))
    }
}

class ComponentTroubleTest {
    private fun snapshot(component: VpnRuntimeComponent, state: VpnComponentState) =
        VpnComponentSnapshot(component, state)

    @Test
    fun `a healthy stack says nothing`() {
        val components = listOf(
            snapshot(VpnRuntimeComponent.XRAY, VpnComponentState.RUNNING),
            snapshot(VpnRuntimeComponent.TUN, VpnComponentState.RUNNING),
            snapshot(VpnRuntimeComponent.TUN2SOCKS, VpnComponentState.RUNNING)
        )

        assertEquals(null, componentTrouble(components))
    }

    @Test
    fun `a starting stack says nothing`() {
        val components = listOf(
            snapshot(VpnRuntimeComponent.XRAY, VpnComponentState.STARTING),
            snapshot(VpnRuntimeComponent.TUN, VpnComponentState.STARTING)
        )

        assertEquals(null, componentTrouble(components))
    }

    @Test
    fun `an empty stack says nothing`() {
        assertEquals(null, componentTrouble(emptyList()))
    }

    @Test
    fun `byedpi fallback is reported before any failure`() {
        val components = listOf(
            snapshot(VpnRuntimeComponent.XRAY, VpnComponentState.RUNNING),
            snapshot(VpnRuntimeComponent.BYEDPI, VpnComponentState.FALLBACK)
        )

        assertEquals(
            "Локальный обход не взлетел: YouTube идёт через выбранный сервер",
            componentTrouble(components)
        )
    }

    @Test
    fun `a dead tunnel outranks a dead backend`() {
        val components = listOf(
            snapshot(VpnRuntimeComponent.XRAY, VpnComponentState.FAILED),
            snapshot(VpnRuntimeComponent.TUN, VpnComponentState.FAILED)
        )

        assertEquals("Туннель закрылся, перезапускаем VPN", componentTrouble(components))
    }

    @Test
    fun `each failing component gets its own wording`() {
        assertEquals(
            "Соединение с сервером оборвалось, восстанавливаем",
            componentTrouble(listOf(snapshot(VpnRuntimeComponent.XRAY, VpnComponentState.FAILED)))
        )
        assertEquals(
            "Перенос трафика в туннель остановился, восстанавливаем",
            componentTrouble(
                listOf(snapshot(VpnRuntimeComponent.TUN2SOCKS, VpnComponentState.FAILED))
            )
        )
        assertEquals(
            "Локальный обход остановился, восстанавливаем",
            componentTrouble(listOf(snapshot(VpnRuntimeComponent.BYEDPI, VpnComponentState.FAILED)))
        )
        assertEquals(
            "Telegram Proxy остановился",
            componentTrouble(
                listOf(snapshot(VpnRuntimeComponent.TELEGRAM, VpnComponentState.FAILED))
            )
        )
    }
}
