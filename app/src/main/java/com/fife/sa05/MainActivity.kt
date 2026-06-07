package com.fife.sa05

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fife.sa05.ui.theme.Sa05Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

data class InstalledApp(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_REQUEST_VPN = "request_vpn"
    }

    private val pendingSubscriptionUrl = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SubscriptionDeepLink.parse(intent?.dataString)?.let {
            intent?.data = null
            pendingSubscriptionUrl.value = it
        }
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
                    subscriptionImport = pendingSubscriptionUrl,
                    onSubscriptionImportConsumed = { pendingSubscriptionUrl.value = null },
                    onDynamicColorChanged = {
                        dynamicColor = it
                        XrayPreferences.saveDynamicColor(this, it)
                    },
                    requestStart = { requestVpnAndStart() }
                )
            }
        }
        consumeVpnRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        consumeVpnRequest(intent)
        SubscriptionDeepLink.parse(intent?.dataString)?.let {
            intent?.data = null
            pendingSubscriptionUrl.value = it
        }
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
        requestSelectedBackendStart()
    }

    private fun requestSelectedBackendStart() {
        requestVpnPermission()
    }

    private fun requestVpnPermission() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) BackendController.startSelected(this)
        else vpnPermission.launch(prepareIntent)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestSelectedBackendStart()
        }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) BackendController.startSelected(this)
        }

    private fun consumeVpnRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_VPN, false) != true) return
        intent.removeExtra(EXTRA_REQUEST_VPN)
        window.decorView.post { requestVpnAndStart() }
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

private enum class AppScreen {
    MAIN,
    SETTINGS,
    HOSTS,
    EXCLUSIONS
}

