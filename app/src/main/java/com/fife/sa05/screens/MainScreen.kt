package com.fife.sa05.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fife.sa05.AppUpdateState
import com.fife.sa05.R
import com.fife.sa05.componentTrouble
import com.fife.sa05.ConnectionCheckState
import com.fife.sa05.formatBytes
import com.fife.sa05.formatUptime
import com.fife.sa05.VpnTraffic
import com.fife.sa05.SubscriptionProfile
import com.fife.sa05.SubscriptionState
import com.fife.sa05.TelegramProxyRunStatus
import com.fife.sa05.TelegramProxyRuntimeSnapshot
import com.fife.sa05.VpnComponentSnapshot
import com.fife.sa05.VpnComponentState
import com.fife.sa05.VpnPrimaryAction
import com.fife.sa05.VpnRunStatus
import com.fife.sa05.VpnRuntimeSnapshot
import com.fife.sa05.VpnSecondaryAction
import com.fife.sa05.VpnStatusPresentation
import com.fife.sa05.vpnStatusPresentation
import androidx.compose.ui.text.style.TextAlign
import com.fife.sa05.components.DashboardRow
import com.fife.sa05.components.FlagBadge
import com.fife.sa05.components.VpnHeroControl
import com.fife.sa05.vpnHeroState
import com.fife.sa05.parseServerRemark
import com.fife.sa05.ProfileExplainer
import com.fife.sa05.ProfilePing
import kotlinx.coroutines.delay
import com.fife.sa05.ui.theme.Space
import com.fife.sa05.ui.theme.fadeTransform
import com.fife.sa05.ui.theme.motionEnabled
import com.fife.sa05.ui.theme.pressScale
import com.fife.sa05.ui.theme.tabularFigures

private fun ConnectionCheckState.simpleText(): String = when (this) {
    ConnectionCheckState.Idle -> "Доступна после подключения VPN"
    ConnectionCheckState.Running -> "Проверяем связь…"
    is ConnectionCheckState.Passed -> result.delayMs?.let { "Пинг Google: $it мс" }
        ?: "Интернет доступен"
    is ConnectionCheckState.Failed -> "Связь не подтвердилась — откройте проверку"
}

private fun ConnectionCheckState.pingText(): String? = when (this) {
    is ConnectionCheckState.Passed -> result.delayMs?.let { "Пинг: $it мс" }
    else -> null
}

private fun TelegramProxyRuntimeSnapshot.title(): String = when (status) {
    TelegramProxyRunStatus.STOPPED -> "Только Telegram"
    TelegramProxyRunStatus.STARTING -> "Подключаем Telegram…"
    TelegramProxyRunStatus.RUNNING -> "Telegram подключён"
    TelegramProxyRunStatus.ERROR -> "Telegram не подключён"
}

