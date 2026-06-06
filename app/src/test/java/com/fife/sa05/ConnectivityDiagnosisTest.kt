package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectivityDiagnosisTest {
    private fun results(vararg reachable: String): List<DiagnosticResult> =
        ConnectivityDiagnostics.targets.map {
            DiagnosticResult(it, if (it.id in reachable) 100 else null)
        }

    @Test
    fun allTargetsMeansNoDetectedRestrictions() {
        assertEquals(
            "Ограничений не обнаружено",
            ConnectivityDiagnosis.describe(results("google", "yandex", "telegram"))
        )
    }

    @Test
    fun yandexOnlyMeansLikelyAllowlist() {
        assertEquals(
            "Вероятно действует белый список",
            ConnectivityDiagnosis.describe(results("yandex"))
        )
    }

    @Test
    fun telegramOnlyFailureMeansCommonBlocking() {
        assertEquals(
            "Обычные блокировки: Telegram недоступен",
            ConnectivityDiagnosis.describe(results("google", "yandex"))
        )
    }
}
