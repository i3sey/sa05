package com.fife.sa05

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import com.fife.sa05.screens.SubscriptionReadyScreen
import com.fife.sa05.ui.theme.backTransform
import com.fife.sa05.ui.theme.fadeTransform
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
                    "Сначала добавьте действующую подписку",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            startSelectedBackend()
        } else {
            vpnPermission.launch(prepareIntent)
        }
    }

    private fun startSelectedBackend() {
        lifecycleScope.launch {
            if (!BackendController.startSelected(this@MainActivity)) {
                Toast.makeText(
                    this@MainActivity,
                    "Сначала добавьте действующую подписку",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            requestNotificationPermissionIfNeeded()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startSelectedBackend()
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
    null, AppScreen.MAIN, AppScreen.DIAGNOSTICS, AppScreen.SETTINGS -> 0
    AppScreen.ADVANCED, AppScreen.HOSTS, AppScreen.EXCLUSIONS -> 1
}

private fun AppScreen.isTopLevel(): Boolean = this in setOf(
    AppScreen.MAIN,
    AppScreen.DIAGNOSTICS,
    AppScreen.SETTINGS
)

@Composable
private fun AppNavigationBar(
    screen: AppScreen,
    onSelect: (AppScreen) -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            selected = screen == AppScreen.MAIN,
            onClick = { onSelect(AppScreen.MAIN) },
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { androidx.compose.material3.Text("Главная") }
        )
        NavigationBarItem(
            selected = screen == AppScreen.DIAGNOSTICS,
            onClick = { onSelect(AppScreen.DIAGNOSTICS) },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
            label = { androidx.compose.material3.Text("Проверка") }
        )
        NavigationBarItem(
            selected = screen == AppScreen.SETTINGS,
            onClick = { onSelect(AppScreen.SETTINGS) },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { androidx.compose.material3.Text("Настройки") }
        )
    }
}

// Standalone Telegram proxy is a plain foreground service (no VpnService tunnel protection), so OEM battery managers reap it fast unless whitelisted.
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
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
    var subscriptionError by remember { mutableStateOf<String?>(null) }
    var importedSubscription by remember { mutableStateOf<SubscriptionState?>(null) }
    var updating by remember { mutableStateOf(false) }
    var pingResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activePing by remember { mutableStateOf<String?>(null) }
    var pingJob by remember { mutableStateOf<Job?>(null) }
    var diagnosticResults by remember { mutableStateOf<List<DiagnosticResult>?>(null) }
    var diagnosticRunning by remember { mutableStateOf(false) }
    var activeDiagnosticId by remember { mutableStateOf<String?>(null) }
    var diagnosticRoute by remember { mutableStateOf("") }
    var diagnosticJob by remember { mutableStateOf<Job?>(null) }
    var connectionCheck by remember {
        mutableStateOf<ConnectionCheckState>(ConnectionCheckState.Idle)
    }
    var quickConnectionCheckJob by remember { mutableStateOf<Job?>(null) }
    var excludedBrowserTarget by remember { mutableStateOf<DiagnosticTarget?>(null) }
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
    val telegramRuntime by TelegramProxyRuntimeState.observe(context).collectAsState()
    val activeSocksPort by XrayVpnService.socksPort.collectAsState()
    val trafficUsage by XrayVpnService.trafficUsage.collectAsState()
    val importUrl by subscriptionImport.collectAsState()
    val backendState = vpnRuntime.status
    val snackbarHostState = remember { SnackbarHostState() }
    val authorized = SubscriptionAuth.isAuthorized(subscription)
    var updateState by remember { mutableStateOf<AppUpdateState>(AppUpdateState.Idle) }
    var updateDownloadSession by remember { mutableStateOf(0L) }
    var updateDownloadJob by remember { mutableStateOf<Job?>(null) }
    var telegramStartRequested by remember { mutableStateOf(false) }
    var showTelegramExplainer by remember { mutableStateOf(false) }
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

    fun startTelegramOnly() {
        if (telegramRuntime.status == TelegramProxyRunStatus.STARTING) return
        requestIgnoreBatteryOptimizations(context)
        telegramStartRequested = true
        scope.launch {
            if (!BackendController.startTelegramOnly(context)) {
                telegramStartRequested = false
                message = TelegramProxyRuntimeState.read(context).message
                    .ifBlank { "Не удалось запустить Telegram" }
            }
        }
    }

    fun updateSubscription(
        url: String,
        imported: Boolean = false,
        silent: Boolean = false
    ) {
        if (updating) return
        val showFirstImportSuccess = !authorized
        subscriptionError = null
        updating = true
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
                if (showFirstImportSuccess) importedSubscription = refreshed
                if (!silent && !showFirstImportSuccess) {
                    message = when (result) {
                        is SubscriptionUpdateResult.Updated -> "Подписка обновлена"
                        is SubscriptionUpdateResult.NotModified -> "Подписка уже актуальна"
                    }
                }
            } catch (e: Exception) {
                val error = "Не удалось обновить подписку: " +
                    (e.message ?: e.javaClass.simpleName)
                if (!authorized) {
                    subscriptionError = error
                } else if (!silent) {
                    message = error
                }
            } finally {
                updating = false
                if (imported) onSubscriptionImportConsumed()
            }
        }
    }

    fun pasteSubscriptionUrl() {
        val value = context.getSystemService(ClipboardManager::class.java)
            ?.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
        if (value.isNullOrBlank()) {
            subscriptionError = "В буфере обмена нет ссылки подписки"
        } else {
            urlDraft = value
            subscriptionError = null
        }
    }

    fun openDiagnosticTarget(target: DiagnosticTarget) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.url))
        val browserPackage = intent.resolveActivity(context.packageManager)?.packageName
        if (browserPackage != null && browserPackage in selectedApps) {
            excludedBrowserTarget = target
        } else {
            context.startActivity(intent)
        }
    }

    fun runQuickConnectionCheck() {
        if (backendState != VpnRunStatus.CONNECTED || diagnosticRunning) return
        val socksPort = activeSocksPort ?: return
        quickConnectionCheckJob?.cancel()
        connectionCheck = ConnectionCheckState.Running
        quickConnectionCheckJob = scope.launch {
            val result = runCatching {
                diagnostics.runSocks(
                    socksPort = socksPort,
                    resolveForSocks = vpnRuntime.backend == VpnBackend.LOCAL_BYPASS,
                    targetsToTest = listOf(ConnectivityDiagnostics.target("google"))
                ).single()
            }.getOrElse { error ->
                DiagnosticResult(
                    target = ConnectivityDiagnostics.target("google"),
                    status = DiagnosticStatus.FAILED,
                    error = error.message ?: error.javaClass.simpleName
                )
            }
            connectionCheck = connectionCheckState(result)
            quickConnectionCheckJob = null
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

    fun checkAppUpdate(notify: Boolean = false, silent: Boolean = false) {
        if (updateDownloadJob?.isActive == true) {
            if (!silent) message = "Дождитесь завершения скачивания обновления"
            return
        }
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
            if (!silent && notify && result is AppUpdateState.Available) {
                message = "Доступна версия ${result.release.versionName}"
            } else if (!silent && !notify) {
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
        if (updateDownloadJob?.isActive == true) {
            message = "Обновление уже скачивается"
            return
        }
        val sessionId = ++updateDownloadSession
        updateDownloadJob = scope.launch {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessionId == updateDownloadSession) {
                    updateState = AppUpdateState.Error(
                        "Не удалось скачать APK: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            } finally {
                if (sessionId == updateDownloadSession) {
                    updateDownloadJob = null
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
        checkAppUpdate(silent = true)
        if (subscription.url.isNotBlank() && importUrl == null) {
            updateSubscription(subscription.url, silent = true)
        }
    }
    LaunchedEffect(importUrl) {
        importUrl?.let {
            while (updating) delay(50)
            updateSubscription(it, imported = true)
        }
    }
    LaunchedEffect(telegramRuntime.status, telegramStartRequested) {
        when {
            telegramRuntime.status == TelegramProxyRunStatus.RUNNING && telegramStartRequested -> {
                telegramStartRequested = false
                if (preferences.telegramProxyExplainerSeen) {
                    applyTelegramProxy()
                } else {
                    showTelegramExplainer = true
                }
            }
            telegramRuntime.status == TelegramProxyRunStatus.ERROR && telegramStartRequested -> {
                telegramStartRequested = false
                message = telegramRuntime.message.ifBlank { "Не удалось запустить Telegram" }
            }
        }
    }
    LaunchedEffect(vpnRuntime.status, vpnRuntime.connectedAtMillis, activeSocksPort) {
        if (vpnRuntime.status == VpnRunStatus.CONNECTED &&
            vpnRuntime.connectedAtMillis > 0L && activeSocksPort != null
        ) {
            runQuickConnectionCheck()
        } else if (vpnRuntime.status != VpnRunStatus.CONNECTED) {
            quickConnectionCheckJob?.cancel()
            connectionCheck = ConnectionCheckState.Idle
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

    excludedBrowserTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { excludedBrowserTarget = null },
            title = { androidx.compose.material3.Text("Браузер откроет сайт напрямую") },
            text = {
                androidx.compose.material3.Text(
                    "Вы исключили браузер из VPN. Поэтому «Открыть» не проверит " +
                        "маршрут SA05 для ${target.label}."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url)))
                    excludedBrowserTarget = null
                }) {
                    androidx.compose.material3.Text("Открыть напрямую")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { excludedBrowserTarget = null }
                ) {
                    androidx.compose.material3.Text("Отмена")
                }
            }
        )
    }

    if (showTelegramExplainer) {
        AlertDialog(
            onDismissRequest = { showTelegramExplainer = false },
            title = { androidx.compose.material3.Text("Подключаем Telegram") },
            text = {
                androidx.compose.material3.Text(
                    "Сейчас откроется Telegram. Подтвердите использование прокси в самом Telegram. " +
                        "Этот режим запускает только Telegram Proxy и не включает VPN."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showTelegramExplainer = false
                    scope.launch { XrayPreferences.markTelegramProxyExplainerSeen(context) }
                    applyTelegramProxy()
                }) { androidx.compose.material3.Text("Открыть Telegram") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showTelegramExplainer = false
                }) { androidx.compose.material3.Text("Позже") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (authorized && importedSubscription == null && screen.isTopLevel()) {
                AppNavigationBar(screen = screen, onSelect = { screen = it })
            }
        }
    ) { padding ->
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
                    when {
                        targetState.navDepth() == initialState.navDepth() -> fadeTransform(motionOn)
                        targetState.navDepth() > initialState.navDepth() -> forwardTransform(motionOn)
                        else -> backTransform(motionOn)
                    }
                },
                label = "screen"
            ) { target ->
              Column(Modifier.fillMaxSize()) {
                val firstImport = importedSubscription
                if (firstImport != null) {
                    SubscriptionReadyScreen(
                        subscription = firstImport,
                        onConnect = {
                            importedSubscription = null
                            screen = AppScreen.MAIN
                            requestStart()
                        },
                        onContinue = {
                            importedSubscription = null
                            screen = AppScreen.MAIN
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (target == null) {
                    AuthScreen(
                        url = urlDraft,
                        updating = updating,
                        errorMessage = subscriptionError,
                        telegramRuntime = telegramRuntime,
                        onUrlChanged = {
                            urlDraft = it
                            subscriptionError = null
                        },
                        onPaste = { pasteSubscriptionUrl() },
                        onSubmit = { updateSubscription(urlDraft) },
                        onStartTelegram = { startTelegramOnly() },
                        onOpenTelegram = { applyTelegramProxy() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else when (target) {
                AppScreen.MAIN -> MainScreen(
                    subscription = subscription,
                    vpnRuntime = vpnRuntime,
                    connectionCheck = connectionCheck,
                    telegramRuntime = telegramRuntime,
                    updateState = updateState,
                    trafficUsage = trafficUsage,
                    onSelectProfile = { id ->
                        when (
                            profileSwitchAction(
                                currentProfileId = subscription.activeProfile?.id.orEmpty(),
                                selectedProfileId = id,
                                runtimeStatus = VpnRuntimeState.read(context).status
                            )
                        ) {
                            ProfileSwitchAction.NO_CHANGE -> Unit
                            ProfileSwitchAction.SAVE_ONLY -> scope.launch {
                                repository.setActiveProfile(id)
                                pingResults = emptyMap()
                            }
                            ProfileSwitchAction.SAVE_AND_RECONNECT -> scope.launch {
                                val updated = repository.setActiveProfile(id)
                                pingResults = emptyMap()
                                XrayVpnService.reconnect(context)
                                message = "Переподключение: " +
                                    updated.activeProfile?.remarks.orEmpty()
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
                    onStartTelegram = { startTelegramOnly() },
                    onStopTelegram = { BackendController.stopTelegramOnly(context) },
                    onOpenTelegram = { applyTelegramProxy() },
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
                    onOpenUpdate = { screen = AppScreen.SETTINGS },
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
                        advancedModeEnabled = preferences.advancedModeEnabled,
                        onRunDiagnostics = { runDiagnostics() },
                        onCancelDiagnostics = { stopDiagnostics() },
                        onOpenTarget = { target -> openDiagnosticTarget(target) },
                        modifier = Modifier.weight(1f)
                    )
                }
                AppScreen.SETTINGS -> SettingsScreen(
                    subscription = subscription,
                    url = urlDraft,
                    updating = updating,
                    dynamicColor = preferences.dynamicColor,
                    advancedModeEnabled = preferences.advancedModeEnabled,
                    onBack = { screen = AppScreen.MAIN },
                    onUrlChanged = { urlDraft = it },
                    onUpdate = { updateSubscription(urlDraft) },
                    onDynamicColorChanged = onDynamicColorChanged,
                    onAdvancedModeChanged = { enabled ->
                        scope.launch {
                            val shouldReconnect = !enabled &&
                                preferences.vpnBackend != VpnBackend.PROXY_ONLY &&
                                backendState != VpnRunStatus.DISCONNECTED
                            XrayPreferences.saveAdvancedModeEnabled(context, enabled)
                            if (shouldReconnect) {
                                XrayVpnService.reconnect(context)
                                message = "Включаем обычный режим через выбранный сервер"
                            }
                        }
                    },
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
                    selectedBackend = selectedBackend,
                    zapretPreset = zapretPreset,
                    customZapretArguments = customZapretArguments,
                    telegramCfEnabled = telegramCfEnabled,
                    telegramCfDomain = telegramCfDomain,
                    onBack = { screen = AppScreen.SETTINGS },
                    onSelectBackend = { backend ->
                        if (backend != selectedBackend) {
                            val wasRunning = backendState != VpnRunStatus.DISCONNECTED
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
                    onHosts = { screen = AppScreen.HOSTS },
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
                            TelegramProxyService.reload(context)
                        }
                    },
                    onTelegramCfDomainChanged = { telegramCfDomain = it },
                    onSaveTelegramCfDomain = {
                        scope.launch {
                            XrayPreferences.saveTelegramCfDomain(context, telegramCfDomain)
                            message = "Настройки Telegram Proxy сохранены"
                            TelegramProxyService.reload(context)
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
                        isBsProfile = BsProfile.isBs(subscription.activeProfile),
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
                        vpnRunning = backendState != VpnRunStatus.DISCONNECTED,
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
                        onReconnect = {
                            scope.launch {
                                XrayPreferences.saveExcludedApps(context, selectedApps)
                                XrayVpnService.reconnect(context)
                                message = "Переподключаем VPN и применяем исключения"
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
