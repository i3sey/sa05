package com.fife.sa05.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fife.sa05.ConnectionCheckSummary
import com.fife.sa05.ConnectionSummaryStatus
import com.fife.sa05.ConnectivityDiagnostics
import com.fife.sa05.DiagnosticResult
import com.fife.sa05.DiagnosticStatus
import com.fife.sa05.DiagnosticTarget
import com.fife.sa05.connectionCheckSummary
import com.fife.sa05.ui.theme.motionTween

private fun ConnectionSummaryStatus.label(): String = when (this) {
    ConnectionSummaryStatus.NOT_CHECKED -> "Не проверено"
    ConnectionSummaryStatus.CHECKING -> "Проверяем"
    ConnectionSummaryStatus.AVAILABLE -> "Работает"
    ConnectionSummaryStatus.UNAVAILABLE -> "Не работает"
}

@Composable
private fun ConnectionSummaryStatus.color() = when (this) {
    ConnectionSummaryStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
    ConnectionSummaryStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
    ConnectionSummaryStatus.CHECKING -> MaterialTheme.colorScheme.tertiary
    ConnectionSummaryStatus.NOT_CHECKED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun diagnosticResultText(result: DiagnosticResult?): String = when {
    result == null -> "Ожидает проверки"
    result.status == DiagnosticStatus.SUCCESS ->
        listOfNotNull(result.statusCode?.toString(), result.delayMs?.let { "$it мс" })
            .joinToString(" · ")
            .ifBlank { "Успех" }
    result.error.isNotBlank() -> result.error
    result.status == DiagnosticStatus.INCONCLUSIVE -> "Неоднозначно"
    else -> "Ошибка"
}

@Composable
private fun SummaryCard(
    title: String,
    description: String,
    status: ConnectionSummaryStatus
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                status.label(),
                style = MaterialTheme.typography.labelLarge,
                color = status.color()
            )
        }
    }
}

@Composable
internal fun DiagnosticsScreen(
    diagnosticResults: List<DiagnosticResult>?,
    diagnosticRunning: Boolean,
    activeDiagnosticId: String?,
    diagnosticRoute: String,
    advancedModeEnabled: Boolean,
    onRunDiagnostics: () -> Unit,
    onCancelDiagnostics: () -> Unit,
    onOpenTarget: (DiagnosticTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val results = diagnosticResults.orEmpty()
    val summary = remember(results, diagnosticRunning) {
        connectionCheckSummary(results, diagnosticRunning)
    }
    var showTechnicalDetails by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Проверка подключения", style = MaterialTheme.typography.titleLarge)
                    Text(
                        summary.recommendation,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (diagnosticRunning) {
                        Text(
                            "Проверено ${results.size} из ${ConnectivityDiagnostics.targets.size}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        val progress by animateFloatAsState(
                            targetValue = results.size.toFloat() /
                                ConnectivityDiagnostics.targets.size.toFloat(),
                            animationSpec = motionTween(),
                            label = "connectionCheckProgress"
                        )
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        activeDiagnosticId?.let { activeId ->
                            ConnectivityDiagnostics.targets.firstOrNull { it.id == activeId }?.let {
                                Text(
                                    "Сейчас: ${it.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (diagnosticRunning) {
                        OutlinedButton(
                            onClick = onCancelDiagnostics,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Остановить проверку") }
                    } else {
                        Button(
                            onClick = onRunDiagnostics,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (results.isEmpty()) "Проверить подключение" else "Проверить снова")
                        }
                    }
                }
            }
        }
        item {
            SummaryCard(
                title = "Интернет",
                description = "Google или Ya.ru",
                status = summary.internet
            )
        }
        item {
            SummaryCard(
                title = "Сайты с ограничениями",
                description = "Kinozal и NNMClub",
                status = summary.restrictedSites
            )
        }
        item {
            SummaryCard(
                title = "Telegram",
                description = "Проверяем отдельно от сайтов",
                status = summary.telegram
            )
        }
        if (advancedModeEnabled) {
            item {
                TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
                    Text(if (showTechnicalDetails) "Скрыть технические детали" else "Технические детали")
                }
            }
            item {
                AnimatedVisibility(visible = showTechnicalDetails) {
                    TechnicalDiagnostics(
                        results = results,
                        activeDiagnosticId = activeDiagnosticId,
                        diagnosticRoute = diagnosticRoute,
                        onOpenTarget = onOpenTarget
                    )
                }
            }
        }
    }
}

@Composable
private fun TechnicalDiagnostics(
    results: List<DiagnosticResult>,
    activeDiagnosticId: String?,
    diagnosticRoute: String,
    onOpenTarget: (DiagnosticTarget) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                "Технические детали",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            if (diagnosticRoute.isNotBlank()) {
                Text(
                    "Маршрут: $diagnosticRoute",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp)
                )
            }
            ConnectivityDiagnostics.targets.forEachIndexed { index, target ->
                val result = results.firstOrNull { it.target.id == target.id }
                val active = activeDiagnosticId == target.id
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(target.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (active) "Запрос выполняется" else diagnosticResultText(result),
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                active -> MaterialTheme.colorScheme.tertiary
                                result?.status == DiagnosticStatus.FAILED -> MaterialTheme.colorScheme.error
                                result?.status == DiagnosticStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    TextButton(onClick = { onOpenTarget(target) }) { Text("Открыть") }
                }
                if (index != ConnectivityDiagnostics.targets.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
