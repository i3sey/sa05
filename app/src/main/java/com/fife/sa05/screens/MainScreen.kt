package com.fife.sa05.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.fife.sa05.AppUpdateState
import com.fife.sa05.components.DashboardRow
import com.fife.sa05.components.FlagBadge
import com.fife.sa05.ConnectivityDiagnosis
import com.fife.sa05.ConnectivityDiagnostics
import com.fife.sa05.DiagnosticResult
import com.fife.sa05.DiagnosticStatus
import com.fife.sa05.parseServerRemark
import com.fife.sa05.ProfileExplainer
import com.fife.sa05.beelineProfileInfo
import com.fife.sa05.StrategyExplainer
import com.fife.sa05.SubscriptionState
import com.fife.sa05.ui.theme.expandFadeIn
import com.fife.sa05.ui.theme.fadeScaleTransform
import com.fife.sa05.ui.theme.fadeTransform
import com.fife.sa05.ui.theme.Motion
import com.fife.sa05.ui.theme.motionEnabled
import com.fife.sa05.ui.theme.motionTween
import com.fife.sa05.ui.theme.pressScale
import com.fife.sa05.ui.theme.shrinkFadeOut
import com.fife.sa05.VpnBackend
import com.fife.sa05.VpnComponentState
import com.fife.sa05.VpnPrimaryAction
import com.fife.sa05.VpnRunStatus
import com.fife.sa05.VpnRuntimeSnapshot
import com.fife.sa05.VpnSecondaryAction
import com.fife.sa05.vpnStatusPresentation
import com.fife.sa05.XrayPreferences
import com.fife.sa05.ZapretAutoProgress
import com.fife.sa05.ZapretPreset
import kotlinx.coroutines.delay
import java.util.Locale

private fun VpnBackend.clientTitle(): String = when (this) {
    VpnBackend.FULL_AUTO -> "[BETA] Автоматически"
    VpnBackend.LOCAL_BYPASS -> "[BETA] Локальный обход"
    VpnBackend.PROXY_ONLY -> "Только прокси"
}

private fun VpnBackend.clientDescription(): String = when (this) {
    VpnBackend.FULL_AUTO -> "Telegram локально, YouTube с обходом, остальное через профиль"
    VpnBackend.LOCAL_BYPASS -> "Локальный обход без удалённого профиля"
    VpnBackend.PROXY_ONLY -> "Весь трафик через выбранный профиль"
}

