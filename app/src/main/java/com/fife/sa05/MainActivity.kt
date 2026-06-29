package com.fife.sa05

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
    var diagnosticJob by remember { mutableStateOf<Job?>(null) }
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
    val appUpdateRepository = remember { AppUpdateRepository(context.applicationContext) }
    val vpnState by XrayVpnService.state.collectAsState()
    val activeSocksPort by XrayVpnService.socksPort.collectAsState()
    val zapretAutoProgress by XrayVpnService.zapretAutoProgress.collectAsState()
    val verificationMessage by XrayVpnService.verificationMessage.collectAsState()
    val importUrl by subscriptionImport.collectAsState()
    val backendState = vpnState
    val snackbarHostState = remember { SnackbarHostState() }
    val authorized = SubscriptionAuth.isAuthorized(subscription)
    var updateState by remember { mutableStateOf<AppUpdateState>(AppUpdateState.Idle) }
    var updateDownloadSession by remember { mutableStateOf(0L) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

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

    fun stopDiagnostics() {
        diagnosticJob?.cancel()
    }

    fun runDiagnostics() {
        if (diagnosticRunning) return
        diagnosticJob?.cancel()
        diagnosticRunning = true
        diagnosticResults = emptyList()
        activeDiagnosticId = ConnectivityDiagnostics.targets.first().id
        val throughVpn = backendState == XrayVpnService.STATE_CONNECTED
        diagnosticRoute = if (throughVpn) {
            "backend; TUN активен · " + VpnRuntimeState.read(context).backend.title
        } else {
            "прямое соединение"
        }
        diagnosticJob = scope.launch {
            try {
                val onResult: suspend (DiagnosticResult) -> Unit = { result ->
                    diagnosticResults = diagnosticResults.orEmpty() + result
                    val completed = diagnosticResults.orEmpty().size
                    activeDiagnosticId =
                        ConnectivityDiagnostics.targets.getOrNull(completed)?.id
                }
                diagnosticResults = if (throughVpn) {
                    val backend = VpnRuntimeState.read(context).backend
                    diagnostics.runSocks(
                        activeSocksPort ?: error("SOCKS-порт VPN недоступен"),
                        resolveForSocks = backend == VpnBackend.LOCAL_BYPASS,
                        targetsToTest = ConnectivityDiagnostics.targets,
                        onResult = onResult
                    )
                } else {
                    diagnostics.runDirect(
                        ConnectivityDiagnostics.targets,
                        onResult
                    )
                }
            } catch (_: CancellationException) {
                message = "Проверка остановлена"
            } finally {
                diagnosticRunning = false
                activeDiagnosticId = null
                diagnosticJob = null
            }
        }
    }

    fun checkAppUpdate(notify: Boolean = false) {
        scope.launch {
            updateState = AppUpdateState.Checking
            val result = try {
                withContext(Dispatchers.IO) {
                    appUpdateRepository.checkLatestRelease(
                        BuildConfig.VERSION_CODE,
                        BuildConfig.VERSION_NAME
                    )
                }
            } catch (e: Exception) {
                AppUpdateState.Error(e.message ?: e.javaClass.simpleName)
            }
            updateState = result
            if (notify) {
                if (result is AppUpdateState.Available) {
                    message = "Доступна версия ${result.release.versionName}"
                }
            } else {
                message = when (result) {
                    is AppUpdateState.Available -> "Доступна версия ${result.release.versionName}"
                    AppUpdateState.UpToDate -> "Установлена актуальная версия"
                    is AppUpdateState.Error -> "Ошибка проверки: ${result.message}"
                    else -> message
                }
            }
        }
    }

    fun downloadAppUpdate(release: AppRelease) {
        scope.launch {
            val sessionId = ++updateDownloadSession
            updateState = AppUpdateState.Available(release, downloadProgress = 0)
            try {
                val file = withContext(Dispatchers.IO) {
                    appUpdateRepository.downloadRelease(release) { progress ->
                        if (progress < 100 && sessionId == updateDownloadSession) {
                            mainHandler.post {
                                if (sessionId == updateDownloadSession) {
                                    updateState = AppUpdateState.Available(
                                        release = release,
                                        downloadedPath = null,
                                        downloadProgress = progress
                                    )
                                }
                            }
                        }
                    }
                }
                if (sessionId != updateDownloadSession) return@launch
                updateState = AppUpdateState.Available(
                    release = release,
                    downloadedPath = file.absolutePath
                )
            } catch (e: Exception) {
                if (sessionId == updateDownloadSession) {
                    updateState = AppUpdateState.Error(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun installDownloadedUpdate(path: String) {
        val file = java.io.File(path)
        if (!file.exists()) {
            updateState = AppUpdateState.Error("APK не найден")
            return
        }
        if (!AppUpdateInstaller.canInstallPackages(context)) {
            context.startActivity(AppUpdateInstaller.unknownSourcesIntent(context))
            return
        }
        context.startActivity(AppUpdateInstaller.installIntent(context, file))
    }

    LaunchedEffect(Unit) {
        checkAppUpdate(notify = true)
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
            AppScreen.HOSTS, AppScreen.ADVANCED -> AppScreen.SETTINGS
            AppScreen.EXCLUSIONS -> AppScreen.MAIN
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
                    updateState = updateState,
                    onRefresh = { updateSubscription(subscription.url) },
                    onSelect = { id ->
                        when (
                            profileSwitchAction(
                                currentProfileId = subscription.activeProfile?.id.orEmpty(),
                                selectedProfileId = id,
                                runtimeStatus = VpnRuntimeState.read(context).status
                            )
                        ) {
                            ProfileSwitchAction.NO_CHANGE -> Unit

                            ProfileSwitchAction.SAVE_ONLY -> {
                                subscription = repository.setActiveProfile(id)
                                pingResults = emptyMap()
                            }

                            ProfileSwitchAction.SAVE_AND_RECONNECT -> {
                                subscription = repository.setActiveProfile(id)
                                pingResults = emptyMap()
                                XrayVpnService.reconnect(context)
                                message = "Переподключение: " +
                                    subscription.activeProfile?.remarks.orEmpty()
                            }
                        }
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
                    onRunDiagnostics = { runDiagnostics() },
                    onCancelDiagnostics = { stopDiagnostics() },
                    onApplyTelegram = { applyTelegramProxy() },
                    onDiagnostics = { screen = AppScreen.DIAGNOSTICS },
                    onExclusions = { screen = AppScreen.EXCLUSIONS },
                    onCheckUpdate = {
                        if (updateState is AppUpdateState.Available) {
                            screen = AppScreen.SETTINGS
                        } else {
                            checkAppUpdate()
                        }
                    },
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
                        onRunDiagnostics = { runDiagnostics() },
                        onCancelDiagnostics = { stopDiagnostics() },
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
                    onBack = { screen = AppScreen.MAIN },
                    onUrlChanged = { urlDraft = it },
                    onUpdate = { updateSubscription(urlDraft) },
                    onDynamicColorChanged = onDynamicColorChanged,
                    onHosts = { screen = AppScreen.HOSTS },
                    onAdvanced = { screen = AppScreen.ADVANCED },
                    updateState = updateState,
                    canInstallPackages = AppUpdateInstaller.canInstallPackages(context),
                    onCheckUpdate = { checkAppUpdate() },
                    onDownloadUpdate = { downloadAppUpdate(it) },
                    onInstallUpdate = { installDownloadedUpdate(it) },
                    onOpenUnknownSources = {
                        context.startActivity(AppUpdateInstaller.unknownSourcesIntent(context))
                    }
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
                    onBack = { screen = AppScreen.MAIN }
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
    VpnBackend.FULL_AUTO -> "[BETA] Автоматически"
    VpnBackend.LOCAL_BYPASS -> "[BETA] Локальный обход"
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

private fun appUpdateSummary(updateState: AppUpdateState): String = when (updateState) {
    AppUpdateState.Idle -> "Проверить GitHub Releases"
    AppUpdateState.Checking -> "Проверяем последнюю версию"
    AppUpdateState.UpToDate -> "Установлена актуальная версия"
    is AppUpdateState.Available -> "Доступна версия ${updateState.release.versionName}"
    is AppUpdateState.Error -> "Ошибка: ${updateState.message}"
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RedesignedMainScreen(
    subscription: SubscriptionState,
    vpnState: String,
    updating: Boolean,
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
    onExclusions: () -> Unit,
    onCheckUpdate: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runtime = remember(vpnState, selectedBackend) { VpnRuntimeState.read(context) }
    var profileExpanded by remember { mutableStateOf(false) }
    var modeSheetVisible by remember { mutableStateOf(false) }
    val modeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val profileMode = selectedBackend.usesXrayProfile
    val connected = vpnState == XrayVpnService.STATE_CONNECTED
    val connecting = vpnState == XrayVpnService.STATE_CONNECTING
    val failed = vpnState.startsWith("Ошибка:")
    val connectionContainer = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        connected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val connectionContent = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        connected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
                        if (connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = connectionContent,
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (connected) {
                                    Icons.Default.CheckCircle
                                } else {
                                    Icons.Default.PowerSettingsNew
                                },
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                                tint = connectionContent
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                connectionTitle(vpnState),
                                style = MaterialTheme.typography.headlineSmall,
                                color = connectionContent
                            )
                            Text(
                                connectionDescription(vpnState, runtime),
                                style = MaterialTheme.typography.bodyMedium,
                                color = connectionContent
                            )
                        }
                    }
                    Button(
                        onClick = onToggleVpn,
                        enabled = !connecting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (connected) "Отключить VPN" else "Подключить VPN")
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
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (profileMode) "Профиль" else "Стратегия",
                                style = MaterialTheme.typography.labelLarge,
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
                                if (profileMode && updating) {
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
                        ExposedDropdownMenuBox(
                            expanded = profileExpanded,
                            onExpandedChange = {
                                val enabled = !profileMode || subscription.profiles.isNotEmpty()
                                if (enabled) profileExpanded = !profileExpanded
                            }
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
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                        expanded = profileExpanded
                                    )
                                },
                                modifier = Modifier
                                    .menuAnchor(
                                        ExposedDropdownMenuAnchorType.PrimaryNotEditable
                                    )
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
                                            trailingIcon = {
                                                if (profile.id ==
                                                    subscription.activeProfile?.id
                                                ) {
                                                    Icon(
                                                        Icons.Default.CheckCircle,
                                                        contentDescription = "Выбран",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            },
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
                                            trailingIcon = {
                                                if (preset == zapretPreset) {
                                                    Icon(
                                                        Icons.Default.CheckCircle,
                                                        contentDescription = "Выбрана",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            },
                                            onClick = {
                                                onSelectZapretPreset(preset)
                                                profileExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        if (profileMode &&
                            runtime.status != VpnRunStatus.DISCONNECTED
                        ) {
                            Text(
                                "Смена профиля автоматически переподключит VPN.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                        LinearProgressIndicator(
                            progress = {
                                results.size.toFloat() /
                                    ConnectivityDiagnostics.targets.size.toFloat()
                            },
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
                    if (diagnosticRunning) {
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

        if (verificationMessage.isNotBlank()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        verificationMessage,
                        modifier = Modifier.padding(16.dp),
                        color = if (verificationMessage.contains("не подтверждён")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
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

@Composable
private fun DashboardRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.MainScreen(
    subscription: SubscriptionState,
    vpnState: String,
    updating: Boolean,
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
    onExclusions: () -> Unit,
    onCheckUpdate: () -> Unit,
    onSettings: () -> Unit
) {
    RedesignedMainScreen(
        subscription = subscription,
        vpnState = vpnState,
        updating = updating,
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
        onExclusions = onExclusions,
        onCheckUpdate = onCheckUpdate,
        onSettings = onSettings,
        modifier = Modifier.weight(1f)
    )
}

@Composable
private fun DiagnosticsScreen(
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
                            LinearProgressIndicator(
                                progress = {
                                    results.size.toFloat() /
                                        ConnectivityDiagnostics.targets.size.toFloat()
                                },
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
                    if (diagnosticRunning) {
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
                            Text(if (results.isEmpty()) "Начать проверку" else "Проверить снова")
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
                                val statusColor = when {
                                    result == null ->
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    result.status == DiagnosticStatus.SUCCESS ->
                                        MaterialTheme.colorScheme.primary
                                    result.status == DiagnosticStatus.FAILED ->
                                        MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.tertiary
                                }
                                val detailText = if (activeDiagnosticId == target.id) {
                                    "Запрос выполняется"
                                } else {
                                    diagnosticResultText(result)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
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
                                    if (activeDiagnosticId == target.id) {
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

@Composable
private fun ColumnScope.SettingsScreen(
    subscription: SubscriptionState,
    url: String,
    updating: Boolean,
    dynamicColor: Boolean,
    updateState: AppUpdateState,
    canInstallPackages: Boolean,
    onBack: () -> Unit,
    onUrlChanged: (String) -> Unit,
    onUpdate: () -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onHosts: () -> Unit,
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
                            if (updating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Обновить подписку")
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

            item { SectionTitle("Подключение") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        DashboardRow(
                            title = "Хосты",
                            subtitle = "Проверка outbound-подключений",
                            onClick = onHosts
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        DashboardRow(
                            title = "Расширенные параметры",
                            subtitle = "Локальный обход и Telegram",
                            onClick = onAdvanced
                        )
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
