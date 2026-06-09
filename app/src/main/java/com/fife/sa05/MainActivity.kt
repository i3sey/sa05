package com.fife.sa05

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
        if (!SubscriptionAuth.isAuthorized(this)) {
            Toast.makeText(this, "Сначала войдите по действующей ссылке", Toast.LENGTH_SHORT)
                .show()
            return
        }
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
            if (result.resultCode == RESULT_OK && !BackendController.startSelected(this)) {
                Toast.makeText(this, "Сначала войдите по действующей ссылке", Toast.LENGTH_SHORT)
                    .show()
            }
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
    DIAGNOSTICS,
    SETTINGS,
    ADVANCED,
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
    val snackbarHostState = remember { SnackbarHostState() }
    val authorized = SubscriptionAuth.isAuthorized(subscription)

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
        screen = when (screen) {
            AppScreen.HOSTS, AppScreen.EXCLUSIONS, AppScreen.ADVANCED -> AppScreen.SETTINGS
            else -> AppScreen.MAIN
        }
    }
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            snackbarHostState.showSnackbar(message)
            message = ""
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!authorized) {
                AuthScreen(
                    url = urlDraft,
                    updating = updating,
                    onUrlChanged = { urlDraft = it },
                    onSubmit = { updateSubscription(urlDraft) },
                    modifier = Modifier.fillMaxSize()
                )
            } else when (screen) {
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
                    onDiagnostics = { screen = AppScreen.DIAGNOSTICS },
                    onSettings = { screen = AppScreen.SETTINGS }
                )
                AppScreen.DIAGNOSTICS -> ContentScreen(
                    title = "Проверка",
                    onBack = { screen = AppScreen.MAIN }
                ) {
                    DiagnosticsScreen(
                        diagnosticResults = diagnosticResults,
                        diagnosticRunning = diagnosticRunning,
                        activeDiagnosticId = activeDiagnosticId,
                        diagnosticRoute = diagnosticRoute,
                        onRunDiagnostics = {
                            if (!diagnosticRunning) {
                                diagnosticRunning = true
                                diagnosticResults = emptyList()
                                activeDiagnosticId = ConnectivityDiagnostics.targets.first().id
                                val throughVpn = backendState == XrayVpnService.STATE_CONNECTED
                                diagnosticRoute = if (throughVpn) {
                                    "VPN · " + VpnRuntimeState.read(context).backend.clientTitle()
                                } else {
                                    "прямое соединение"
                                }
                                scope.launch {
                                    try {
                                        val onResult: suspend (DiagnosticResult) -> Unit = { result ->
                                            diagnosticResults = diagnosticResults.orEmpty() + result
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
                        modifier = Modifier.weight(1f)
                    )
                }
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
                    onExclusions = { screen = AppScreen.EXCLUSIONS },
                    onAdvanced = { screen = AppScreen.ADVANCED }
                )
                AppScreen.ADVANCED -> AdvancedSettingsScreen(
                    customZapretArguments = customZapretArguments,
                    telegramCfEnabled = telegramCfEnabled,
                    telegramCfDomain = telegramCfDomain,
                    onBack = { screen = AppScreen.SETTINGS },
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
                    }
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

@Composable
private fun AuthScreen(
    url: String,
    updating: Boolean,
    onUrlChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Вход в SA05", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Чтобы пользоваться приложением, нужна действующая HTTPS-ссылка " +
                        "подписки с JSON-профилями Xray.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Ссылка подписки") },
                    placeholder = { Text("https://example.com/token/json") }
                )
                Button(
                    onClick = onSubmit,
                    enabled = !updating && url.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (updating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Войти")
                    }
                }
                Text(
                    "После первой успешной проверки ссылка сохраняется, повторный вход " +
                        "без сети не требуется.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun VpnBackend.clientTitle(): String = when (this) {
    VpnBackend.FULL_AUTO -> "Автоматически"
    VpnBackend.LOCAL_BYPASS -> "Локальный обход"
    VpnBackend.PROXY_ONLY -> "Только прокси"
}

private fun VpnBackend.clientDescription(): String = when (this) {
    VpnBackend.FULL_AUTO -> "Telegram локально, YouTube с обходом, остальное через профиль"
    VpnBackend.LOCAL_BYPASS -> "Локальный обход без удалённого профиля"
    VpnBackend.PROXY_ONLY -> "Весь трафик через выбранный профиль"
}

private fun connectionTitle(vpnState: String): String = when {
    vpnState == XrayVpnService.STATE_CONNECTED -> "VPN включён"
    vpnState == XrayVpnService.STATE_CONNECTING -> "Подключение..."
    vpnState.startsWith("Ошибка:") -> "Нужна проверка"
    else -> "VPN выключен"
}

private fun connectionDescription(vpnState: String, runtime: VpnRuntimeSnapshot): String = when {
    vpnState.startsWith("Ошибка:") -> vpnState
    runtime.status != VpnRunStatus.DISCONNECTED && runtime.profileName.isNotBlank() ->
        "${runtime.backend.clientTitle()} · ${runtime.profileName}"
    runtime.status != VpnRunStatus.DISCONNECTED -> runtime.backend.clientTitle()
    else -> "Выберите режим и нажмите кнопку подключения"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RedesignedMainScreen(
    subscription: SubscriptionState,
    vpnState: String,
    updating: Boolean,
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
    onApplyTelegram: () -> Unit,
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runtime = remember(vpnState, selectedBackend) { VpnRuntimeState.read(context) }
    var profileExpanded by remember { mutableStateOf(false) }
    var modeSheetVisible by remember { mutableStateOf(false) }
    val modeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val profileMode = selectedBackend.usesXrayProfile

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
                        Icon(
                            imageVector = if (backend == selectedBackend) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.Tune
                            },
                            contentDescription = null
                        )
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(subscription.title.ifBlank { "SA05" })
                        Text(
                            "Простой VPN-клиент",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        connectionTitle(vpnState),
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (vpnState.startsWith("Ошибка:")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Text(
                        connectionDescription(vpnState, runtime),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onToggleVpn,
                        enabled = vpnState != XrayVpnService.STATE_CONNECTING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        if (vpnState == XrayVpnService.STATE_CONNECTING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.PowerSettingsNew,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (vpnState == XrayVpnService.STATE_CONNECTED) {
                                    "Отключить"
                                } else {
                                    "Подключить"
                                }
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsLink(
                title = "Режим",
                subtitle = selectedBackend.clientTitle(),
                onClick = { modeSheetVisible = true }
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExposedDropdownMenuBox(
                        expanded = profileExpanded,
                        onExpandedChange = {
                            val enabled = !profileMode || subscription.profiles.isNotEmpty()
                            if (enabled) profileExpanded = !profileExpanded
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
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = profileExpanded
                                )
                            },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = profileExpanded,
                            onDismissRequest = { profileExpanded = false }
                        ) {
                            if (profileMode) {
                                subscription.profiles.forEach { profile ->
                                    DropdownMenuItem(
                                        text = { Text(profile.remarks) },
                                        onClick = {
                                            onSelect(profile.id)
                                            profileExpanded = false
                                        }
                                    )
                                }
                            } else {
                                ZapretPreset.selectable.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text(preset.title) },
                                        onClick = {
                                            onSelectZapretPreset(preset)
                                            profileExpanded = false
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
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                    }
                }
            }
        }
        if (zapretAutoProgress.running) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Подбираю локальный обход")
                        LinearProgressIndicator(
                            progress = {
                                if (zapretAutoProgress.total <= 0) {
                                    0f
                                } else {
                                    zapretAutoProgress.tested.toFloat() /
                                        zapretAutoProgress.total.toFloat()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        )
                        Text(
                            listOf(zapretAutoProgress.preset, zapretAutoProgress.target)
                                .filter(String::isNotBlank)
                                .joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
        if (verificationMessage.isNotBlank()) {
            item {
                Text(
                    verificationMessage,
                    color = if (verificationMessage.contains("не подтверждён")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        if (selectedBackend.usesTelegram &&
            vpnState == XrayVpnService.STATE_CONNECTED &&
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
                                telegramCfDomain.ifBlank { "Используется Cloudflare-маршрут" }
                            } else {
                                "Используется прямой локальный маршрут"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(onClick = onApplyTelegram) {
                            Text("Открыть Telegram")
                        }
                    }
                }
            }
        }
        item {
            SettingsLink(
                title = "Проверка соединения",
                subtitle = "Google, Ya.ru, YouTube, Telegram и заблокированные сайты",
                onClick = onDiagnostics
            )
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
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit
) {
    RedesignedMainScreen(
        subscription = subscription,
        vpnState = vpnState,
        updating = updating,
        zapretAutoProgress = zapretAutoProgress,
        verificationMessage = verificationMessage,
        selectedBackend = selectedBackend,
        zapretPreset = zapretPreset,
        telegramCfEnabled = telegramCfEnabled,
        telegramCfDomain = telegramCfDomain,
        onRefresh = onRefresh,
        onSelect = onSelect,
        onToggleVpn = onToggleVpn,
        onSelectBackend = onSelectBackend,
        onSelectZapretPreset = onSelectZapretPreset,
        onRetryZapretAuto = onRetryZapretAuto,
        onApplyTelegram = onApplyTelegram,
        onDiagnostics = onDiagnostics,
        onSettings = onSettings,
        modifier = Modifier.weight(1f)
    )
    return

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
private fun DiagnosticsScreen(
    diagnosticResults: List<DiagnosticResult>?,
    diagnosticRunning: Boolean,
    activeDiagnosticId: String?,
    diagnosticRoute: String,
    onRunDiagnostics: () -> Unit,
    onOpenTarget: (DiagnosticTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val results = diagnosticResults.orEmpty()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Проверка ограничений",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        if (diagnosticRoute.isBlank()) {
                            "Запускается последовательно и сразу показывает каждый результат."
                        } else {
                            "Маршрут: $diagnosticRoute"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onRunDiagnostics,
                        enabled = !diagnosticRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
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
                    if (!diagnosticRunning && results.isNotEmpty()) {
                        Text(
                            ConnectivityDiagnosis.describe(results),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
        items(ConnectivityDiagnostics.targets, key = { it.id }) { target ->
            val result = results.firstOrNull { it.target.id == target.id }
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(target.label, style = MaterialTheme.typography.titleMedium)
                        val detail = when {
                            result != null &&
                                result.status != DiagnosticStatus.SUCCESS &&
                                result.error.isNotBlank() -> result.error
                            result != null -> listOfNotNull(
                                result.statusCode?.toString(),
                                result.delayMs?.let { "$it мс" }
                            ).joinToString(" · ")
                            activeDiagnosticId == target.id -> "Проверяется..."
                            else -> target.url
                        }
                        Text(
                            detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TextButton(onClick = { onOpenTarget(target) }) {
                        Text("Открыть")
                    }
                    when {
                        activeDiagnosticId == target.id -> CircularProgressIndicator(
                            Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                        result != null -> Text(
                            when (result.status) {
                                DiagnosticStatus.SUCCESS -> "✓"
                                DiagnosticStatus.FAILED -> "✕"
                                DiagnosticStatus.INCONCLUSIVE -> "!"
                            },
                            color = when (result.status) {
                                DiagnosticStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                                DiagnosticStatus.FAILED -> MaterialTheme.colorScheme.error
                                DiagnosticStatus.INCONCLUSIVE -> MaterialTheme.colorScheme.tertiary
                            },
                            style = MaterialTheme.typography.headlineSmall
                        )
                        else -> Text(
                            "·",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                }
            }
        }
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
    onExclusions: () -> Unit,
    onAdvanced: () -> Unit
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
            item { SettingsLink("Хосты", "Пинг outbound-подключений", onHosts) }
            item {
                SettingsLink(
                    "Исключения",
                    "Приложения с прямым доступом",
                    onExclusions
                )
            }
            item {
                SettingsLink(
                    "Расширенные параметры",
                    "Локальный обход и Telegram-маршрут",
                    onAdvanced
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
private fun ColumnScope.AdvancedSettingsScreen(
    customZapretArguments: String,
    telegramCfEnabled: Boolean,
    telegramCfDomain: String,
    onBack: () -> Unit,
    onCustomZapretArgumentsChanged: (String) -> Unit,
    onSaveCustomZapretArguments: () -> Unit,
    onTelegramCfEnabledChanged: (Boolean) -> Unit,
    onTelegramCfDomainChanged: (String) -> Unit,
    onSaveTelegramCfDomain: () -> Unit
) {
    ContentScreen(title = "Расширенные", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.ContentScreen(
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
