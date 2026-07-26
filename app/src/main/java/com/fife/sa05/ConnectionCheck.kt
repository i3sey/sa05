package com.fife.sa05

/**
 * Short, non-invasive connection check shown after a VPN has connected.
 * It intentionally covers one control endpoint; the complete diagnostics
 * remain a user initiated operation.
 */
internal sealed interface ConnectionCheckState {
    data object Idle : ConnectionCheckState
    data object Running : ConnectionCheckState
    data class Passed(val result: DiagnosticResult) : ConnectionCheckState
    data class Failed(val result: DiagnosticResult? = null) : ConnectionCheckState
}

internal fun connectionCheckState(result: DiagnosticResult): ConnectionCheckState =
    if (result.reachable) {
        ConnectionCheckState.Passed(result)
    } else {
        ConnectionCheckState.Failed(result)
    }

enum class ConnectionSummaryStatus {
    NOT_CHECKED,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE
}

data class ConnectionCheckSummary(
    val internet: ConnectionSummaryStatus,
    val restrictedSites: ConnectionSummaryStatus,
    val telegram: ConnectionSummaryStatus,
    val recommendation: String
)

/** Turns the seven technical probes into the three outcomes shown to normal users. */
internal fun connectionCheckSummary(
    results: List<DiagnosticResult>,
    running: Boolean
): ConnectionCheckSummary {
    fun pending(): ConnectionSummaryStatus =
        if (running) ConnectionSummaryStatus.CHECKING else ConnectionSummaryStatus.NOT_CHECKED

    val byId = results.associateBy { it.target.id }
    val controls = listOfNotNull(byId["google"], byId["yandex"])
    val internet = when {
        controls.any { it.reachable } -> ConnectionSummaryStatus.AVAILABLE
        controls.size == 2 -> ConnectionSummaryStatus.UNAVAILABLE
        else -> pending()
    }
    val restricted = listOfNotNull(byId["kinozal"], byId["nnmclub"])
    val restrictedSites = when {
        restricted.size == 2 && restricted.all { it.reachable } &&
            internet == ConnectionSummaryStatus.AVAILABLE -> ConnectionSummaryStatus.AVAILABLE
        restricted.any { it.status == DiagnosticStatus.FAILED } ||
            (restricted.size == 2 && internet == ConnectionSummaryStatus.UNAVAILABLE) ->
            ConnectionSummaryStatus.UNAVAILABLE
        else -> pending()
    }
    val telegramResult = byId["telegram"]
    val telegram = when {
        telegramResult == null -> pending()
        telegramResult.reachable -> ConnectionSummaryStatus.AVAILABLE
        else -> ConnectionSummaryStatus.UNAVAILABLE
    }
    val recommendation = when {
        internet == ConnectionSummaryStatus.UNAVAILABLE ->
            "Интернета нет. Проверьте связь и запустите проверку снова."
        restrictedSites == ConnectionSummaryStatus.UNAVAILABLE ->
            "Обычные сайты открываются, а заблокированные — нет. Попробуйте другой сервер."
        telegram == ConnectionSummaryStatus.UNAVAILABLE ->
            "Telegram не открылся. Включите на главном экране режим «Только Telegram»."
        internet == ConnectionSummaryStatus.AVAILABLE &&
            restrictedSites == ConnectionSummaryStatus.AVAILABLE &&
            telegram == ConnectionSummaryStatus.AVAILABLE -> "Подключение работает."
        running -> "Проверяем подключение…"
        else -> "Проверим, открываются ли обычные и заблокированные сайты."
    }
    return ConnectionCheckSummary(internet, restrictedSites, telegram, recommendation)
}
