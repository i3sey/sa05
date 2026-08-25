package com.fife.sa05.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fife.sa05.AppRelease
import com.fife.sa05.AppUpdateCard
import com.fife.sa05.AppUpdateState
import com.fife.sa05.BuildConfig
import com.fife.sa05.components.DashboardRow
import com.fife.sa05.components.SectionTitle
import com.fife.sa05.InstalledApp
import com.fife.sa05.R
import com.fife.sa05.SubscriptionState
import com.fife.sa05.ui.theme.clickableScale
import com.fife.sa05.ui.theme.fadeTransform
import com.fife.sa05.ui.theme.motionEnabled
import com.fife.sa05.XrayConfig
import com.fife.sa05.XrayHost
import com.fife.sa05.VpnBackend
import com.fife.sa05.ZapretPreset
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ColumnScope.SettingsScreen(
    subscription: SubscriptionState,
    url: String,
    updating: Boolean,
    dynamicColor: Boolean,
    advancedModeEnabled: Boolean,
    updateState: AppUpdateState,
    canInstallPackages: Boolean,
    onBack: () -> Unit,
    onUrlChanged: (String) -> Unit,
    onUpdate: () -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAdvancedModeChanged: (Boolean) -> Unit,
    onAdvanced: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: (AppRelease) -> Unit,
    onInstallUpdate: (String) -> Unit,
    onOpenUnknownSources: () -> Unit
) {
    ContentScreen(title = "Настройки", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item { SectionTitle("Обновление приложения") }
            item {
                AppUpdateCard(
                    currentVersionName = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    updateState = updateState,
                    canInstallPackages = canInstallPackages,
                    onCheckUpdate = onCheckUpdate,
                    onDownloadUpdate = onDownloadUpdate,
                    onInstallUpdate = onInstallUpdate,
                    onOpenUnknownSources = onOpenUnknownSources
                )
            }

            item { SectionTitle("Подписка") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                subscription.title.ifBlank { "Активная подписка" },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Профилей: ${subscription.profiles.size} · Выбран: " +
                                    subscription.activeProfile?.remarks.orEmpty()
                                        .ifBlank { "нет" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedTextField(
                            value = url,
                            onValueChange = onUrlChanged,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("HTTPS-ссылка подписки") }
                        )
                        Button(
                            onClick = onUpdate,
                            enabled = !updating && url.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            val updateMotion = motionEnabled()
                            AnimatedContent(
                                targetState = updating,
                                transitionSpec = { fadeTransform(updateMotion) },
                                label = "updateLabel"
                            ) { loading ->
                                if (loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Обновить подписку")
                                }
                            }
                        }
                        HorizontalDivider()
                        Text(
                            "Последнее обновление: " + if (subscription.updatedAt > 0) {
                                DateFormat.getDateTimeInstance()
                                    .format(Date(subscription.updatedAt))
                            } else {
                                "никогда"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        subscription.updateIntervalHours?.let { hours ->
                            Text(
                                "Интервал провайдера: $hours ч",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (subscription.userInfo.isNotBlank()) {
                            Text(
                                "Трафик: ${subscription.userInfo}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { SectionTitle("Для опытных") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Показывать технические настройки")
                                Text(
                                    "Режимы обхода, ByeDPI, Cloudflare и ping хостов",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = advancedModeEnabled,
                                onCheckedChange = onAdvancedModeChanged
                            )
                        }
                        if (advancedModeEnabled) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            DashboardRow(
                                title = "Расширенные настройки",
                                subtitle = "Режимы, обход и Telegram",
                                onClick = onAdvanced
                            )
                        }
                    }
                }
            }

            item { SectionTitle("Оформление") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Material You",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Системные цвета обоев на Android 12+",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = dynamicColor,
                            onCheckedChange = onDynamicColorChanged
                        )
                    }
                }
            }

        }
    }
}