private fun formatElapsed(startedAtMillis: Long, nowMillis: Long): String {
    val totalSeconds = ((nowMillis - startedAtMillis).coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

private fun VpnSecondaryAction.title(): String = when (this) {
    VpnSecondaryAction.NETWORK_SETTINGS -> "Настройки сети"
    VpnSecondaryAction.CHANGE_PROFILE -> "Сменить профиль"
    VpnSecondaryAction.CHANGE_STRATEGY -> "Сменить стратегию"
    VpnSecondaryAction.DIAGNOSTICS -> "Диагностика"
}

@Composable
private fun StatusPill(
    text: String,
    state: VpnComponentState? = null,
    modifier: Modifier = Modifier
) {
    val container = when (state) {
        VpnComponentState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        VpnComponentState.FALLBACK -> MaterialTheme.colorScheme.secondaryContainer
        VpnComponentState.FAILED -> MaterialTheme.colorScheme.errorContainer
        VpnComponentState.STARTING -> MaterialTheme.colorScheme.tertiaryContainer
        null -> MaterialTheme.colorScheme.surface
    }
    val content = when (state) {
        VpnComponentState.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
        VpnComponentState.FALLBACK -> MaterialTheme.colorScheme.onSecondaryContainer
        VpnComponentState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        VpnComponentState.STARTING -> MaterialTheme.colorScheme.onTertiaryContainer
        null -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = content
        )
    }
}

private fun appUpdateSummary(updateState: AppUpdateState): String = when (updateState) {
    AppUpdateState.Idle -> "Проверить GitHub Releases"
    AppUpdateState.Checking -> "Проверяем последнюю версию"
    AppUpdateState.UpToDate -> "Установлена актуальная версия"
    is AppUpdateState.Available -> "Доступна версия ${updateState.release.versionName}"
    is AppUpdateState.Error -> "Ошибка: ${updateState.message}"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RedesignedMainScreen(
    subscription: SubscriptionState,
    vpnRuntime: VpnRuntimeSnapshot,
    updating: Boolean,
    diagnosticResults: List<DiagnosticResult>?,
    diagnosticRunning: Boolean,
    activeDiagnosticId: String?,
    diagnosticRoute: String,
    zapretAutoProgress: ZapretAutoProgress,
    selectedBackend: VpnBackend,
    zapretPreset: ZapretPreset,
    telegramCfEnabled: Boolean,
    telegramCfDomain: String,
    updateState: AppUpdateState,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onToggleVpn: () -> Unit,
    onSelectBackend: (VpnBackend) -> Unit,
    onSelectZapretPreset: (ZapretPreset) -> Unit,
    onRetryZapretAuto: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onCancelDiagnostics: () -> Unit,
    onApplyTelegram: () -> Unit,
    onDiagnostics: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onOpenSubscriptionSettings: () -> Unit,
    onExclusions: () -> Unit,
    onCheckUpdate: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = LocalHapticFeedback.current
    val runtime = vpnRuntime
    val presentation = remember(runtime) { vpnStatusPresentation(runtime) }
    var nowMillis by remember(runtime.connectedAtMillis) {
        mutableStateOf(System.currentTimeMillis())
    }
    var profileSheetVisible by remember { mutableStateOf(false) }
    var modeSheetVisible by remember { mutableStateOf(false) }
    var explainedProfileId by remember { mutableStateOf<String?>(null) }
    var explainedPreset by remember { mutableStateOf<ZapretPreset?>(null) }
    val beeline = remember(subscription.activeProfileId) {
        beelineProfileInfo(subscription.activeProfile?.json.orEmpty())
    }
    var beelineHintVisible by remember { mutableStateOf(false) }
    val modeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val profileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectorListState = rememberLazyListState()
    val profileMode = selectedBackend.usesXrayProfile
    val selectedSelectorIndex = if (profileMode) {
        subscription.profiles.indexOfFirst { it.id == subscription.activeProfile?.id }
    } else {
        ZapretPreset.selectable.indexOf(zapretPreset)
    }
    val activeServerRemark = parseServerRemark(
        subscription.activeProfile?.remarks.orEmpty()
    )
    val connected = runtime.status == VpnRunStatus.CONNECTED
    val connecting = runtime.status == VpnRunStatus.CONNECTING ||
        runtime.status == VpnRunStatus.RECOVERING
    val failed = runtime.status == VpnRunStatus.ERROR
    val connectionContainerTarget = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        connected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val connectionContentTarget = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        connected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val connectionContainer by animateColorAsState(
        connectionContainerTarget, motionTween(), label = "connContainer"
    )
    val connectionContent by animateColorAsState(
        connectionContentTarget, motionTween(), label = "connContent"
    )

    LaunchedEffect(runtime.connectedAtMillis, runtime.status) {
        while (runtime.connectedAtMillis > 0L && runtime.requested) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    LaunchedEffect(profileSheetVisible, profileMode, selectedSelectorIndex) {
        if (profileSheetVisible && selectedSelectorIndex >= 0) {
            selectorListState.scrollToItem(selectedSelectorIndex)
        }
    }

    if (modeSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { modeSheetVisible = false },
            sheetState = modeSheetState
        ) {
            Text(
                "Режим работы",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            VpnBackend.entries.forEach { backend ->
                ListItem(
                    headlineContent = { Text(backend.clientTitle()) },
                    supportingContent = { Text(backend.clientDescription()) },
                    leadingContent = {
                        Crossfade(
                            targetState = backend == selectedBackend,
                            animationSpec = motionTween(),
                            label = "modeCheck"
                        ) { selected ->
                            Icon(
                                imageVector = if (selected) {
                                    Icons.Default.CheckCircle
                                } else {
                                    Icons.Default.Tune
                                },
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        onSelectBackend(backend)
                        modeSheetVisible = false
                    }
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (profileSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { profileSheetVisible = false },
            sheetState = profileSheetState
        ) {
            Column(modifier = Modifier.fillMaxHeight(0.92f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (profileMode) "Профиль" else "Стратегия",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (profileMode) onRefresh() else onRetryZapretAuto()
                        },
                        enabled = if (profileMode) {
                            subscription.url.isNotBlank() && !updating
                        } else {
                            zapretPreset == ZapretPreset.AUTO
                        }
                    ) {
                        Crossfade(
                            targetState = profileMode && updating,
                            animationSpec = motionTween(),
                            label = "refreshSpinner"
                        ) { loading ->
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = if (profileMode) {
                                    "Обновить подписку"
                                } else {
                                    "Повторить подбор"
                                }
                            )
                        }
                        }
                    }
                }
                LazyColumn(
                    state = selectorListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (profileMode) {
                        itemsIndexed(
                            items = subscription.profiles,
                            key = { index, profile -> "profile-${profile.id}-$index" }
                        ) { _, profile ->
                            val serverRemark = parseServerRemark(profile.remarks)
                            val explained = explainedProfileId == profile.id
                            val chevronRotation by animateFloatAsState(
                                targetValue = if (explained) 180f else 0f,
                                animationSpec = motionTween(),
                                label = "profile-chevron-${profile.id}"
                            )
                            ListItem(
                                headlineContent = {
                                    Text(serverRemark.name.ifBlank { "Сервер" })
                                },
                                leadingContent = {
                                    Crossfade(
                                        targetState = profile.id ==
                                            subscription.activeProfile?.id,
                                        animationSpec = motionTween(),
                                        label = "profileCheck"
                                    ) { active ->
                                        Icon(
                                            imageVector = if (active) {
                                                Icons.Default.CheckCircle
                                            } else {
                                                Icons.Default.Dns
                                            },
                                            contentDescription = null
                                        )
                                    }
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = {
                                            explainedProfileId = if (explained) null else profile.id
                                        }) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = if (explained) {
                                                    "Скрыть параметры профиля"
                                                } else {
                                                    "Как работает профиль"
                                                },
                                                modifier = Modifier.rotate(chevronRotation)
                                            )
                                        }
                                        serverRemark.flag?.let { FlagBadge(it) }
                                    }
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent
                                ),
                                modifier = Modifier.clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    onSelect(profile.id)
                                    profileSheetVisible = false
                                }
                            )
                            AnimatedVisibility(
                                visible = explained,
                                enter = expandFadeIn(),
                                exit = shrinkFadeOut()
                            ) {
                                ProfileExplainer(
                                    profile = profile,
                                    modifier = Modifier.padding(
                                        start = 24.dp,
                                        top = 8.dp,
                                        end = 24.dp,
                                        bottom = 8.dp
                                    )
                                )
                            }
                        }
                    } else {
                        items(
                            items = ZapretPreset.selectable,
                            key = { "preset-${it.name}" }
                        ) { preset ->
                            val explained = explainedPreset == preset
                            val chevronRotation by animateFloatAsState(
                                targetValue = if (explained) 180f else 0f,
                                animationSpec = motionTween(),
                                label = "chevron-${preset.name}"
                            )
                            ListItem(
                                headlineContent = { Text(preset.title) },
                                leadingContent = {
                                    Crossfade(
                                        targetState = preset == zapretPreset,
                                        animationSpec = motionTween(),
                                        label = "presetCheck"
                                    ) { selected ->
                                        Icon(
                                            imageVector = if (selected) {
                                                Icons.Default.CheckCircle
                                            } else {
                                                Icons.Default.Tune
                                            },
                                            contentDescription = null
                                        )
                                    }
                                },
                                trailingContent = {
                                    IconButton(onClick = {
                                        explainedPreset = if (explained) null else preset
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = if (explained) {
                                                "Скрыть как работает"
                                            } else {
                                                "Как работает"
                                            },
                                            modifier = Modifier.rotate(chevronRotation)
                                        )
                                    }
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent
                                ),
                                modifier = Modifier.clickable {
                                    onSelectZapretPreset(preset)
                                    profileSheetVisible = false
                                }
                            )
                            AnimatedVisibility(
                                visible = explained,
                                enter = expandFadeIn(),
                                exit = shrinkFadeOut()
                            ) {
                                StrategyExplainer(
                                    preset = preset,
                                    modifier = Modifier.padding(
                                        start = 24.dp,
                                        top = 8.dp,
                                        end = 24.dp,
                                        bottom = 8.dp
                                    )
                                )
                            }
                        }
                    }
                    if (profileMode && runtime.status != VpnRunStatus.DISCONNECTED) {
                        item(key = "profile-reconnect-note") {
                            Text(
                                "Смена профиля автоматически переподключит VPN.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text("SA05")
                        if (subscription.title.isNotBlank()) {
                            Text(
                                subscription.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = connectionContainer)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val statusPulse = rememberInfiniteTransition(label = "statusPulse")
                        val pulseScale by statusPulse.animateFloat(
                            initialValue = 1f,
                            targetValue = if (connected && motionEnabled()) 1.08f else 1f,
                            animationSpec = infiniteRepeatable(
                                tween(1100, easing = Motion.Standard),
                                RepeatMode.Reverse
                            ),
                            label = "pulse"
                        )
                        val statusMotion = motionEnabled()
                        AnimatedContent(
                            targetState = when {
                                connecting -> 0
                                connected -> 1
                                else -> 2
                            },
                            transitionSpec = { fadeScaleTransform(statusMotion) },
                            label = "statusIcon"
                        ) { status ->
                            when (status) {
                                0 -> CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = connectionContent,
                                    strokeWidth = 3.dp
                                )
                                1 -> Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(34.dp).scale(pulseScale),
                                    tint = connectionContent
                                )
                                else -> Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(34.dp),
                                    tint = connectionContent
                                )
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                presentation.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = connectionContent
                            )
                            Text(
                                presentation.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = connectionContent
                            )
                        }
                    }
                    if (beeline != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Shield,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = connectionContent.copy(alpha = 0.85f)
                                )
                                Text(
                                    "Beeline XHTTP · бета",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = connectionContent.copy(alpha = 0.85f),
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { beelineHintVisible = !beelineHintVisible },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Info,
                                        contentDescription = "Что это",
                                        modifier = Modifier.size(16.dp),
                                        tint = connectionContent.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            AnimatedVisibility(
                                visible = beelineHintVisible,
                                enter = expandFadeIn(),
                                exit = shrinkFadeOut()
                            ) {
                                Text(
                                    "Экспериментальный обход Beeline. " +
                                        "Работает только в этом клиенте.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = connectionContent.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    if (runtime.status != VpnRunStatus.DISCONNECTED) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusPill(runtime.networkType.title)
                            if (runtime.connectedAtMillis > 0L) {
                                StatusPill(formatElapsed(runtime.connectedAtMillis, nowMillis))
                            }
                            runtime.components.forEach { component ->
                                StatusPill(
                                    text = component.component.title,
                                    state = component.state
                                )
                            }
                        }
                    }
                    val toggleInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(
                                if (presentation.primaryAction == VpnPrimaryAction.STOP) {
                                    HapticFeedbackType.ToggleOff
                                } else {
                                    HapticFeedbackType.ToggleOn
                                }
                            )
                            if (presentation.primaryAction == VpnPrimaryAction.OPEN_SUBSCRIPTION) {
                                onOpenSubscriptionSettings()
                            } else {
                                onToggleVpn()
                            }
                        },
                        interactionSource = toggleInteraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .pressScale(toggleInteraction)
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        val toggleMotion = motionEnabled()
                        AnimatedContent(
                            targetState = presentation.primaryAction,
                            transitionSpec = { fadeTransform(toggleMotion) },
                            label = "toggleLabel"
                        ) { action ->
                            Text(
                                when (action) {
                                    VpnPrimaryAction.CONNECT -> "Подключить VPN"
                                    VpnPrimaryAction.STOP -> "Отключить VPN"
                                    VpnPrimaryAction.RETRY -> "Повторить"
                                    VpnPrimaryAction.OPEN_SUBSCRIPTION -> "Настроить подписку"
                                }
                            )
                        }
                    }
                    if (presentation.secondaryActions.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            presentation.secondaryActions.forEach { action ->
                                OutlinedButton(
                                    onClick = {
                                        when (action) {
                                            VpnSecondaryAction.NETWORK_SETTINGS ->
                                                onOpenNetworkSettings()
                                            VpnSecondaryAction.CHANGE_PROFILE,
                                            VpnSecondaryAction.CHANGE_STRATEGY ->
                                                run { profileSheetVisible = true }
                                            VpnSecondaryAction.DIAGNOSTICS -> onDiagnostics()
                                        }
                                    }
                                ) {
                                    Text(action.title())
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Маршрут",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
                    )
                    DashboardRow(
                        title = "Режим",
                        subtitle = selectedBackend.clientTitle(),
                        onClick = { modeSheetVisible = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    DashboardRow(
                        title = if (profileMode) "Профиль" else "Стратегия",
                        subtitle = if (profileMode) {
                            activeServerRemark.name.ifBlank {
                                "Профиль не выбран"
                            }
                        } else {
                            zapretPreset.title
                        },
                        trailingFlag = activeServerRemark.flag.takeIf { profileMode },
                        onClick = {
                            if (profileMode) {
                                explainedProfileId = subscription.activeProfile?.id
                            } else {
                                explainedPreset = zapretPreset
                            }
                            profileSheetVisible = true
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    DashboardRow(
                        title = "Исключения",
                        subtitle = "Приложения с прямым доступом",
                        onClick = onExclusions
                    )
                }
            }
        }

        item {
            val results = diagnosticResults.orEmpty()
            val successCount = results.count { it.status == DiagnosticStatus.SUCCESS }
            val problemCount = results.count { it.status == DiagnosticStatus.FAILED }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Проверка соединения",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDiagnostics) {
                            Text("Подробнее")
                        }
                    }
                    Text(
                        when {
                            diagnosticRunning ->
                                "Проверено ${results.size} из " +
                                    ConnectivityDiagnostics.targets.size
                            results.isNotEmpty() -> ConnectivityDiagnosis.describe(results)
                            diagnosticRoute.isNotBlank() -> "Маршрут: $diagnosticRoute"
                            else -> "Проверка HTTPS-доступа и обхода ограничений."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (diagnosticRunning) {
                        val diagProgress by animateFloatAsState(
                            targetValue = results.size.toFloat() /
                                ConnectivityDiagnostics.targets.size.toFloat(),
                            animationSpec = motionTween(),
                            label = "diagProgress"
                        )
                        LinearProgressIndicator(
                            progress = { diagProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        activeDiagnosticId?.let { id ->
                            ConnectivityDiagnostics.targets
                                .firstOrNull { it.id == id }
                                ?.let { target ->
                                    Text(
                                        "Сейчас: ${target.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                        }
                    } else if (results.isNotEmpty()) {
                        Text(
                            "Успешно: $successCount · Ошибки: $problemCount",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Crossfade(
                        targetState = diagnosticRunning,
                        animationSpec = motionTween(),
                        label = "diagButton"
                    ) { running ->
                        if (running) {
                            OutlinedButton(
                                onClick = onCancelDiagnostics,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Остановить проверку")
                            }
                        } else {
                            FilledTonalButton(
                                onClick = onRunDiagnostics,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (results.isEmpty()) {
                                        "Проверить соединение"
                                    } else {
                                        "Проверить снова"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (updateState is AppUpdateState.Available) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    DashboardRow(
                        title = "Доступно обновление",
                        subtitle = appUpdateSummary(updateState),
                        onClick = onCheckUpdate
                    )
                }
            }
        }

        if (zapretAutoProgress.running) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Подбор локального обхода",
                            style = MaterialTheme.typography.titleMedium
                        )
                        LinearProgressIndicator(
                            progress = {
                                if (zapretAutoProgress.total <= 0) {
                                    0f
                                } else {
                                    zapretAutoProgress.tested.toFloat() /
                                        zapretAutoProgress.total.toFloat()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            listOf(zapretAutoProgress.preset, zapretAutoProgress.target)
                                .filter(String::isNotBlank)
                                .joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (selectedBackend.usesTelegram &&
            connected &&
            !XrayPreferences.telegramProxyApplied(context)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Telegram готов к настройке",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (telegramCfEnabled) {
                                telegramCfDomain.ifBlank {
                                    "Используется Cloudflare-маршрут"
                                }
                            } else {
                                "Используется прямой локальный маршрут"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = onApplyTelegram,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Открыть Telegram")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.MainScreen(
    subscription: SubscriptionState,
    vpnRuntime: VpnRuntimeSnapshot,
    updating: Boolean,
    diagnosticResults: List<DiagnosticResult>?,
    diagnosticRunning: Boolean,
    activeDiagnosticId: String?,
    diagnosticRoute: String,
    zapretAutoProgress: ZapretAutoProgress,
    selectedBackend: VpnBackend,
    zapretPreset: ZapretPreset,
    telegramCfEnabled: Boolean,
    telegramCfDomain: String,
    updateState: AppUpdateState,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onToggleVpn: () -> Unit,
    onSelectBackend: (VpnBackend) -> Unit,
    onSelectZapretPreset: (ZapretPreset) -> Unit,
    onRetryZapretAuto: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onCancelDiagnostics: () -> Unit,
    onApplyTelegram: () -> Unit,
    onDiagnostics: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onOpenSubscriptionSettings: () -> Unit,
    onExclusions: () -> Unit,
    onCheckUpdate: () -> Unit,
    onSettings: () -> Unit
) {
    RedesignedMainScreen(
        subscription = subscription,
        vpnRuntime = vpnRuntime,
        updating = updating,
        diagnosticResults = diagnosticResults,
        diagnosticRunning = diagnosticRunning,
        activeDiagnosticId = activeDiagnosticId,
        diagnosticRoute = diagnosticRoute,
        zapretAutoProgress = zapretAutoProgress,
        selectedBackend = selectedBackend,
        zapretPreset = zapretPreset,
        telegramCfEnabled = telegramCfEnabled,
        telegramCfDomain = telegramCfDomain,
        updateState = updateState,
        onRefresh = onRefresh,
        onSelect = onSelect,
        onToggleVpn = onToggleVpn,
        onSelectBackend = onSelectBackend,
        onSelectZapretPreset = onSelectZapretPreset,
        onRetryZapretAuto = onRetryZapretAuto,
        onRunDiagnostics = onRunDiagnostics,
        onCancelDiagnostics = onCancelDiagnostics,
        onApplyTelegram = onApplyTelegram,
        onDiagnostics = onDiagnostics,
        onOpenNetworkSettings = onOpenNetworkSettings,
        onOpenSubscriptionSettings = onOpenSubscriptionSettings,
        onExclusions = onExclusions,
        onCheckUpdate = onCheckUpdate,
        onSettings = onSettings,
        modifier = Modifier.weight(1f)
    )
}
