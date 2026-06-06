package com.fife.sa05

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fife.sa05.ui.theme.Sa05Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

data class InstalledApp(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_REQUEST_VPN = "request_vpn"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var dynamicColor by remember {
                mutableStateOf(XrayPreferences.dynamicColor(this))
            }
            Sa05Theme(dynamicColor = dynamicColor) {
                var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
                LaunchedEffect(Unit) {
                    apps = withContext(Dispatchers.IO) { loadLaunchableApps() }
                }
                XrayScreen(
                    apps = apps,
                    dynamicColor = dynamicColor,
                    onDynamicColorChanged = {
                        dynamicColor = it
                        XrayPreferences.saveDynamicColor(this, it)
                    },
                    onAddTile = { requestAddTile() },
                    requestStart = { requestVpnAndStart() }
                )
            }
        }
        consumeVpnRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeVpnRequest(intent)
    }

    private fun requestVpnAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestVpnPermission()
    }

    private fun requestVpnPermission() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) XrayVpnService.start(this)
        else vpnPermission.launch(prepareIntent)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestVpnPermission()
        }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) XrayVpnService.start(this)
        }

    private fun consumeVpnRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_VPN, false) != true) return
        intent.removeExtra(EXTRA_REQUEST_VPN)
        window.decorView.post { requestVpnAndStart() }
    }

    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = getSystemService(StatusBarManager::class.java)
        manager.requestAddTileService(
            ComponentName(this, VpnQuickSettingsTile::class.java),
            "SA05 Xray",
            Icon.createWithResource(this, R.drawable.ic_vpn_tile),
            mainExecutor
        ) {
            VpnRuntimeState.requestTileRefresh(this)
        }
    }

    private fun loadLaunchableApps(): List<InstalledApp> {
        @Suppress("DEPRECATION")
        val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        return installed.asSequence()
            .filter { it.packageName != packageName }
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                InstalledApp(
                    label = packageManager.getApplicationLabel(it).toString(),
                    packageName = it.packageName
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}

@Composable
private fun XrayScreen(
    apps: List<InstalledApp>,
    dynamicColor: Boolean,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAddTile: () -> Unit,
    requestStart: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { SubscriptionRepository(context.applicationContext) }
    var subscription by remember { mutableStateOf(repository.load()) }
    var urlDraft by remember { mutableStateOf(subscription.url) }
    var selectedApps by remember { mutableStateOf(XrayPreferences.excludedApps(context)) }
    var tab by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }
    var pingResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activePing by remember { mutableStateOf<String?>(null) }
    var pingJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val pingEngine = remember { XrayPingEngine(context.applicationContext) }
    val vpnState by XrayVpnService.state.collectAsState()

    fun updateSubscription(url: String) {
        if (updating) return
        updating = true
        message = "Обновление подписки..."
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.update(url) }
                subscription = when (result) {
                    is SubscriptionUpdateResult.Updated -> result.state
                    is SubscriptionUpdateResult.NotModified -> result.state
                }
                urlDraft = subscription.url
                pingResults = emptyMap()
                message = when (result) {
                    is SubscriptionUpdateResult.Updated ->
                        "Загружено профилей: ${subscription.profiles.size}"
                    is SubscriptionUpdateResult.NotModified -> "Подписка не изменилась"
                }
            } catch (e: Exception) {
                message = "Ошибка обновления: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                updating = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (subscription.url.isNotBlank()) updateSubscription(subscription.url)
    }
    DisposableEffect(pingEngine) {
        onDispose { pingEngine.cancel() }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                subscription.title.ifBlank { "SA05 XRAY" },
                style = MaterialTheme.typography.headlineMedium
            )
            Text(vpnState, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = requestStart,
                    enabled = vpnState != XrayVpnService.STATE_CONNECTING &&
                        vpnState != XrayVpnService.STATE_CONNECTED
                ) { Text("Подключить") }
                Button(
                    onClick = { XrayVpnService.stop(context) },
                    enabled = vpnState != XrayVpnService.STATE_DISCONNECTED
                ) { Text("Стоп") }
            }
            if (message.isNotBlank()) {
                Text(message, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                listOf("Профили", "Хосты", "Исключения", "Настройки").forEachIndexed {
                        index, title ->
                    Tab(tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> ProfilesTab(
                    subscription = subscription,
                    url = urlDraft,
                    updating = updating,
                    onUrlChanged = { urlDraft = it },
                    onUpdate = { updateSubscription(urlDraft) },
                    onSelect = { id ->
                        subscription = repository.setActiveProfile(id)
                        pingResults = emptyMap()
                    },
                    modifier = Modifier.weight(1f)
                )
                1 -> HostPingList(
                    config = subscription.activeProfile?.json
                        ?: XrayPreferences.config(context),
                    results = pingResults,
                    activePing = activePing,
                    onPing = { host ->
                        pingEngine.cancel()
                        pingJob?.cancel()
                        activePing = host.id
                        pingResults = pingResults + (host.id to "Проверка...")
                        val config = subscription.activeProfile?.json
                            ?: XrayPreferences.config(context)
                        pingJob = scope.launch {
                            try {
                                val delayMs = pingEngine.ping(config, host)
                                pingResults = pingResults + (host.id to "$delayMs мс")
                            } catch (e: CancellationException) {
                                pingResults = pingResults + (host.id to "Отменено")
                                throw e
                            } catch (e: Exception) {
                                pingResults = pingResults + (
                                    host.id to "Ошибка: ${e.message ?: e.javaClass.simpleName}"
                                )
                            } finally {
                                if (activePing == host.id) activePing = null
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                2 -> AppExclusionList(
                    apps = apps,
                    selected = selectedApps,
                    suggested = subscription.suggestedBypassApps,
                    onImportSuggested = {
                        selectedApps += subscription.suggestedBypassApps
                        XrayPreferences.saveExcludedApps(context, selectedApps)
                        message = "Исключения подписки добавлены"
                    },
                    onToggle = { pkg ->
                        selectedApps = if (pkg in selectedApps) {
                            selectedApps - pkg
                        } else {
                            selectedApps + pkg
                        }
                        XrayPreferences.saveExcludedApps(context, selectedApps)
                    },
                    modifier = Modifier.weight(1f)
                )
                else -> SettingsTab(
                    subscription = subscription,
                    dynamicColor = dynamicColor,
                    onDynamicColorChanged = onDynamicColorChanged,
                    onAddTile = onAddTile,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ProfilesTab(
    subscription: SubscriptionState,
    url: String,
    updating: Boolean,
    onUrlChanged: (String) -> Unit,
    onUpdate: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(top = 10.dp)) {
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
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text(if (updating) "Обновление..." else "Обновить")
        }
        if (subscription.profiles.isEmpty()) {
            Text("Добавьте ссылку. До первого импорта используется локальный конфиг.")
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(subscription.profiles, key = { it.id }) { profile ->
                val selected = profile.id == subscription.activeProfile?.id
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelect(profile.id) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.remarks, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (selected) "Активный профиль" else "Нажмите для выбора",
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (selected) Text("●", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun HostPingList(
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
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hosts, key = { it.id }) { host ->
                    Card(modifier = Modifier.fillMaxWidth()) {
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
                                Button(
                                    onClick = { onPing(host) },
                                    enabled = activePing != host.id
                                ) {
                                    Text(if (activePing == host.id) "Пинг..." else "Пинг")
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
private fun AppExclusionList(
    apps: List<InstalledApp>,
    selected: Set<String>,
    suggested: Set<String>,
    onImportSuggested: () -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(top = 8.dp)) {
        Text("Отмеченные приложения идут напрямую, вне VPN.")
        if (suggested.isNotEmpty() && !selected.containsAll(suggested)) {
            Button(
                onClick = onImportSuggested,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Добавить исключения подписки (${suggested.size})")
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyColumn {
            items(apps, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = app.packageName in selected,
                        onCheckedChange = { onToggle(app.packageName) }
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

@Composable
private fun SettingsTab(
    subscription: SubscriptionState,
    dynamicColor: Boolean,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAddTile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Material You", style = MaterialTheme.typography.titleMedium)
                Text("Системные цвета обоев на Android 12+")
            }
            Switch(checked = dynamicColor, onCheckedChange = onDynamicColorChanged)
        }
        HorizontalDivider()
        Text("Кнопка в быстрых настройках", style = MaterialTheme.typography.titleMedium)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Button(onClick = onAddTile) {
                Text("Добавить в шторку")
            }
        } else {
            Text("Откройте редактирование быстрых настроек и добавьте плитку SA05 Xray.")
        }
        HorizontalDivider()
        Text(
            "Последнее обновление: " + if (subscription.updatedAt > 0) {
                DateFormat.getDateTimeInstance().format(Date(subscription.updatedAt))
            } else {
                "никогда"
            }
        )
        if (subscription.updateIntervalHours != null) {
            Text("Интервал провайдера: ${subscription.updateIntervalHours} ч")
        }
        if (subscription.userInfo.isNotBlank()) {
            Text("Трафик: ${subscription.userInfo}")
        }
        Text("Профилей: ${subscription.profiles.size}")
    }
}
