package com.fife.sa05

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Sa05Theme(dynamicColor = false) {
                var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
                LaunchedEffect(Unit) {
                    apps = withContext(Dispatchers.IO) { loadLaunchableApps() }
                }
                XrayScreen(
                    apps = apps,
                    requestStart = { requestVpnAndStart() }
                )
            }
        }
    }

    private fun requestVpnAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            XrayVpnService.start(this)
        } else {
            vpnPermission.launch(prepareIntent)
        }
    }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) XrayVpnService.start(this)
        }

    private fun loadLaunchableApps(): List<InstalledApp> {
        @Suppress("DEPRECATION")
        val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        return installed
            .asSequence()
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
private fun XrayScreen(apps: List<InstalledApp>, requestStart: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var config by remember { mutableStateOf(XrayPreferences.config(context)) }
    var selected by remember { mutableStateOf(XrayPreferences.excludedApps(context)) }
    var tab by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var pingResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activePing by remember { mutableStateOf<String?>(null) }
    var pingJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val pingEngine = remember { XrayPingEngine(context.applicationContext) }
    val state by XrayVpnService.state.collectAsState()
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
            Text("SA05 XRAY", style = MaterialTheme.typography.headlineMedium)
            Text(state, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    try {
                        XrayConfig.validate(config)
                        XrayPreferences.saveConfig(context, config)
                        XrayPreferences.saveExcludedApps(context, selected)
                        message = "Конфигурация сохранена"
                    } catch (e: Exception) {
                        message = e.message ?: "Некорректный JSON"
                    }
                }) { Text("Сохранить") }
                Button(
                    onClick = {
                        XrayPreferences.saveConfig(context, config)
                        XrayPreferences.saveExcludedApps(context, selected)
                        requestStart()
                    },
                    enabled = state != XrayVpnService.STATE_CONNECTING &&
                        state != XrayVpnService.STATE_CONNECTED
                ) { Text("Подключить") }
                Button(
                    onClick = { XrayVpnService.stop(context) },
                    enabled = state != XrayVpnService.STATE_DISCONNECTED
                ) { Text("Стоп") }
            }
            if (message.isNotBlank()) {
                Text(message, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text("JSON") })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Исключения") })
                Tab(tab == 2, onClick = { tab = 2 }, text = { Text("Хосты") })
            }
            when (tab) {
                0 -> OutlinedTextField(
                    value = config,
                    onValueChange = { config = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("Полная Xray JSON-конфигурация") }
                )
                1 -> AppExclusionList(
                    apps = apps,
                    selected = selected,
                    onToggle = { pkg ->
                        selected = if (pkg in selected) selected - pkg else selected + pkg
                    },
                    modifier = Modifier.weight(1f)
                )
                else -> HostPingList(
                    config = config,
                    results = pingResults,
                    activePing = activePing,
                    onPing = { host ->
                        pingEngine.cancel()
                        pingJob?.cancel()
                        activePing = host.id
                        pingResults = pingResults + (host.id to "Проверка...")
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
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(top = 8.dp)) {
        Text("Отмеченные приложения идут напрямую, вне VPN.")
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