private fun TelegramProxyRuntimeSnapshot.description(): String = when (status) {
    TelegramProxyRunStatus.STOPPED ->
        "Откроем Telegram даже без VPN и подписки"
    TelegramProxyRunStatus.STARTING -> message.ifBlank { "Запускаем прокси для Telegram" }
    TelegramProxyRunStatus.RUNNING -> "Telegram идёт через локальный прокси"
    TelegramProxyRunStatus.ERROR -> message.ifBlank { "Попробуйте ещё раз" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.MainScreen(
    subscription: SubscriptionState,
    vpnRuntime: VpnRuntimeSnapshot,
    connectionCheck: ConnectionCheckState,
    telegramRuntime: TelegramProxyRuntimeSnapshot,
    updateState: AppUpdateState,
    traffic: VpnTraffic,
    advancedModeEnabled: Boolean,
    pings: Map<String, ProfilePing>,
    refreshing: Boolean,
    onRefreshSubscription: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onPingProfile: (SubscriptionProfile) -> Unit,
    onToggleVpn: () -> Unit,
    onStartTelegram: () -> Unit,
    onStopTelegram: () -> Unit,
    onOpenTelegram: () -> Unit,
    onDiagnostics: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onOpenSubscriptionSettings: () -> Unit,
    onExclusions: () -> Unit,
    onOpenUpdate: () -> Unit,
    onSettings: () -> Unit
) {
    val presentation = remember(vpnRuntime) { vpnStatusPresentation(vpnRuntime) }
    var profileSheetVisible by remember { mutableStateOf(false) }
    var expandedProfileId by remember { mutableStateOf<String?>(null) }
    val profileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (profileSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { profileSheetVisible = false },
            sheetState = profileSheetState
        ) {
            Text(
                "Выберите сервер",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
            if (subscription.profiles.isEmpty()) {
                // An empty sheet reads as a bug; say what happened and what to do about it.
                Text(
                    "Провайдер не прислал ни одного сервера. Обновите подписку в настройках или " +
                        "проверьте ссылку у провайдера.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = 24.dp,
                        vertical = Space.Content
                    )
                )
            }
            LazyColumn {
                items(subscription.profiles, key = { it.id }) { profile ->
                    ProfileChoice(
                        profile = profile,
                        selected = profile.id == subscription.activeProfile?.id,
                        expanded = expandedProfileId == profile.id,
                        ping = pings[profile.id],
                        onClick = {
                            onSelectProfile(profile.id)
                            profileSheetVisible = false
                        },
                        onToggleDetails = {
                            expandedProfileId = profile.id.takeIf { it != expandedProfileId }
                        },
                        onPing = { onPingProfile(profile) }
                    )
                }
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefreshSubscription,
        modifier = Modifier.fillMaxSize()
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Space.Item),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Space.Content,
            end = Space.Content,
            bottom = Space.ScrollBottom
        )
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text("SA05")
                        subscription.title.takeIf(String::isNotBlank)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Настройки")
                    }
                }
            )
        }
        item {
            VpnStatusCard(
                runtime = vpnRuntime,
                presentation = presentation,
                description = connectionCheck.pingText() ?: presentation.description,
                traffic = traffic,
                advancedModeEnabled = advancedModeEnabled,
                onToggleVpn = onToggleVpn,
                onOpenSubscriptionSettings = onOpenSubscriptionSettings,
                onOpenNetworkSettings = onOpenNetworkSettings,
                onChangeProfile = { profileSheetVisible = true },
                onDiagnostics = onDiagnostics
            )
        }
        if (updateState is AppUpdateState.Available) {
            item {
                UpdateBanner(
                    versionName = updateState.release.versionName,
                    onClick = onOpenUpdate
                )
            }
        }
        // Three navigational rows that all answer "how is this VPN set up" belong in one
        // group; as separate cards they read as three unrelated decisions.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    val activeRemark = parseServerRemark(
                        subscription.activeProfile?.remarks.orEmpty()
                    )
                    DashboardRow(
                        title = "Сервер",
                        subtitle = activeRemark.name.ifBlank { "Сервер не выбран" },
                        trailingFlag = activeRemark.flag,
                        onClick = { profileSheetVisible = true }
                    )
                    RowDivider()
                    DashboardRow(
                        title = "Исключения приложений",
                        subtitle = "Пойдут в обход VPN, напрямую",
                        onClick = onExclusions
                    )
                    RowDivider()
                    DashboardRow(
                        title = "Проверка подключения",
                        subtitle = connectionCheck.simpleText(),
                        onClick = onDiagnostics
                    )
                    if (vpnRuntime.status == VpnRunStatus.CONNECTED &&
                        connectionCheck is ConnectionCheckState.Failed
                    ) {
                        Text(
                            "Откроем список сайтов и покажем, что именно не отвечает.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = Space.Content,
                                end = Space.Content,
                                bottom = Space.Item
                            )
                        )
                    }
                }
            }
        }
        item {
            TelegramCard(
                runtime = telegramRuntime,
                onStart = onStartTelegram,
                onStop = onStopTelegram,
                onOpen = onOpenTelegram
            )
        }
    }
    }
}

/**
 * An available update is worth mentioning, not worth shouting: on primaryContainer it was the
 * loudest thing on the screen after the connect control, which put "there is a new build" on a
 * par with "your VPN is off". An outlined card states it without competing.
 */
@Composable
private fun UpdateBanner(versionName: String, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(Space.Content),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Item)
        ) {
            Icon(
                painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f)) {
                Text("Доступно обновление", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Версия $versionName готова к установке",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Открыть",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Inset so the rule separates the rows' text, not the card's edges. */
@Composable
private fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = Space.Content))
}

@Composable
private fun ProfileChoice(
    profile: SubscriptionProfile,
    selected: Boolean,
    expanded: Boolean,
    ping: ProfilePing?,
    onClick: () -> Unit,
    onToggleDetails: () -> Unit,
    onPing: () -> Unit
) {
    val remark = parseServerRemark(profile.remarks)
    Column {
        ListItem(
            headlineContent = { Text(remark.name.ifBlank { "Сервер" }) },
            supportingContent = {
                val label = ping.label()
                Text(
                    label ?: if (selected) "Выбран" else profile.remarks,
                    style = if (label != null && ping is ProfilePing.Success) {
                        MaterialTheme.typography.bodyMedium.tabularFigures()
                    } else {
                        MaterialTheme.typography.bodyMedium
                    }
                )
            },
            leadingContent = {
                if (selected) Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    remark.flag?.let { FlagBadge(it) }
                    TextButton(onClick = onToggleDetails) {
                        Text(if (expanded) "Скрыть" else "Подробнее")
                    }
                }
            },
            modifier = Modifier.clickable(onClick = onClick),
            colors = androidx.compose.material3.ListItemDefaults.colors(
                containerColor = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                } else {
                    Color.Transparent
                }
            )
        )
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProfileExplainer(profile)
                OutlinedButton(
                    onClick = onPing,
                    enabled = ping !is ProfilePing.Running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (ping is ProfilePing.Running) "Измеряем…" else "Измерить задержку")
                }
            }
        }
    }
}

