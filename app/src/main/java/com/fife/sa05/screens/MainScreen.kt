package com.fife.sa05.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fife.sa05.AppUpdateState
import com.fife.sa05.ConnectionCheckState
import com.fife.sa05.SubscriptionProfile
import com.fife.sa05.SubscriptionState
import com.fife.sa05.TelegramProxyRunStatus
import com.fife.sa05.TelegramProxyRuntimeSnapshot
import com.fife.sa05.VpnPrimaryAction
import com.fife.sa05.VpnRunStatus
import com.fife.sa05.VpnRuntimeSnapshot
import com.fife.sa05.VpnSecondaryAction
import com.fife.sa05.vpnStatusPresentation
import com.fife.sa05.components.DashboardRow
import com.fife.sa05.parseServerRemark
import com.fife.sa05.ui.theme.fadeTransform
import com.fife.sa05.ui.theme.motionEnabled
import com.fife.sa05.ui.theme.pressScale

private fun ConnectionCheckState.simpleText(): String = when (this) {
    ConnectionCheckState.Idle -> "Проверка доступна после подключения"
    ConnectionCheckState.Running -> "Проверяем доступ через VPN…"
    is ConnectionCheckState.Passed -> result.delayMs?.let { "Пинг Google: $it мс" }
        ?: "Интернет доступен"
    is ConnectionCheckState.Failed -> "Интернет ещё не подтверждён"
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
        "Откроем Telegram через локальный прокси, без VPN и подписки"
    TelegramProxyRunStatus.STARTING -> message.ifBlank { "Запускаем локальный прокси" }
    TelegramProxyRunStatus.RUNNING -> "Работает локальный Telegram Proxy"
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
    onSelectProfile: (String) -> Unit,
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
            LazyColumn {
                items(subscription.profiles, key = { it.id }) { profile ->
                    ProfileChoice(
                        profile = profile,
                        selected = profile.id == subscription.activeProfile?.id,
                        onClick = {
                            onSelectProfile(profile.id)
                            profileSheetVisible = false
                        }
                    )
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 28.dp
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
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        }
        item {
            VpnStatusCard(
                runtime = vpnRuntime,
                primaryAction = presentation.primaryAction,
                title = presentation.title,
                description = connectionCheck.pingText() ?: presentation.description,
                onToggleVpn = onToggleVpn,
                onOpenSubscriptionSettings = onOpenSubscriptionSettings,
                onOpenNetworkSettings = onOpenNetworkSettings
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
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    DashboardRow(
                        title = "Сервер",
                        subtitle = parseServerRemark(subscription.activeProfile?.remarks.orEmpty())
                            .name.ifBlank { "Сервер не выбран" },
                        onClick = { profileSheetVisible = true }
                    )
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    DashboardRow(
                        title = "Исключения приложений",
                        subtitle = "Приложения с прямым доступом",
                        onClick = onExclusions
                    )
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
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    DashboardRow(
                        title = "Проверка подключения",
                        subtitle = connectionCheck.simpleText(),
                        onClick = onDiagnostics
                    )
                    if (vpnRuntime.status == VpnRunStatus.CONNECTED &&
                        connectionCheck is ConnectionCheckState.Failed
                    ) {
                        Text(
                            "Откройте проверку, чтобы узнать, что именно не работает.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(versionName: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text("Доступно обновление", style = MaterialTheme.typography.titleMedium)
                Text("Версия $versionName готова к установке")
            }
            Text("Открыть", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ProfileChoice(
    profile: SubscriptionProfile,
    selected: Boolean,
    onClick: () -> Unit
) {
    val remark = parseServerRemark(profile.remarks)
    ListItem(
        headlineContent = { Text(remark.name.ifBlank { "Сервер" }) },
        supportingContent = {
            Text(if (selected) "Выбран" else profile.remarks)
        },
        leadingContent = {
            if (selected) Icon(Icons.Default.CheckCircle, contentDescription = null)
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
}

@Composable
private fun VpnStatusCard(
    runtime: VpnRuntimeSnapshot,
    primaryAction: VpnPrimaryAction,
    title: String,
    description: String,
    onToggleVpn: () -> Unit,
    onOpenSubscriptionSettings: () -> Unit,
    onOpenNetworkSettings: () -> Unit
) {
    val container = when (runtime.status) {
        VpnRunStatus.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
        VpnRunStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (runtime.status) {
        VpnRunStatus.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer
        VpnRunStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (runtime.status == VpnRunStatus.CONNECTING ||
                    runtime.status == VpnRunStatus.RECOVERING
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(32.dp), color = content)
                } else {
                    Icon(
                        if (runtime.status == VpnRunStatus.CONNECTED) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.PowerSettingsNew
                        },
                        contentDescription = null,
                        tint = content
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = content)
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = content)
                }
            }
            val interaction = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    if (primaryAction == VpnPrimaryAction.OPEN_SUBSCRIPTION) {
                        onOpenSubscriptionSettings()
                    } else {
                        onToggleVpn()
                    }
                },
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().height(56.dp).pressScale(interaction)
            ) {
                val motion = motionEnabled()
                AnimatedContent(
                    targetState = primaryAction,
                    transitionSpec = { fadeTransform(motion) },
                    label = "vpnPrimaryAction"
                ) { action ->
                    Text(
                        when (action) {
                            VpnPrimaryAction.CONNECT -> "Подключить VPN"
                            VpnPrimaryAction.STOP -> "Отключить VPN"
                            VpnPrimaryAction.RETRY -> "Повторить подключение"
                            VpnPrimaryAction.OPEN_SUBSCRIPTION -> "Настроить подписку"
                        }
                    )
                }
            }
            if (runtime.status == VpnRunStatus.WAITING_FOR_NETWORK) {
                OutlinedButton(onClick = onOpenNetworkSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Настройки сети")
                }
            }
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
