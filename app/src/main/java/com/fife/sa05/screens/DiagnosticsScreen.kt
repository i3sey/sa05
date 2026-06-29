package com.fife.sa05.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fife.sa05.components.SectionTitle
import com.fife.sa05.ConnectivityDiagnosis
import com.fife.sa05.ConnectivityDiagnostics
import com.fife.sa05.DiagnosticGroup
import com.fife.sa05.DiagnosticResult
import com.fife.sa05.DiagnosticStatus
import com.fife.sa05.DiagnosticTarget
import com.fife.sa05.ui.theme.motionTween

private fun diagnosticGroupTitle(group: DiagnosticGroup): String = when (group) {
    DiagnosticGroup.CONTROL -> "Контроль"
    DiagnosticGroup.DPI -> "DPI"
    DiagnosticGroup.MEDIA -> "Медиа"
    DiagnosticGroup.IP -> "IP"
}

private fun diagnosticResultText(result: DiagnosticResult?): String = when {
    result == null -> "Ожидает проверки"
    result.status == DiagnosticStatus.SUCCESS ->
        listOfNotNull(result.statusCode?.toString(), result.delayMs?.let { "$it мс" })
            .joinToString(" · ")
            .ifBlank { "Успех" }
    result.error.isNotBlank() -> result.error
    else -> when (result.status) {
        DiagnosticStatus.SUCCESS -> "Успех"
        DiagnosticStatus.FAILED -> "Ошибка"
        DiagnosticStatus.INCONCLUSIVE -> "Неоднозначно"
    }
}

@Composable
internal fun DiagnosticsScreen(
    diagnosticResults: List<DiagnosticResult>?,
    diagnosticRunning: Boolean,
    activeDiagnosticId: String?,
    diagnosticRoute: String,
    onRunDiagnostics: () -> Unit,
    onCancelDiagnostics: () -> Unit,
    onOpenTarget: (DiagnosticTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val results = diagnosticResults.orEmpty()
    val orderedGroups = listOf(
        DiagnosticGroup.CONTROL,
        DiagnosticGroup.DPI,
        DiagnosticGroup.MEDIA,
        DiagnosticGroup.IP
    )
    val groupedTargets = ConnectivityDiagnostics.targets.groupBy { it.group }

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
                    Text(
                        "Проверка ограничений",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        if (diagnosticRoute.isBlank()) {
                            "HTTPS-проверки выполняются последовательно."
                        } else {
                            "Маршрут: $diagnosticRoute"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when {
                        diagnosticRunning -> {
                            Text(
                                "Проверено ${results.size} из " +
                                    ConnectivityDiagnostics.targets.size,
                                style = MaterialTheme.typography.labelLarge
                            )
                            val diagProgress by animateFloatAsState(
                                targetValue = results.size.toFloat() /
                                    ConnectivityDiagnostics.targets.size.toFloat(),
                                animationSpec = motionTween(),
                                label = "diagScreenProgress"
                            )
                            LinearProgressIndicator(
                                progress = { diagProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            activeDiagnosticId?.let { activeId ->
                                ConnectivityDiagnostics.targets
                                    .firstOrNull { it.id == activeId }
                                    ?.let { target ->
                                        Text(
                                            "Сейчас проверяется: ${target.label}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                            }
                        }

                        results.isNotEmpty() -> {
                            Text(
                                ConnectivityDiagnosis.describe(results),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Результаты последнего запуска остаются на экране.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> Text(
                            "Проверяем контрольные сайты, DPI, медиа и Telegram.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Crossfade(
                        targetState = diagnosticRunning,
                        animationSpec = motionTween(),
                        label = "diagScreenButton"
                    ) { running ->
                        if (running) {
                            OutlinedButton(
                                onClick = onCancelDiagnostics,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Остановить проверку")
                            }
                        } else {
                            Button(
                                onClick = onRunDiagnostics,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (results.isEmpty()) {
                                        "Начать проверку"
                                    } else {
                                        "Проверить снова"
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        "Кнопка «Открыть» запускает системный браузер и служит " +
                            "финальной проверкой маршрута.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        orderedGroups.forEach { group ->
            val targets = groupedTargets[group].orEmpty()
            if (targets.isNotEmpty()) {
                item { SectionTitle(diagnosticGroupTitle(group)) }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            targets.forEachIndexed { index, target ->
                                val result = results.firstOrNull { it.target.id == target.id }
                                val statusText = when {
                                    activeDiagnosticId == target.id -> "Проверяется"
                                    result == null -> "Ожидает"
                                    result.status == DiagnosticStatus.SUCCESS -> "Доступен"
                                    result.status == DiagnosticStatus.FAILED -> "Недоступен"
                                    else -> "Неоднозначно"
                                }
                                val statusColorTarget = when {
                                    result == null ->
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    result.status == DiagnosticStatus.SUCCESS ->
                                        MaterialTheme.colorScheme.primary
                                    result.status == DiagnosticStatus.FAILED ->
                                        MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.tertiary
                                }
                                val statusColor by animateColorAsState(
                                    statusColorTarget,
                                    motionTween(),
                                    label = "diagStatusColor"
                                )
                                val detailText = if (activeDiagnosticId == target.id) {
                                    "Запрос выполняется"
                                } else {
                                    diagnosticResultText(result)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateContentSize()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                target.label,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            if (target.informationalOnly) {
                                                Text(
                                                    "Инфо",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme
                                                        .colorScheme
                                                        .onSurfaceVariant
                                                )
                                            }
                                        }
                                        Text(
                                            "$statusText · $detailText",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = statusColor
                                        )
                                    }
                                    TextButton(onClick = { onOpenTarget(target) }) {
                                        Text("Открыть")
                                    }
                                    AnimatedVisibility(
                                        visible = activeDiagnosticId == target.id,
                                        enter = scaleIn(motionTween()) + fadeIn(motionTween()),
                                        exit = scaleOut(motionTween()) + fadeOut(motionTween())
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                                if (index != targets.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
