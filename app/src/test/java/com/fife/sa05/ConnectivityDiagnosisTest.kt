package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityDiagnosisTest {
    private fun results(vararg successful: String): List<DiagnosticResult> =
        ConnectivityDiagnostics.targets.map {
            DiagnosticResult(
                target = it,
                status = if (it.id in successful) {
                    DiagnosticStatus.SUCCESS
                } else {
                    DiagnosticStatus.FAILED
                },
                delayMs = if (it.id in successful) 100 else null
            )
        }

    @Test
    fun controlAndTwoDpiSitesConfirmBypass() {
        assertTrue(
            ConnectivityDiagnostics.bypassWorks(
                results("google", "rule34", "kinozal")
            )
        )
    }

    @Test
    fun dpiSitesWithoutControlDoNotConfirmBypass() {
        assertFalse(
            ConnectivityDiagnostics.bypassWorks(
                results("rule34", "kinozal", "nnmclub")
            )
        )
    }

    @Test
    fun rutrackerDoesNotAffectBypassScore() {
        assertEquals(
            1,
            ConnectivityDiagnostics.bypassScore(
                results("google", "rutracker", "rule34", "telegram")
            )
        )
    }

    @Test
    fun forbiddenResponseIsFailure() {
        val result = ConnectivityDiagnostics().classifyResponse(
            target = ConnectivityDiagnostics.target("rule34"),
            statusCode = 403,
            bodyBytes = 1_000,
            delayMs = 100
        )

        assertEquals(DiagnosticStatus.FAILED, result.status)
    }

    @Test
    fun rutracker521IsInconclusive() {
        val result = ConnectivityDiagnostics().classifyResponse(
            target = ConnectivityDiagnostics.target("rutracker"),
            statusCode = 521,
            bodyBytes = 500,
            delayMs = 100
        )

        assertEquals(DiagnosticStatus.INCONCLUSIVE, result.status)
    }

    @Test
    fun shortHtmlResponseIsFailure() {
        val result = ConnectivityDiagnostics().classifyResponse(
            target = ConnectivityDiagnostics.target("kinozal"),
            statusCode = 200,
            bodyBytes = 20,
            delayMs = 100
        )

        assertEquals(DiagnosticStatus.FAILED, result.status)
    }
}