@Composable
private fun XrayScreen(
    apps: List<InstalledApp>,
    dynamicColor: Boolean,
    subscriptionImport: MutableStateFlow<String?>,
    onSubscriptionImportConsumed: () -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    requestStart: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { SubscriptionRepository(context.applicationContext) }
    var subscription by remember { mutableStateOf(repository.load()) }
    var urlDraft by remember { mutableStateOf(subscription.url) }
    var selectedApps by remember { mutableStateOf(XrayPreferences.excludedApps(context)) }
    var screen by remember { mutableStateOf(AppScreen.MAIN) }
    var message by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }
    var pingResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activePing by remember { mutableStateOf<String?>(null) }
    var pingJob by remember { mutableStateOf<Job?>(null) }
    var diagnosticResults by remember { mutableStateOf<List<DiagnosticResult>?>(null) }
    var diagnosticRunning by remember { mutableStateOf(false) }
    var activeDiagnosticId by remember { mutableStateOf<String?>(null) }
    var diagnosticRoute by remember { mutableStateOf("") }
    var selectedBackend by remember { mutableStateOf(XrayPreferences.vpnBackend(context)) }
    var zapretPreset by remember { mutableStateOf(XrayPreferences.zapretPreset(context)) }
    var customZapretArguments by remember {
        mutableStateOf(XrayPreferences.zapretCustomArguments(context))
    }
    var telegramCfEnabled by remember {
        mutableStateOf(XrayPreferences.telegramCfEnabled(context))
    }
    var telegramCfDomain by remember {
        mutableStateOf(XrayPreferences.telegramCfDomain(context))
    }
    val scope = rememberCoroutineScope()
    val pingEngine = remember { XrayPingEngine(context.applicationContext) }
    val diagnostics = remember { ConnectivityDiagnostics() }
    val vpnState by XrayVpnService.state.collectAsState()
    val activeSocksPort by XrayVpnService.socksPort.collectAsState()
    val zapretAutoProgress by XrayVpnService.zapretAutoProgress.collectAsState()
    val verificationMessage by XrayVpnService.verificationMessage.collectAsState()
    val importUrl by subscriptionImport.collectAsState()
    val backendState = vpnState

    fun applyTelegramProxy() {
        val secret = XrayPreferences.telegramSecret(context)
        val nativeIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(TelegramProxyConfig.proxyUri(secret))
        )
        val opened = runCatching {
            context.startActivity(nativeIntent)
            true
        }.getOrDefault(false)
        if (!opened) {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(TelegramProxyConfig.proxyUri(secret, webFallback = true))
                )
            )
        }
        XrayPreferences.markTelegramProxyApplied(context)
    }

    fun updateSubscription(url: String, imported: Boolean = false) {
        if (updating) return
        updating = true
        message = if (imported) "Импорт подписки..." else "Обновление подписки..."
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.update(url) }
                subscription = when (result) {
                    is SubscriptionUpdateResult.Updated -> result.state
                    is SubscriptionUpdateResult.NotModified -> result.state
                }
                urlDraft = subscription.url
                pingResults = emptyMap()
                screen = AppScreen.MAIN
                message = when (result) {
                    is SubscriptionUpdateResult.Updated -> "Подписка обновлена"
                    is SubscriptionUpdateResult.NotModified -> "Подписка не изменилась"
                }
            } catch (e: Exception) {
                message = "Ошибка обновления: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                updating = false
                if (imported) onSubscriptionImportConsumed()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (subscription.url.isNotBlank() && importUrl == null) {
            updateSubscription(subscription.url)
        }
    }
    LaunchedEffect(importUrl) {
        importUrl?.let {
            while (updating) delay(50)
            updateSubscription(it, imported = true)
        }
    }
    LaunchedEffect(selectedBackend, vpnState) {
        if (selectedBackend.usesTelegram &&
            vpnState == XrayVpnService.STATE_CONNECTED &&
            !XrayPreferences.telegramProxyApplied(context)
        ) {
            applyTelegramProxy()
        }
    }
    DisposableEffect(pingEngine) {
        onDispose { pingEngine.cancel() }
    }
    BackHandler(enabled = screen != AppScreen.MAIN) {
        screen = if (screen == AppScreen.SETTINGS) AppScreen.MAIN else AppScreen.SETTINGS
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            when (screen) {
                AppScreen.MAIN -> MainScreen(
                    subscription = subscription,
                    vpnState = backendState,
                    updating = updating,
                    message = message,
                    diagnosticResults = diagnosticResults,
                    diagnosticRunning = diagnosticRunning,
                    activeDiagnosticId = activeDiagnosticId,
                    diagnosticRoute = diagnosticRoute,
                    zapretAutoProgress = zapretAutoProgress,
                    verificationMessage = verificationMessage,
                    selectedBackend = selectedBackend,
                    zapretPreset = zapretPreset,
                    telegramCfEnabled = telegramCfEnabled,
                    telegramCfDomain = telegramCfDomain,
                    onRefresh = { updateSubscription(subscription.url) },
                    onSelect = { id ->
                        subscription = repository.setActiveProfile(id)
                        pingResults = emptyMap()
                    },
                    onToggleVpn = {
                        val runtime = VpnRuntimeState.read(context)
                        if (runtime.status == VpnRunStatus.DISCONNECTED ||
                            backendState.startsWith("Ошибка:")
                        ) {
                            requestStart()
                        } else {
                            BackendController.stopRunning(context)
                        }
                    },
                    onSelectBackend = { backend ->
                        if (backend != selectedBackend) {
                            val wasRunning =
                                VpnRuntimeState.read(context).status != VpnRunStatus.DISCONNECTED
                            if (wasRunning) BackendController.stopRunning(context)
                            selectedBackend = backend
                            XrayPreferences.saveVpnBackend(context, backend)
                            if (wasRunning) {
                                scope.launch {
                                    delay(400)
                                    requestStart()
                                }
                            }
                        }
                    },
                    onSelectZapretPreset = { preset ->
                        if (preset != zapretPreset) {
                            zapretPreset = preset
                            XrayPreferences.saveZapretPreset(context, preset)
                            if (selectedBackend == VpnBackend.LOCAL_BYPASS &&
                                backendState != XrayVpnService.STATE_DISCONNECTED
                            ) {
                                XrayVpnService.reconnect(context)
                            }
                        }
                    },
                    onRetryZapretAuto = {
                        if (selectedBackend == VpnBackend.FULL_AUTO) {
                            XrayPreferences.clearYoutubeAutoCache(context)
                        } else {
                            XrayPreferences.clearZapretAutoCache(context)
                        }
                        message = if (selectedBackend != VpnBackend.PROXY_ONLY &&
                            backendState != XrayVpnService.STATE_DISCONNECTED
                        ) {
                            XrayVpnService.reconnect(context)
                            "Повторный подбор стратегии..."
                        } else {
                            "Подбор выполнится при подключении"
                        }
                    },
                    onRunDiagnostics = {
                        if (!diagnosticRunning) {
                            diagnosticRunning = true
                            diagnosticResults = emptyList()
                            activeDiagnosticId = ConnectivityDiagnostics.targets.first().id
                            val throughVpn = backendState == XrayVpnService.STATE_CONNECTED
                            diagnosticRoute = if (throughVpn) {
                                "backend; TUN активен · " +
                                    VpnRuntimeState.read(context).backend.title
                            } else {
                                "прямое соединение"
                            }
                            scope.launch {
                                try {
                                    val onResult: suspend (DiagnosticResult) -> Unit = { result ->
                                            diagnosticResults =
                                                diagnosticResults.orEmpty() + result
                                            val completed = diagnosticResults.orEmpty().size
                                            activeDiagnosticId =
                                                ConnectivityDiagnostics.targets
                                                    .getOrNull(completed)?.id
                                    }
                                    diagnosticResults = if (throughVpn) {
                                        val backend = VpnRuntimeState.read(context).backend
                                        diagnostics.runSocks(
                                            activeSocksPort
                                                ?: error("SOCKS-порт VPN недоступен"),
                                            resolveForSocks =
                                                backend == VpnBackend.LOCAL_BYPASS,
                                            targetsToTest = ConnectivityDiagnostics.targets,
                                            onResult = onResult
                                        )
                                    } else {
                                        diagnostics.runDirect(
                                            ConnectivityDiagnostics.targets,
                                            onResult
                                        )
                                    }
                                } finally {
                                    diagnosticRunning = false
                                    activeDiagnosticId = null
                                }
                            }
                        }
                    },
                    onOpenTarget = { target ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.url))
                        val browserPackage = intent.resolveActivity(context.packageManager)
                            ?.packageName
                        if (browserPackage != null && browserPackage in selectedApps) {
                            message = "Браузер исключён из VPN и откроет сайт напрямую"
                        }
                        context.startActivity(intent)
                    },
                    onApplyTelegram = { applyTelegramProxy() },
                    onSettings = { screen = AppScreen.SETTINGS }
                )
                AppScreen.SETTINGS -> SettingsScreen(
                    subscription = subscription,
                    url = urlDraft,
                    updating = updating,
                    dynamicColor = dynamicColor,
                    customZapretArguments = customZapretArguments,
                    telegramCfEnabled = telegramCfEnabled,
                    telegramCfDomain = telegramCfDomain,
                    onBack = { screen = AppScreen.MAIN },
                    onUrlChanged = { urlDraft = it },
                    onUpdate = { updateSubscription(urlDraft) },
                    onDynamicColorChanged = onDynamicColorChanged,
                    onCustomZapretArgumentsChanged = {
                        customZapretArguments = it
                    },
                    onSaveCustomZapretArguments = {
                        try {
                            XrayPreferences.saveZapretCustomArguments(
                                context,
                                customZapretArguments
                            )
                            message = "Параметры ByeDPI сохранены"
                        } catch (e: IllegalArgumentException) {
                            message = e.message ?: "Некорректные параметры ByeDPI"
                        }
                    },
                    onTelegramCfEnabledChanged = {
                        telegramCfEnabled = it
                        XrayPreferences.saveTelegramCfEnabled(context, it)
                        if (selectedBackend.usesTelegram &&
                            backendState == XrayVpnService.STATE_CONNECTED
                        ) {
                            XrayVpnService.reconnect(context)
                        }
                    },
                    onTelegramCfDomainChanged = { telegramCfDomain = it },
                    onSaveTelegramCfDomain = {
                        XrayPreferences.saveTelegramCfDomain(context, telegramCfDomain)
                        message = "Настройки Telegram Proxy сохранены"
                        if (selectedBackend.usesTelegram &&
                            backendState == XrayVpnService.STATE_CONNECTED
                        ) {
                            XrayVpnService.reconnect(context)
                        }
                    },
                    onHosts = { screen = AppScreen.HOSTS },
                    onExclusions = { screen = AppScreen.EXCLUSIONS }
                )
                AppScreen.HOSTS -> ContentScreen(
                    title = "Хосты",
                    onBack = { screen = AppScreen.SETTINGS }
                ) {
                    HostPingList(
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
                                        host.id to
                                            "Ошибка: ${e.message ?: e.javaClass.simpleName}"
                                        )
                                } finally {
                                    if (activePing == host.id) activePing = null
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                AppScreen.EXCLUSIONS -> ContentScreen(
                    title = "Исключения",
                    onBack = { screen = AppScreen.SETTINGS }
                ) {
                    AppExclusionList(
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
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.MainScreen(
    subscription: SubscriptionState,
    vpnState: String,
    updating: Boolean,
    message: String,
    diagnosticResults: List<DiagnosticResult>?,
    diagnosticRunning: Boolean,
    activeDiagnosticId: String?,
    diagnosticRoute: String,
    zapretAutoProgress: ZapretAutoProgress,
    verificationMessage: String,
    selectedBackend: VpnBackend,
    zapretPreset: ZapretPreset,
    telegramCfEnabled: Boolean,
    telegramCfDomain: String,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onToggleVpn: () -> Unit,
    onSelectBackend: (VpnBackend) -> Unit,
    onSelectZapretPreset: (ZapretPreset) -> Unit,
    onRetryZapretAuto: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onOpenTarget: (DiagnosticTarget) -> Unit,
    onApplyTelegram: () -> Unit,
    onSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(true) }
    val runtime = remember(vpnState, selectedBackend) { VpnRuntimeState.read(context) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    subscription.title.ifBlank { "SA05" },
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    vpnState,
                    color = if (vpnState.startsWith("Ошибка")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                if (runtime.profileName.isNotBlank() &&
                    runtime.status != VpnRunStatus.DISCONNECTED
                ) {
                    Text(
                        runtime.profileName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onSettings) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Настройки")
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VpnBackend.entries.forEach { backend ->
            val selected = backend == selectedBackend
            if (selected) {
                Button(
                    onClick = { onSelectBackend(backend) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(backend.title)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelectBackend(backend) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(backend.title)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val profileMode = selectedBackend.usesXrayProfile
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = {
                val enabled = !profileMode || subscription.profiles.isNotEmpty()
                if (enabled) menuExpanded = !menuExpanded
            },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = if (profileMode) {
                    subscription.activeProfile?.remarks.orEmpty()
                } else {
                    zapretPreset.title
                },
                onValueChange = {},
                readOnly = true,
                enabled = !profileMode || subscription.profiles.isNotEmpty(),
                singleLine = true,
                label = { Text(if (profileMode) "Профиль" else "Стратегия") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                if (profileMode) {
                    subscription.profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.remarks) },
                            onClick = {
                                onSelect(profile.id)
                                menuExpanded = false
                            }
                        )
                    }
                } else {
                    ZapretPreset.selectable.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.title) },
                            onClick = {
                                onSelectZapretPreset(preset)
                                menuExpanded = false
                            }
                        )
                    }
                }
            }
        }
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
            if (profileMode && updating) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    painterResource(R.drawable.ic_refresh),
                    contentDescription = if (profileMode) {
                        "Обновить подписку"
                    } else {
                        "Повторить подбор стратегии"
                    }
                )
            }
        }
    }
    if (selectedBackend.usesTelegram) {
        Spacer(Modifier.height(10.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Telegram через локальный обход", style = MaterialTheme.typography.titleMedium)
                Text(
                    "TG WS Proxy на 127.0.0.1:${TelegramProxyConfig.PORT}. " +
                        "При первом запуске примените его в Telegram.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    if (telegramCfEnabled) {
                        telegramCfDomain.ifBlank { "Cloudflare: автоматический домен" }
                    } else {
                        "Cloudflare выключен: прямое подключение к Telegram DC"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                OutlinedButton(
                    onClick = onApplyTelegram,
                    enabled = vpnState == XrayVpnService.STATE_CONNECTED,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Text("Применить в Telegram")
                }
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    if (zapretAutoProgress.running) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Подбор ByeDPI ${zapretAutoProgress.tested + 1}/${zapretAutoProgress.total}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        listOf(zapretAutoProgress.preset, zapretAutoProgress.target)
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (verificationMessage.isNotBlank()) {
            Text(
                verificationMessage,
                color = if (verificationMessage.contains("не подтверждён")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopStart),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Проверка ограничений",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (diagnosticRoute.isBlank()) {
                                    "Последовательная HTTPS-проверка"
                                } else {
                                    "Маршрут: $diagnosticRoute"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = onRunDiagnostics, enabled = !diagnosticRunning) {
                            if (diagnosticRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Проверить")
                            }
                        }
                    }
                    IconButton(
                        onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(32.dp)
                            .padding(4.dp)
                    ) {
                        Icon(
                            imageVector = if (diagnosticsExpanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription =
                                if (diagnosticsExpanded) "Свернуть" else "Развернуть",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (diagnosticsExpanded && (diagnosticResults != null || diagnosticRunning)) {
                    val results = diagnosticResults.orEmpty()
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    ConnectivityDiagnostics.targets.forEach { target ->
                        val result = results.firstOrNull { it.target.id == target.id }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(target.label)
                                if (result != null &&
                                    result.status != DiagnosticStatus.SUCCESS &&
                                    result.error.isNotBlank()
                                ) {
                                    Text(
                                        result.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = { onOpenTarget(target) }) {
                                Text("Открыть")
                            }
                            when {
                                activeDiagnosticId == target.id -> CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                result != null -> Text(
                                    when (result.status) {
                                        DiagnosticStatus.SUCCESS -> "✓"
                                        DiagnosticStatus.FAILED -> "✕"
                                        DiagnosticStatus.INCONCLUSIVE -> "!"
                                    },
                                    color = when (result.status) {
                                        DiagnosticStatus.SUCCESS ->
                                            MaterialTheme.colorScheme.primary
                                        DiagnosticStatus.FAILED ->
                                            MaterialTheme.colorScheme.error
                                        DiagnosticStatus.INCONCLUSIVE ->
                                            MaterialTheme.colorScheme.tertiary
                                    },
                                    style = MaterialTheme.typography.titleLarge
                                )
                                else -> Text(
                                    "·",
                                    color = MaterialTheme.colorScheme.outline,
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                        }
                    }
                    if (!diagnosticRunning && results.isNotEmpty()) {
                        Text(
                            ConnectivityDiagnosis.describe(results),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    Spacer(Modifier.weight(1f))
    Button(
        onClick = onToggleVpn,
        enabled = vpnState != XrayVpnService.STATE_CONNECTING,
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .width(200.dp)
            .height(64.dp)
    ) {
        if (vpnState == XrayVpnService.STATE_CONNECTING) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 3.dp
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_power),
                contentDescription = if (vpnState == XrayVpnService.STATE_CONNECTED) {
                    "Отключить"
                } else {
                    "Подключить"
                },
                modifier = Modifier.size(30.dp)
            )
        }
    }
    Spacer(Modifier.weight(1f))
    if (message.isNotBlank()) {
        Text(
            message,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun ColumnScope.SettingsScreen(
    subscription: SubscriptionState,
    url: String,
    updating: Boolean,
    dynamicColor: Boolean,
    customZapretArguments: String,
    telegramCfEnabled: Boolean,
    telegramCfDomain: String,
    onBack: () -> Unit,
    onUrlChanged: (String) -> Unit,
    onUpdate: () -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onCustomZapretArgumentsChanged: (String) -> Unit,
    onSaveCustomZapretArguments: () -> Unit,
    onTelegramCfEnabledChanged: (Boolean) -> Unit,
    onTelegramCfDomainChanged: (String) -> Unit,
    onSaveTelegramCfDomain: () -> Unit,
    onHosts: () -> Unit,
    onExclusions: () -> Unit
) {
    ContentScreen(title = "Настройки", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Подписка", style = MaterialTheme.typography.titleLarge)
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
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(if (updating) "Обновление..." else "Применить")
                }
            }
            item {
                Text("ByeDPI", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = customZapretArguments,
                    onValueChange = onCustomZapretArgumentsChanged,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Свои параметры") },
                    supportingText = {
                        Text("Используются при выборе стратегии «Свои параметры»")
                    }
                )
                OutlinedButton(
                    onClick = onSaveCustomZapretArguments,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Сохранить параметры")
                }
            }
            item {
                Text("Telegram Proxy", style = MaterialTheme.typography.titleLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Cloudflare", style = MaterialTheme.typography.titleMedium)
                        Text("WebSocket-маршрут к Telegram DC")
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    singleLine = true,
                    label = { Text("Свой Cloudflare-домен (необязательно)") }
                )
                OutlinedButton(
                    onClick = onSaveTelegramCfDomain,
                    enabled = telegramCfEnabled,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Сохранить Telegram Proxy")
                }
            }
            item { SettingsLink("Хосты", "Пинг outbound-подключений", onHosts) }
            item {
                SettingsLink(
                    "Исключения",
                    "Приложения с прямым доступом",
                    onExclusions
                )
            }
            item {
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
            }
            item {
                HorizontalDivider()
                Text(
                    "Последнее обновление: " + if (subscription.updatedAt > 0) {
                        DateFormat.getDateTimeInstance().format(Date(subscription.updatedAt))
                    } else {
                        "никогда"
                    },
                    modifier = Modifier.padding(top = 12.dp)
                )
                if (subscription.updateIntervalHours != null) {
                    Text("Интервал провайдера: ${subscription.updateIntervalHours} ч")
                }
                if (subscription.userInfo.isNotBlank()) {
                    Text("Трафик: ${subscription.userInfo}")
                }
            }
        }
    }
}

@Composable
private fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null
            )
        }
    }
}

@Composable
private fun ColumnScope.ContentScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = "Назад")
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
    Spacer(Modifier.height(8.dp))
    content()
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
    var query by remember { mutableStateOf("") }
    val visibleApps = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }
    Column(modifier.padding(top = 8.dp)) {
        Text("Выбрано: ${selected.size}. Изменения применятся после переподключения.")
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
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyColumn {
            items(visibleApps, key = { it.packageName }) { app ->
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
