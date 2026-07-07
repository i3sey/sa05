package com.fife.sa05

import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fife.sa05.screens.AdvancedSettingsScreen
import com.fife.sa05.screens.AppExclusionList
import com.fife.sa05.screens.AuthScreen
import com.fife.sa05.screens.ContentScreen
import com.fife.sa05.screens.DiagnosticsScreen
import com.fife.sa05.screens.HostPingList
import com.fife.sa05.screens.MainScreen
import com.fife.sa05.screens.SettingsScreen
import com.fife.sa05.ui.theme.backTransform
import com.fife.sa05.ui.theme.forwardTransform
import com.fife.sa05.ui.theme.motionEnabled
import com.fife.sa05.ui.theme.Sa05Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            val preferences by produceState<XraySettings?>(initialValue = null) {
                XrayPreferences.settings(this@MainActivity).collect { value = it }
            }
            val loadedPreferences = preferences ?: return@setContent
            Sa05Theme(dynamicColor = loadedPreferences.dynamicColor) {
                var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
                LaunchedEffect(Unit) {
                    apps = withContext(Dispatchers.IO) { loadLaunchableApps() }
                }
                XrayScreen(
                    apps = apps,
                    preferences = loadedPreferences,
                    subscriptionImport = pendingSubscriptionUrl,
                    onSubscriptionImportConsumed = { pendingSubscriptionUrl.value = null },
                    onDynamicColorChanged = {
                        lifecycleScope.launch {
                            XrayPreferences.saveDynamicColor(this@MainActivity, it)
                        }
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
        lifecycleScope.launch {
            val subscription = XrayPreferences.snapshot(this@MainActivity).subscription
            if (!SubscriptionAuth.isAuthorized(subscription)) {
                Toast.makeText(
                    this@MainActivity,
                    "Сначала войдите по действующей ссылке",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@launch
            }
            requestSelectedBackendStart()
        }
    }

    private fun requestSelectedBackendStart() {
        requestVpnPermission()
    }

    private fun requestVpnPermission() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            lifecycleScope.launch { BackendController.startSelected(this@MainActivity) }
        } else {
            vpnPermission.launch(prepareIntent)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestSelectedBackendStart()
        }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                lifecycleScope.launch {
                    if (!BackendController.startSelected(this@MainActivity)) {
                        Toast.makeText(
                            this@MainActivity,
                            "Сначала войдите по действующей ссылке",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
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

/** Navigation depth, drives screen-transition direction (deeper = slide forward). */
private fun AppScreen?.navDepth(): Int = when (this) {
    null, AppScreen.MAIN -> 0
    AppScreen.DIAGNOSTICS, AppScreen.SETTINGS, AppScreen.EXCLUSIONS -> 1
    AppScreen.ADVANCED, AppScreen.HOSTS -> 2
}

@Composable
private fun XrayScreen(
    apps: List<InstalledApp>,
    preferences: XraySettings,
    subscriptionImport: MutableStateFlow<String?>,
    onSubscriptionImportConsumed: () -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    requestStart: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { SubscriptionRepository(context.applicationContext) }
    val subscription = preferences.subscription
    var urlDraft by remember { mutableStateOf(subscription.url) }
    var selectedApps by remember { mutableStateOf(preferences.excludedApps) }
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
    var selectedBackend by remember { mutableStateOf(preferences.vpnBackend) }
    var zapretPreset by remember { mutableStateOf(preferences.zapretPreset) }
    var customZapretArguments by remember {
        mutableStateOf(preferences.zapretCustomArguments)
    }
    var telegramCfEnabled by remember {
        mutableStateOf(preferences.telegramCfEnabled)
    }
    var telegramCfDomain by remember {
        mutableStateOf(preferences.telegramCfDomain)
    }
    val scope = rememberCoroutineScope()
    val pingEngine = remember { XrayPingEngine(context.applicationContext) }
    val diagnostics = remember { ConnectivityDiagnostics() }
    val appUpdateRepository = remember { AppUpdateRepository(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val installPermissionMonitor = remember(context) {
        InstallPermissionMonitor { AppUpdateInstaller.canInstallPackages(context) }
    }
    val canInstallPackages by installPermissionMonitor.canInstall.collectAsState()
    val vpnRuntime by VpnRuntimeState.observe(context).collectAsState()
    val activeSocksPort by XrayVpnService.socksPort.collectAsState()
    val zapretAutoProgress by XrayVpnService.zapretAutoProgress.collectAsState()
    val importUrl by subscriptionImport.collectAsState()
    val backendState = vpnRuntime.status
    val snackbarHostState = remember { SnackbarHostState() }
    val authorized = SubscriptionAuth.isAuthorized(subscription)
    var updateState by remember { mutableStateOf<AppUpdateState>(AppUpdateState.Idle) }
    var updateDownloadSession by remember { mutableStateOf(0L) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun applyTelegramProxy() {
        scope.launch {
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
    }

    fun updateSubscription(url: String, imported: Boolean = false) {
        if (updating) return
        updating = true
        message = if (imported) "Импорт подписки..." else "Обновление подписки..."
        scope.launch {
            try {
                val result = SubscriptionRefreshRunner.refresh(context, url)
                val refreshed = when (result) {
                    is SubscriptionUpdateResult.Updated -> result.state
                    is SubscriptionUpdateResult.NotModified -> result.state
                }
                SubscriptionRefreshScheduler.sync(context, refreshed)
                urlDraft = refreshed.url
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
        val throughVpn = backendState == VpnRunStatus.CONNECTED
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

    LaunchedEffect(preferences.excludedApps) {
        selectedApps = preferences.excludedApps
    }
    LaunchedEffect(preferences.vpnBackend) {
        selectedBackend = preferences.vpnBackend
    }
    LaunchedEffect(preferences.zapretPreset) {
        zapretPreset = preferences.zapretPreset
    }
    LaunchedEffect(preferences.zapretCustomArguments) {
        customZapretArguments = preferences.zapretCustomArguments
    }
    LaunchedEffect(preferences.telegramCfEnabled) {
        telegramCfEnabled = preferences.telegramCfEnabled
    }
    LaunchedEffect(preferences.telegramCfDomain) {
        telegramCfDomain = preferences.telegramCfDomain
    }
    LaunchedEffect(subscription.url) {
        if (!updating) urlDraft = subscription.url
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
    LaunchedEffect(selectedBackend, vpnRuntime.status) {
        if (selectedBackend.usesTelegram &&
            vpnRuntime.status == VpnRunStatus.CONNECTED &&
            !preferences.telegramProxyApplied
        ) {
            applyTelegramProxy()
        }
    }
    DisposableEffect(pingEngine) {
        onDispose { pingEngine.cancel() }
    }
    DisposableEffect(lifecycleOwner, installPermissionMonitor) {
        lifecycleOwner.lifecycle.addObserver(installPermissionMonitor)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(installPermissionMonitor)
        }
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
            val navTarget = if (authorized) screen else null
            val motionOn = motionEnabled()
            AnimatedContent(
                targetState = navTarget,
                transitionSpec = {
                    if (targetState.navDepth() >= initialState.navDepth()) {
                        forwardTransform(motionOn)
                    } else {
                        backTransform(motionOn)
                    }
                },
                label = "screen"
            ) { target ->
              Column(Modifier.fillMaxSize()) {
                if (target == null) {
                AuthScreen(
                    url = urlDraft,
                    updating = updating,
                    onUrlChanged = { urlDraft = it },
                    onSubmit = { updateSubscription(urlDraft) },
                    modifier = Modifier.fillMaxSize()
                )
                } else when (target) {
                AppScreen.MAIN -> MainScreen(
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
                    telegramProxyApplied = preferences.telegramProxyApplied,
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
                                scope.launch {
                                    repository.setActiveProfile(id)
                                    pingResults = emptyMap()
                                }
                            }

                            ProfileSwitchAction.SAVE_AND_RECONNECT -> {
                                scope.launch {
                                    val updated = repository.setActiveProfile(id)
                                    pingResults = emptyMap()
                                    XrayVpnService.reconnect(context)
                                    message = "Переподключение: " +
                                        updated.activeProfile?.remarks.orEmpty()
                                }
                            }
                        }
                    },
                    onToggleVpn = {
                        val runtime = VpnRuntimeState.read(context)
                        if (runtime.status == VpnRunStatus.DISCONNECTED ||
                            runtime.status == VpnRunStatus.ERROR
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
                            scope.launch {
                                XrayPreferences.saveVpnBackend(context, backend)
                                if (wasRunning) {
                                    delay(400)
                                    requestStart()
                                }
                            }
                        }
                    },
                    onSelectZapretPreset = { preset ->
                        if (preset != zapretPreset) {
                            zapretPreset = preset
                            scope.launch {
                                XrayPreferences.saveZapretPreset(context, preset)
                                if (selectedBackend == VpnBackend.LOCAL_BYPASS &&
                                    backendState != VpnRunStatus.DISCONNECTED
                                ) {
                                    XrayVpnService.reconnect(context)
                                }
                            }
                        }
                    },
                    onRetryZapretAuto = {
                        scope.launch {
                            if (selectedBackend == VpnBackend.FULL_AUTO) {
                                XrayPreferences.clearYoutubeAutoCache(context)
                            } else {
                                XrayPreferences.clearZapretAutoCache(context)
                            }
                            message = if (selectedBackend != VpnBackend.PROXY_ONLY &&
                                backendState != VpnRunStatus.DISCONNECTED
                            ) {
                                XrayVpnService.reconnect(context)
                                "Повторный подбор стратегии..."
                            } else {
                                "Подбор выполнится при подключении"
                            }
                        }
                    },
                    onRunDiagnostics = { runDiagnostics() },
                    onCancelDiagnostics = { stopDiagnostics() },
                    onApplyTelegram = { applyTelegramProxy() },
                    onDiagnostics = { screen = AppScreen.DIAGNOSTICS },
                    onOpenNetworkSettings = {
                        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                        } else {
                            Settings.ACTION_WIRELESS_SETTINGS
                        }
                        context.startActivity(Intent(action))
                    },
                    onOpenSubscriptionSettings = { screen = AppScreen.SETTINGS },
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
                    dynamicColor = preferences.dynamicColor,
                    onBack = { screen = AppScreen.MAIN },
                    onUrlChanged = { urlDraft = it },
                    onUpdate = { updateSubscription(urlDraft) },
                    onDynamicColorChanged = onDynamicColorChanged,
                    onHosts = { screen = AppScreen.HOSTS },
                    onAdvanced = { screen = AppScreen.ADVANCED },
                    updateState = updateState,
                    canInstallPackages = canInstallPackages,
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
                        scope.launch {
                            try {
                                XrayPreferences.saveZapretCustomArguments(
                                    context,
                                    customZapretArguments
                                )
                                message = "Параметры ByeDPI сохранены"
                            } catch (e: IllegalArgumentException) {
                                message = e.message ?: "Некорректные параметры ByeDPI"
                            }
                        }
                    },
                    onTelegramCfEnabledChanged = {
                        telegramCfEnabled = it
                        scope.launch {
                            XrayPreferences.saveTelegramCfEnabled(context, it)
                            if (selectedBackend.usesTelegram &&
                                backendState == VpnRunStatus.CONNECTED
                            ) {
                                XrayVpnService.reconnect(context)
                            }
                        }
                    },
                    onTelegramCfDomainChanged = { telegramCfDomain = it },
                    onSaveTelegramCfDomain = {
                        scope.launch {
                            XrayPreferences.saveTelegramCfDomain(context, telegramCfDomain)
                            message = "Настройки Telegram Proxy сохранены"
                            if (selectedBackend.usesTelegram &&
                                backendState == VpnRunStatus.CONNECTED
                            ) {
                                XrayVpnService.reconnect(context)
                            }
                        }
                    }
                )
                AppScreen.HOSTS -> ContentScreen(
                    title = "Хосты",
                    onBack = { screen = AppScreen.SETTINGS }
                ) {
                    HostPingList(
                        config = subscription.activeProfile?.json
                            ?: preferences.config,
                        results = pingResults,
                        activePing = activePing,
                        onPing = { host ->
                            pingEngine.cancel()
                            pingJob?.cancel()
                            activePing = host.id
                            pingResults = pingResults + (host.id to "Проверка...")
                            val config = subscription.activeProfile?.json
                                ?: preferences.config
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
                            scope.launch {
                                XrayPreferences.saveExcludedApps(context, selectedApps)
                            }
                            message = "Исключения подписки добавлены"
                        },
                        onToggle = { pkg ->
                            selectedApps = if (pkg in selectedApps) {
                                selectedApps - pkg
                            } else {
                                selectedApps + pkg
                            }
                            scope.launch {
                                XrayPreferences.saveExcludedApps(context, selectedApps)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                }
              }
            }
        }
    }
}
