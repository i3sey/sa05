package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRedactionTest {
    private fun redacted(text: String, secrets: Collection<String> = emptyList()) =
        ReportRedaction.redact(text, secrets)

    @Test
    fun `strips subscription URLs`() {
        val out = redacted("fetching https://provider.example/sub/abc123token/json now")

        assertFalse(out.contains("provider.example"))
        assertFalse(out.contains("abc123token"))
        assertTrue(out.contains("<url removed>"))
    }

    @Test
    fun `strips profile UUIDs`() {
        val out = redacted("user id 3f2504e0-4f89-11d3-9a0c-0305e82c3301 connected")

        assertFalse(out.contains("3f2504e0"))
        assertTrue(out.contains("<uuid removed>"))
    }

    @Test
    fun `strips the telegram proxy secret`() {
        val secret = "0123456789abcdef0123456789abcdef"
        val out = redacted("tg secret dd$secret applied")

        assertFalse(out.contains(secret))
    }

    @Test
    fun `strips server addresses but keeps local plumbing`() {
        val out = redacted("dial 203.0.113.77:443 via 127.0.0.1:10808 tun 10.10.10.1")

        assertFalse(out.contains("203.0.113.77"))
        assertTrue(out.contains("127.0.0.1"))
        assertTrue(out.contains("10.10.10.1"))
    }

    @Test
    fun `strips IPv6 server addresses`() {
        val out = redacted("dial [2001:db8::dead:beef]:443 failed")

        assertFalse(out.contains("2001:db8"))
    }

    @Test
    fun `keeps clock times readable`() {
        // A log without timestamps is much harder to reason about, and a naive IPv6 pattern
        // treats 18:07:35 as an address.
        val out = redacted("2026/07/26 18:07:35.727304 [Info] core: started")

        assertTrue(out.contains("18:07:35.727304"))
    }

    @Test
    fun `keeps host-and-port pairs readable`() {
        val out = redacted("listening on 127.0.0.1:10808")

        assertTrue(out.contains("127.0.0.1:10808"))
    }

    @Test
    fun `still strips full-length IPv6`() {
        val out = redacted("peer 2001:0db8:0000:0000:0000:0000:0000:0001 up")

        assertFalse(out.contains("2001:0db8"))
    }

    @Test
    fun `still strips compressed IPv6 in several shapes`() {
        listOf(
            "fe80::1",
            "2001:db8::dead:beef",
            "::1",
            "2001:db8:1234::5678"
        ).forEach { address ->
            val out = redacted("dial $address now")
            assertFalse("leaked $address", out.contains(address))
        }
    }

    @Test
    fun `strips e-mail addresses`() {
        val out = redacted("account owner@example.com over quota")

        assertFalse(out.contains("owner@example.com"))
    }

    @Test
    fun `strips caller supplied literals`() {
        val out = redacted(
            "remark: Tokyo-Premium-Gold connected",
            secrets = listOf("Tokyo-Premium-Gold")
        )

        assertFalse(out.contains("Tokyo-Premium-Gold"))
        assertTrue(out.contains("<removed>"))
    }

    @Test
    fun `ignores caller literals too short to be secrets`() {
        // A one- or two-character "secret" would blank out ordinary prose.
        val out = redacted("status ok", secrets = listOf("o", "ok"))

        assertEquals("status ok", out)
    }

    @Test
    fun `removes longer literals before shorter overlapping ones`() {
        val out = redacted(
            "token abcdefgh and abcd",
            secrets = listOf("abcd", "abcdefgh")
        )

        assertFalse(out.contains("abcdefgh"))
    }

    @Test
    fun `leaves ordinary diagnostics readable`() {
        val out = redacted("tun2socks: connection closed by peer, retrying in 500ms")

        assertEquals("tun2socks: connection closed by peer, retrying in 500ms", out)
    }

    @Test
    fun `is idempotent`() {
        val once = redacted("https://a.example/t and 203.0.113.5")

        assertEquals(once, redacted(once))
    }
}

