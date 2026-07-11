package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionCheckTest {
    private val target = ConnectivityDiagnostics.target("google")

    @Test
    fun `successful control probe passes the lightweight check`() {
        val state = connectionCheckState(
            DiagnosticResult(
                target = target,
                status = DiagnosticStatus.SUCCESS,
                delayMs = 120,
                statusCode = 204
            )
        )

        assertEquals(
            ConnectionCheckState.Passed(
                DiagnosticResult(
                    target = target,
                    status = DiagnosticStatus.SUCCESS,
                    delayMs = 120,
                    statusCode = 204
                )
            ),
            state
        )
    }

    @Test
    fun `three status summary distinguishes internet restricted sites and telegram`() {
        val results = listOf("google", "kinozal", "nnmclub", "telegram").map { id ->
            DiagnosticResult(
                target = ConnectivityDiagnostics.target(id),
                status = DiagnosticStatus.SUCCESS
            )
        }

        val summary = connectionCheckSummary(results, running = false)

        assertEquals(ConnectionSummaryStatus.AVAILABLE, summary.internet)
        assertEquals(ConnectionSummaryStatus.AVAILABLE, summary.restrictedSites)
        assertEquals(ConnectionSummaryStatus.AVAILABLE, summary.telegram)
        assertEquals("Подключение работает.", summary.recommendation)
    }

    @Test
    fun `failed restricted site is reported separately from a working internet`() {
        val summary = connectionCheckSummary(
            listOf(
                DiagnosticResult(target, DiagnosticStatus.SUCCESS),
                DiagnosticResult(
                    ConnectivityDiagnostics.target("kinozal"),
                    DiagnosticStatus.FAILED
                )
            ),
            running = true
        )

        assertEquals(ConnectionSummaryStatus.AVAILABLE, summary.internet)
        assertEquals(ConnectionSummaryStatus.UNAVAILABLE, summary.restrictedSites)
        assertEquals(ConnectionSummaryStatus.CHECKING, summary.telegram)
    }

    @Test
    fun `inconclusive rutracker does not downgrade the three user statuses`() {
        val results = listOf("google", "kinozal", "nnmclub", "telegram").map { id ->
            DiagnosticResult(ConnectivityDiagnostics.target(id), DiagnosticStatus.SUCCESS)
        } + DiagnosticResult(
            ConnectivityDiagnostics.target("rutracker"),
            DiagnosticStatus.INCONCLUSIVE,
            error = "HTTP 521"
        )

        val summary = connectionCheckSummary(results, running = false)

        assertEquals(ConnectionSummaryStatus.AVAILABLE, summary.internet)
        assertEquals(ConnectionSummaryStatus.AVAILABLE, summary.restrictedSites)
        assertEquals(ConnectionSummaryStatus.AVAILABLE, summary.telegram)
    }

    @Test
    fun `empty result has not checked statuses`() {
        val summary = connectionCheckSummary(emptyList(), running = false)

        assertEquals(ConnectionSummaryStatus.NOT_CHECKED, summary.internet)
        assertEquals(ConnectionSummaryStatus.NOT_CHECKED, summary.restrictedSites)
        assertEquals(ConnectionSummaryStatus.NOT_CHECKED, summary.telegram)
    }

    @Test
    fun `failed control probe keeps the diagnostic detail`() {
        val result = DiagnosticResult(
            target = target,
            status = DiagnosticStatus.FAILED,
            error = "Таймаут"
        )

        assertEquals(ConnectionCheckState.Failed(result), connectionCheckState(result))
    }
}