private fun ProfilePing?.label(): String? = when (this) {
    null -> null
    ProfilePing.Running -> "Измеряем задержку…"
    is ProfilePing.Success -> "Задержка: $delayMs мс"
    is ProfilePing.Failure -> "Не отвечает: $message"
}

@Composable
private fun VpnStatusCard(
    runtime: VpnRuntimeSnapshot,
    presentation: VpnStatusPresentation,
    description: String,
    traffic: VpnTraffic,
    advancedModeEnabled: Boolean,
    onToggleVpn: () -> Unit,
    onOpenSubscriptionSettings: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onChangeProfile: () -> Unit,
    onDiagnostics: () -> Unit
) {
    val primaryAction = presentation.primaryAction
    val haptics = LocalHapticFeedback.current
    // Reaching a terminal state is worth a nudge; intermediate ticks are not.
    LaunchedEffect(runtime.status) {
        when (runtime.status) {
            VpnRunStatus.CONNECTED -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            VpnRunStatus.ERROR -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            else -> Unit
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        VpnHeroControl(
            state = vpnHeroState(runtime.status),
            actionLabel = when (primaryAction) {
                VpnPrimaryAction.CONNECT -> "Подключить VPN"
                VpnPrimaryAction.STOP -> "Отключить VPN"
                VpnPrimaryAction.RETRY -> "Повторить подключение"
                VpnPrimaryAction.OPEN_SUBSCRIPTION -> "Настроить подписку"
            },
            // The circle's colour and its label already say what state the VPN is in, so
            // prepending the title here only repeated it: "VPN включён · … · VPN включён, но…".
            statusDescription = description.ifBlank { presentation.title },
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                if (primaryAction == VpnPrimaryAction.OPEN_SUBSCRIPTION) {
                    onOpenSubscriptionSettings()
                } else {
                    onToggleVpn()
                }
            }
        )
        if (runtime.status == VpnRunStatus.CONNECTED && runtime.connectedAtMillis > 0L) {
            VpnUptime(
                connectedAtMillis = runtime.connectedAtMillis,
                traffic = traffic,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        componentTrouble(runtime.components)?.let { trouble ->
            Text(
                trouble,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        if (advancedModeEnabled && runtime.components.isNotEmpty()) {
            ComponentChips(runtime.components)
        }
        presentation.secondaryActions.forEach { action ->
            OutlinedButton(
                onClick = when (action) {
                    VpnSecondaryAction.NETWORK_SETTINGS -> onOpenNetworkSettings
                    VpnSecondaryAction.CHANGE_PROFILE,
                    VpnSecondaryAction.CHANGE_STRATEGY -> onChangeProfile
                    VpnSecondaryAction.DIAGNOSTICS -> onDiagnostics
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when (action) {
                        VpnSecondaryAction.NETWORK_SETTINGS -> "Настройки сети"
                        VpnSecondaryAction.CHANGE_PROFILE -> "Выбрать другой сервер"
                        VpnSecondaryAction.CHANGE_STRATEGY -> "Сменить стратегию обхода"
                        VpnSecondaryAction.DIAGNOSTICS -> "Открыть проверку"
                    }
                )
            }
        }
    }
}

/** Ticks once a second so the connected time stays honest without any service-side work. */
@Composable
private fun VpnUptime(connectedAtMillis: Long, traffic: VpnTraffic, color: Color) {
    var now by remember(connectedAtMillis) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Text(
        "На связи ${formatUptime(now - connectedAtMillis)} · " +
            "↓ ${formatBytes(traffic.rxBytes)} ↑ ${formatBytes(traffic.txBytes)}",
        // Reticks every second; proportional digits would make it wobble in place.
        style = MaterialTheme.typography.bodySmall.tabularFigures(),
        color = color
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentChips(components: List<VpnComponentSnapshot>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        components.forEach { snapshot ->
            val tint = when (snapshot.state) {
                VpnComponentState.RUNNING -> MaterialTheme.colorScheme.primary
                VpnComponentState.FALLBACK -> MaterialTheme.colorScheme.tertiary
                VpnComponentState.FAILED -> MaterialTheme.colorScheme.error
                VpnComponentState.STARTING -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(snapshot.component.title) },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(tint, CircleShape)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun TelegramCard(
    runtime: TelegramProxyRuntimeSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(runtime.title(), style = MaterialTheme.typography.titleMedium)
            Text(
                runtime.description(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (runtime.status) {
                TelegramProxyRunStatus.RUNNING -> {
                    FilledTonalButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                        Text("Открыть Telegram")
                    }
                    TextButton(onClick = onStop, modifier = Modifier.align(Alignment.End)) {
                        Text("Отключить Telegram")
                    }
                }
                TelegramProxyRunStatus.STARTING -> {
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("Подключаем Telegram…")
                    }
                }
                else -> Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text(if (runtime.status == TelegramProxyRunStatus.ERROR) {
                        "Попробовать снова"
                    } else {
                        "Включить Telegram"
                    })
                }
            }
        }
    }
}