class BuildDiagnosticReportTest {
    private fun input(
        results: List<DiagnosticResult> = emptyList(),
        logLines: List<String> = emptyList(),
        secrets: Collection<String> = emptyList()
    ) = DiagnosticReportInput(
        appVersionName = "1.4",
        appVersionCode = 14,
        androidRelease = "15",
        androidSdk = 35,
        device = "Pixel 8",
        xrayVersion = "Xray 26.6.1-beeline",
        backend = VpnBackend.FULL_AUTO,
        status = VpnRunStatus.CONNECTED,
        failureKind = VpnFailureKind.NONE,
        statusMessage = "Маршрут запущен",
        networkType = VpnNetworkType.MOBILE,
        components = listOf(
            VpnComponentSnapshot(VpnRuntimeComponent.XRAY, VpnComponentState.RUNNING),
            VpnComponentSnapshot(VpnRuntimeComponent.BYEDPI, VpnComponentState.FALLBACK)
        ),
        zapretPreset = ZapretPreset.AUTO,
        allowIpv6Bypass = false,
        advancedModeEnabled = true,
        excludedAppCount = 3,
        profileCount = 7,
        results = results,
        logLines = logLines,
        secrets = secrets
    )

    @Test
    fun `carries the context a helper needs`() {
        val report = buildDiagnosticReport(input())

        assertTrue(report.contains("1.4 (14)"))
        assertTrue(report.contains("Xray 26.6.1-beeline"))
        assertTrue(report.contains("Pixel 8"))
        assertTrue(report.contains(VpnBackend.FULL_AUTO.title))
        assertTrue(report.contains("blackholed"))
        assertTrue(report.contains("Xray: RUNNING"))
        assertTrue(report.contains("ByeDPI: FALLBACK"))
    }

    @Test
    fun `says so when nothing has been probed or logged`() {
        val report = buildDiagnosticReport(input())

        assertTrue(report.contains("(not run)"))
        assertTrue(report.contains("(empty)"))
    }

    @Test
    fun `includes probe outcomes`() {
        val report = buildDiagnosticReport(
            input(
                results = listOf(
                    DiagnosticResult(
                        target = ConnectivityDiagnostics.target("google"),
                        status = DiagnosticStatus.SUCCESS,
                        delayMs = 42,
                        statusCode = 204
                    )
                )
            )
        )

        assertTrue(report.contains("SUCCESS"))
        assertTrue(report.contains("42ms"))
    }

    @Test
    fun `redacts secrets reaching it through the log`() {
        val report = buildDiagnosticReport(
            input(
                logLines = listOf(
                    "[xray] dial 198.51.100.9:8443 uuid 3f2504e0-4f89-11d3-9a0c-0305e82c3301"
                ),
                secrets = listOf("https://provider.example/sub/token")
            )
        )

        assertFalse(report.contains("198.51.100.9"))
        assertFalse(report.contains("3f2504e0"))
        assertFalse(report.contains("provider.example"))
    }

    @Test
    fun `never leaks the subscription URL even when it only appears in the log`() {
        val report = buildDiagnosticReport(
            input(logLines = listOf("[xray] config from https://provider.example/s/tok3n"))
        )

        assertFalse(report.contains("provider.example"))
        assertFalse(report.contains("tok3n"))
    }
}

class RingLogTest {
    @Test
    fun `keeps the newest lines once full`() {
        val log = RingLog(capacity = 3)
        (1..5).forEach { log.record("t", "line$it") }

        assertEquals(listOf("[t] line3", "[t] line4", "[t] line5"), log.snapshot())
    }

    @Test
    fun `never grows past capacity`() {
        val log = RingLog(capacity = 10)
        repeat(1_000) { log.record("t", "line$it") }

        assertEquals(10, log.size())
    }

    @Test
    fun `tags every line`() {
        val log = RingLog(capacity = 2)
        log.record("xray", "started")

        assertEquals(listOf("[xray] started"), log.snapshot())
    }

    @Test
    fun `clear empties the buffer`() {
        val log = RingLog(capacity = 4)
        log.record("t", "a")
        log.clear()

        assertEquals(emptyList<String>(), log.snapshot())
    }

    @Test
    fun `snapshot does not alias the buffer`() {
        val log = RingLog(capacity = 4)
        log.record("t", "a")
        val snapshot = log.snapshot()
        log.record("t", "b")

        assertEquals(listOf("[t] a"), snapshot)
    }
}