@Composable
internal fun ColumnScope.AdvancedSettingsScreen(
    selectedBackend: VpnBackend,
    zapretPreset: ZapretPreset,
    customZapretArguments: String,
    telegramCfEnabled: Boolean,
    telegramCfDomain: String,
    onBack: () -> Unit,
    onSelectBackend: (VpnBackend) -> Unit,
    onSelectZapretPreset: (ZapretPreset) -> Unit,
    onHosts: () -> Unit,
    onCustomZapretArgumentsChanged: (String) -> Unit,
    onSaveCustomZapretArguments: () -> Unit,
    onTelegramCfEnabledChanged: (Boolean) -> Unit,
    onTelegramCfDomainChanged: (String) -> Unit,
    onSaveTelegramCfDomain: () -> Unit
) {
    var backendPickerVisible by remember { mutableStateOf(false) }
    var presetPickerVisible by remember { mutableStateOf(false) }
    if (backendPickerVisible) {
        AlertDialog(
            onDismissRequest = { backendPickerVisible = false },
            title = { Text("Режим VPN") },
            text = {
                Column {
                    VpnBackend.selectable.forEach { backend ->
                        TextButton(
                            onClick = {
                                onSelectBackend(backend)
                                backendPickerVisible = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(backend.title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { }
        )
    }
    if (presetPickerVisible) {
        AlertDialog(
            onDismissRequest = { presetPickerVisible = false },
            title = { Text("Стратегия ByeDPI") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(ZapretPreset.selectable, key = { it.name }) { preset ->
                        TextButton(
                            onClick = {
                                onSelectZapretPreset(preset)
                                presetPickerVisible = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(preset.title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { }
        )
    }
    ContentScreen(title = "Расширенные", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { SectionTitle("Маршрут") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        DashboardRow(
                            title = "Режим VPN",
                            subtitle = selectedBackend.title,
                            onClick = { backendPickerVisible = true }
                        )
                        if (selectedBackend != VpnBackend.PROXY_ONLY) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            DashboardRow(
                                title = "Стратегия ByeDPI",
                                subtitle = zapretPreset.title,
                                onClick = { presetPickerVisible = true }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        DashboardRow(
                            title = "Хосты",
                            subtitle = "Проверка outbound-подключений",
                            onClick = onHosts
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Локальный обход", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Свои параметры используются только при выборе стратегии " +
                                "«Свои параметры».",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = customZapretArguments,
                            onValueChange = onCustomZapretArgumentsChanged,
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            label = { Text("Аргументы ByeDPI") }
                        )
                        OutlinedButton(onClick = onSaveCustomZapretArguments) {
                            Text("Сохранить")
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Telegram", style = MaterialTheme.typography.titleLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Cloudflare-маршрут")
                                Text(
                                    "WebSocket-маршрут к Telegram DC",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = telegramCfEnabled,
                                onCheckedChange = onTelegramCfEnabledChanged
                            )
                        }
                        OutlinedTextField(
                            value = telegramCfDomain,
                            onValueChange = onTelegramCfDomainChanged,
                            enabled = telegramCfEnabled,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Cloudflare-домен") }
                        )
                        OutlinedButton(
                            onClick = onSaveTelegramCfDomain,
                            enabled = telegramCfEnabled
                        ) {
                            Text("Сохранить")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.ContentScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Назад")
            }
        }
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        content()
    }
}

@Composable
internal fun HostPingList(
    config: String,
    results: Map<String, String>,
    activePing: String?,
    onPing: (XrayHost) -> Unit,
    isCdnProfile: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (isCdnProfile) {
        Column(modifier.padding(top = 8.dp)) {
            Text(
                "Пинг для CDN-туннеля недоступен: трафик идёт через Yandex Cloud CDN, " +
                    "а не через outbound провайдера."
            )
        }
        return
    }
    val parsed = remember(config) { runCatching { XrayConfig.extractHosts(config) } }
    Column(modifier.padding(top = 8.dp)) {
        Text("Проверка выполняется через протокол и настройки выбранного outbound.")
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val hosts = parsed.getOrNull()
        when {
            parsed.isFailure -> Text(
                parsed.exceptionOrNull()?.message ?: "Некорректный JSON",
                color = MaterialTheme.colorScheme.error
            )
            hosts.isNullOrEmpty() -> Text("Прокси-хосты в outbounds не найдены.")
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(hosts, key = { it.id }) { host ->
                    Card(modifier = Modifier.fillMaxWidth().animateItem()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${host.tag} · ${host.protocol}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            SelectionContainer {
                                Text(
                                    "${host.address}:${host.port}",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(results[host.id].orEmpty())
                                val pingMotion = motionEnabled()
                                Button(
                                    onClick = { onPing(host) },
                                    enabled = activePing != host.id
                                ) {
                                    AnimatedContent(
                                        targetState = activePing == host.id,
                                        transitionSpec = { fadeTransform(pingMotion) },
                                        label = "pingLabel"
                                    ) { pinging ->
                                        Text(if (pinging) "Пинг..." else "Пинг")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppExclusionList(
    apps: List<InstalledApp>,
    selected: Set<String>,
    suggested: Set<String>,
    vpnRunning: Boolean,
    onImportSuggested: () -> Unit,
    onToggle: (String) -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val visibleApps = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }
    Column(modifier.padding(top = 8.dp)) {
        Text(
            if (vpnRunning) {
                "Выбрано: ${selected.size}. Сохранённые изменения применятся после переподключения."
            } else {
                "Выбрано: ${selected.size}."
            }
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            singleLine = true,
            label = { Text("Поиск приложений") }
        )
        if (suggested.isNotEmpty() && !selected.containsAll(suggested)) {
            Button(
                onClick = onImportSuggested,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Добавить исключения подписки (${suggested.size})")
            }
        }
        if (vpnRunning) {
            OutlinedButton(
                onClick = onReconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Переподключить и применить")
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(visibleApps, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .clickableScale { onToggle(app.packageName) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = app.packageName in selected,
                        onCheckedChange = null
                    )
                    Column {
                        Text(app.label)
                        SelectionContainer {
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
