package com.fife.sa05.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fife.sa05.AppRelease
import com.fife.sa05.AppUpdateCard
import com.fife.sa05.AppUpdateState
import com.fife.sa05.BuildConfig
import com.fife.sa05.components.DashboardRow
import com.fife.sa05.components.QrCode
import com.fife.sa05.components.SectionTitle
import com.fife.sa05.LAN_PROXY_PORT
import com.fife.sa05.LAN_PROXY_USER
import com.fife.sa05.InstalledApp
import com.fife.sa05.R
import com.fife.sa05.StrategyExplainer
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
                                "Цвета из обоев",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (dynamicColor) {
                                    "Приложение подстраивается под обои. Android 12 и новее."
                                } else {
                                    "Используется базовая палитра Material."
                                },
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
    allowIpv6Bypass: Boolean,
    telegramCfEnabled: Boolean,
    telegramCfDomain: String,
    strategyMemoryCount: Int,
    lanSharingEnabled: Boolean,
    lanShareUri: String?,
    lanShareAddress: String?,
    lanSharePassword: String,
    onLanSharingChanged: (Boolean) -> Unit,
    onRotateLanPassword: () -> Unit,
    onCopyLanUri: () -> Unit,
    onBack: () -> Unit,
    onSelectBackend: (VpnBackend) -> Unit,
    onSelectZapretPreset: (ZapretPreset) -> Unit,
    onAllowIpv6BypassChanged: (Boolean) -> Unit,
    onExportStrategies: () -> Unit,
    onImportStrategies: () -> Unit,
    onClearStrategies: () -> Unit,
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
                    VpnBackend.entries.forEach { backend ->
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
                var explainedPreset by remember { mutableStateOf(zapretPreset) }
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(ZapretPreset.selectable, key = { it.name }) { preset ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        onSelectZapretPreset(preset)
                                        presetPickerVisible = false
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(preset.title, modifier = Modifier.fillMaxWidth())
                                }
                                TextButton(
                                    onClick = {
                                        explainedPreset = preset.takeIf { it != explainedPreset }
                                            ?: zapretPreset
                                    }
                                ) {
                                    Text(if (explainedPreset == preset) "Скрыть" else "Что это")
                                }
                            }
                            AnimatedVisibility(visible = explainedPreset == preset) {
                                StrategyExplainer(preset, Modifier.padding(bottom = 8.dp))
                            }
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Пропускать IPv6 мимо VPN",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (allowIpv6Bypass) {
                                    "IPv6 идёт напрямую: реальный адрес виден сайтам, " +
                                        "блокировки по IPv6 не обходятся. Включайте только " +
                                        "если без этого сеть не работает."
                                } else {
                                    "IPv6 закрыт туннелем, приложения переходят на IPv4. " +
                                        "Включите, если у оператора сеть только на IPv6."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (allowIpv6Bypass) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Switch(
                            checked = allowIpv6Bypass,
                            onCheckedChange = onAllowIpv6BypassChanged
                        )
                    }
                }
            }
            item { SectionTitle("Раздача обхода") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Раздавать по Wi-Fi",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "Телевизор, ноутбук или приставка в той же сети смогут " +
                                        "ходить через ваш туннель.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = lanSharingEnabled,
                                onCheckedChange = onLanSharingChanged
                            )
                        }
                        if (lanSharingEnabled) {
                            Text(
                                "Порт открыт для всех, кто уже в этой сети. В публичном Wi-Fi " +
                                    "не включайте. Доступ закрыт паролем, трафик гостей идёт " +
                                    "через вашу подписку и расходует ваш трафик и батарею.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            HorizontalDivider()
                            if (lanShareUri == null || lanShareAddress == null) {
                                Text(
                                    "Локальная сеть не найдена. Подключитесь к Wi-Fi или " +
                                        "включите точку доступа — адрес появится здесь.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text("Адрес: $lanShareAddress:$LAN_PROXY_PORT")
                                Text("Логин: $LAN_PROXY_USER")
                                SelectionContainer {
                                    Text(
                                        "Пароль: $lanSharePassword",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    QrCode(
                                        content = lanShareUri,
                                        modifier = Modifier.size(200.dp)
                                    )
                                }
                                Text(
                                    "Изменения применяются при следующем подключении VPN.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = onCopyLanUri) { Text("Копировать") }
                                    TextButton(onClick = onRotateLanPassword) {
                                        Text("Новый пароль")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Стратегии") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Запомненные стратегии",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (strategyMemoryCount == 0) {
                                "Приложение ещё не запоминало, какой обход работает в ваших " +
                                    "сетях. Записи появятся после успешного автоподбора."
                            } else {
                                "Записей: $strategyMemoryCount. Привязаны к оператору и набору " +
                                    "блокировок, а не к конкретному подключению, поэтому " +
                                    "переживают переподключение."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Файл можно передать другому человеку с тем же оператором. " +
                                "Импорт не перетирает то, что подтвердил ваш телефон, и не " +
                                "содержит ни ссылки подписки, ни адресов серверов.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onExportStrategies,
                                enabled = strategyMemoryCount > 0
                            ) { Text("Экспорт") }
                            OutlinedButton(onClick = onImportStrategies) { Text("Импорт") }
                            if (strategyMemoryCount > 0) {
                                TextButton(onClick = onClearStrategies) { Text("Очистить") }
                            }
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
                        Text("Локальный обход", style = MaterialTheme.typography.titleMedium)
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
                        Text("Telegram", style = MaterialTheme.typography.titleMedium)
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
    modifier: Modifier = Modifier
) {
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

/**
 * Loads one launcher icon off the main thread. Only rows the user actually scrolls to pay for it,
 * because `LazyColumn` composes them on demand.
 */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = ICON_PX, height = ICON_PX)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .size(32.dp),
        contentAlignment = Alignment.Center
    ) {
        icon?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(32.dp))
        }
    }
}

private const val ICON_PX = 96

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
                    AppIcon(app.packageName)
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
