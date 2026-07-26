package com.fife.sa05

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class XrayVpnService : VpnService() {
    companion object {
        const val STATE_CONNECTING = "Подключение..."
        const val STATE_CONNECTED = "VPN подключён"
        const val ACTION_START = "com.fife.sa05.START"
        const val ACTION_STOP = "com.fife.sa05.STOP"
        const val ACTION_RECONNECT = "com.fife.sa05.RECONNECT"
        private const val CHANNEL_ID = "xray_vpn"
        private const val NOTIFICATION_ID = 10
        private const val ZAPRET_SOCKS_PORT = 10810
        private const val ZAPRET_BRIDGE_PORT = 10811
        private const val ZAPRET_AUTO_ALGORITHM_VERSION = 4
        private const val YOUTUBE_AUTO_ALGORITHM_VERSION = 3
        private const val NETWORK_DEBOUNCE_MS = 1_500L
        private const val RECOVERY_RETRY_MS = 3_000L
        private const val PROCESS_MONITOR_MS = 3_000L
        private val _socksPort = MutableStateFlow<Int?>(null)
        val socksPort = _socksPort.asStateFlow()
        private val _traffic = MutableStateFlow(VpnTraffic())
        val traffic = _traffic.asStateFlow()
        private val _zapretAutoProgress = MutableStateFlow(ZapretAutoProgress())
        val zapretAutoProgress = _zapretAutoProgress.asStateFlow()
        @Volatile private var verificationMessage = ""

        fun start(context: Context) {
            val intent = Intent(context, XrayVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, XrayVpnService::class.java).setAction(ACTION_STOP))
        }

        fun reconnect(context: Context) {
            val intent = Intent(context, XrayVpnService::class.java).setAction(ACTION_RECONNECT)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    private var fullAutoOptimizationJob: Job? = null
    private var networkRecoveryJob: Job? = null
    private var processMonitorJob: Job? = null
    private val tunController by lazy { TunController({ Builder() }, packageName) }
    private var proxyProcess: Process? = null
    private var auxiliaryProcess: Process? = null
    private var bridgeProcess: Process? = null
    private var tun2socksProcess: Process? = null
    private var xrayRuntime = XrayRuntime.STOPPED
    private val supervisor = ProcessSupervisor()
    private val trafficCounter = UidTrafficCounter()
    /** Preset the currently running ByeDPI process was started with, for targeted restarts. */
    private var runningZapretPreset: ZapretPreset? = null
    /** SOCKS port the currently running tun2socks was pointed at, for targeted restarts. */
    private var tun2socksPort: Int? = null
    private var runningBackend = VpnBackend.PROXY_ONLY
    private var runningSettings = XraySettings(config = XrayPreferences.defaultConfig)
    private var runningProfile: SubscriptionProfile? = null
    private var runningLabel = ""
    private var activeNetworkKey: String? = null
    private var explicitStop = false
    @Volatile
    private var telegramStarted = false
    private val startGeneration = AtomicLong()
    private val startMutex = Mutex()
    private data class BackendStart(val socksPort: Int, val tunnelReady: Boolean = false)
    private data class AutoPresetResolution(
        val preset: ZapretPreset,
        val verified: Boolean,
        val score: Int
    )
    private data class ActiveNetwork(
        val key: String,
        val type: VpnNetworkType
    )

    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkRecoveryCheck()

        override fun onLost(network: Network) = scheduleNetworkRecoveryCheck()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) = scheduleNetworkRecoveryCheck()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            scheduleNetworkRecoveryCheck()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w("XrayVpnService", "Network callback unavailable", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (vpnServiceCommand(intent?.action)) {
            VpnServiceCommand.START -> beginStart()
            VpnServiceCommand.STOP -> stopTunnel()
            VpnServiceCommand.IGNORE -> Unit
        }
        return Service.START_NOT_STICKY
    }

    private fun beginStart(recoveryAttempt: Int? = null) {
        explicitStop = false
        startJob?.cancel()
        fullAutoOptimizationJob?.cancel()
        val generation = startGeneration.incrementAndGet()
        startForegroundNow(
            if (recoveryAttempt == null) STATE_CONNECTING else "Восстановление"
        )
        startJob = scope.launch { prepareAndStart(generation, recoveryAttempt) }
    }

    private suspend fun prepareAndStart(generation: Long, recoveryAttempt: Int?) {
        runningSettings = XrayPreferences.snapshot(this)
        runningBackend = effectiveVpnBackend(runningSettings)
        if (!SubscriptionAuth.isAuthorized(runningSettings.subscription)) {
            _socksPort.value = null
            publishRuntime(
                status = VpnRunStatus.ERROR,
                message = "Нужна действующая подписка",
                failureKind = VpnFailureKind.AUTHORIZATION
            )
            stopSelf()
            return
        }
        runningProfile = runningSettings.subscription.activeProfile
            .takeIf { runningBackend.usesXrayProfile }
        runningLabel = selectedLabel()
        val network = currentNetwork()
        if (network == null) {
            publishRuntime(
                status = VpnRunStatus.WAITING_FOR_NETWORK,
                message = "VPN продолжит запуск после появления сети",
                failureKind = VpnFailureKind.NETWORK,
                recoveryAttempt = recoveryAttempt ?: 0
            )
            startForegroundNow("Ожидание сети")
            return
        }
        val status = if (recoveryAttempt == null) {
            VpnRunStatus.CONNECTING
        } else {
            VpnRunStatus.RECOVERING
        }
        publishRuntime(
            status = status,
            message = if (recoveryAttempt == null) {
                "Запускаем ${runningBackend.title}"
            } else {
                "Переподключение после смены сети"
            },
            recoveryAttempt = recoveryAttempt ?: 0,
            components = startingComponents()
        )
        startForegroundNow(if (status == VpnRunStatus.CONNECTING) STATE_CONNECTING else "Восстановление")
        startTunnel(generation, recoveryAttempt)
    }

    private suspend fun startTunnel(generation: Long, recoveryAttempt: Int?) {
        startMutex.withLock {
            try {
                stopProcesses()
                val backend = when (runningBackend) {
                    VpnBackend.PROXY_ONLY -> BackendStart(startXrayBackend())
                    VpnBackend.LOCAL_BYPASS -> {
                        startTelegramProxy()
                        startZapretBackend()
                    }
                    VpnBackend.FULL_AUTO -> {
                        startTelegramProxy()
                        startFullAutoBackend()
                    }
                }
                check(generation == startGeneration.get()) { "Запуск отменён" }
                _socksPort.value = backend.socksPort
                if (!backend.tunnelReady) {
                    startTun2socks(createTun(), backend.socksPort)
                }
                check(generation == startGeneration.get()) { "Запуск отменён" }
                activeNetworkKey = currentNetwork()?.key
                trafficCounter.start()
                _traffic.value = VpnTraffic()
                publishRuntime(
                    status = VpnRunStatus.CONNECTED,
                    message = "Маршрут запущен",
                    connectedAtMillis = System.currentTimeMillis(),
                    components = componentSnapshots()
                )
                startForegroundNow(STATE_CONNECTED)
                startProcessMonitor()
                if (runningBackend == VpnBackend.FULL_AUTO) {
                    launchFullAutoOptimization(generation, backend.socksPort)
                } else if (runningBackend == VpnBackend.PROXY_ONLY ||
                    (runningBackend == VpnBackend.LOCAL_BYPASS &&
                        runningSettings.zapretPreset != ZapretPreset.AUTO)
                ) {
                    verifyRunningTunnel()
                }
                if (runningBackend == VpnBackend.FULL_AUTO) {
                    verifyRunningTunnel()
                }
            } catch (e: Exception) {
                if (generation != startGeneration.get()) return@withLock
                Log.e("XrayVpnService", "Tunnel startup failed", e)
                _socksPort.value = null
                _zapretAutoProgress.value = ZapretAutoProgress(
                    message = e.message ?: "Ошибка запуска"
                )
                stopProcesses()
                if (currentNetwork() == null) {
                    publishRuntime(
                        status = VpnRunStatus.WAITING_FOR_NETWORK,
                        message = "Сеть пропала во время запуска; ждём восстановления",
                        failureKind = VpnFailureKind.NETWORK,
                        recoveryAttempt = recoveryAttempt ?: 0,
                        components = componentSnapshots(failed = true)
                    )
                    startForegroundNow("Ожидание сети")
                    return@withLock
                }
                val recoveryDecision = if (recoveryAttempt != null) {
                    NetworkRecoveryPolicy.routeChecked(
                        healthy = false,
                        automaticAttempts = recoveryAttempt
                    )
                } else {
                    NetworkRecoveryDecision.FAIL
                }
                if (recoveryDecision == NetworkRecoveryDecision.RECONNECT) {
                    val completedAttempt = checkNotNull(recoveryAttempt)
                    publishRuntime(
                        status = VpnRunStatus.RECOVERING,
                        message = "Повторная попытка восстановления",
                        failureKind = VpnFailureKind.HEALTH_CHECK,
                        recoveryAttempt = completedAttempt,
                        components = componentSnapshots(failed = true)
                    )
                    startForegroundNow("Повторная попытка")
                    scope.launch {
                        delay(RECOVERY_RETRY_MS)
                        if (generation == startGeneration.get()) {
                            beginStart(completedAttempt + 1)
                        }
                    }
                } else {
                    publishRuntime(
                        status = VpnRunStatus.ERROR,
                        message = e.message ?: "Ошибка запуска",
                        failureKind = failureKindFor(e),
                        recoveryAttempt = recoveryAttempt ?: 0,
                        components = componentSnapshots(failed = true)
                    )
                    startForegroundNow("Нужна проверка")
                }
            }
        }
    }

    private suspend fun startXrayBackend(fullAuto: Boolean = false): Int {
        val rawConfig = XrayConfig.applyBeelinePadding(
            runningProfile?.json ?: runningSettings.config
        )
        val validated = if (fullAuto) {
            XrayConfig.buildFullAutoConfig(rawConfig, ZAPRET_BRIDGE_PORT)
        } else {
            XrayConfig.validate(rawConfig)
        }
        copyGeoAssets()
        val configFile = File(filesDir, "config.json").apply {
            writeText(validated.runtimeJson)
        }
        val binary = File(applicationInfo.nativeLibraryDir, "libxray.so")
        check(binary.exists()) { "libxray.so не найден" }
        proxyProcess = ProcessBuilder(
            binary.absolutePath,
            "run",
            "-config",
            configFile.absolutePath
        )
            .directory(filesDir)
            .redirectErrorStream(true)
            .apply { environment()["XRAY_LOCATION_ASSET"] = filesDir.absolutePath }
            .start()
        pipeLogs("xray", proxyProcess!!)
        waitForPort(proxyProcess!!, validated.socksPort, 10_000)
        xrayRuntime = if (fullAuto) {
            XrayRuntime.FULL_AUTO_YOUTUBE
        } else {
            XrayRuntime.PLAIN_PROFILE
        }
        return validated.socksPort
    }

    private suspend fun startZapretBackend(): BackendStart {
        val selected = runningSettings.zapretPreset
        if (selected == ZapretPreset.AUTO) {
            val resolved = resolveAutoPreset()
            runningLabel = "[BETA] Авто → ${resolved.preset.title} · Telegram"
            if (resolved.verified) {
                _zapretAutoProgress.value = ZapretAutoProgress(
                    message = "Локальный маршрут готов"
                )
                verificationMessage = "Локальный маршрут проверен"
            } else {
                _zapretAutoProgress.value = ZapretAutoProgress(
                    message = "Строгая проверка не прошла, используется лучший пресет"
                )
                verificationMessage =
                    "Строгая проверка не прошла: лучший результат " +
                        "${resolved.score} из ${ConnectivityDiagnostics.dpiTargetIds.size}; " +
                        "используется ${resolved.preset.title}"
            }
            return BackendStart(ZAPRET_BRIDGE_PORT, tunnelReady = true)
        }
        runningLabel = "[BETA] ${selected.title} · Telegram"
        _zapretAutoProgress.value = ZapretAutoProgress()
        proxyProcess = createZapretProcess(selected)
        pipeLogs("ByeDPI", proxyProcess!!)
        waitForPort(proxyProcess!!, ZAPRET_SOCKS_PORT, 5_000)
        startZapretBridge()
        return BackendStart(ZAPRET_BRIDGE_PORT)
    }

    private suspend fun resolveAutoPreset(): AutoPresetResolution {
        val networkKey = networkKey()
        val diagnostics = ConnectivityDiagnostics()
        _zapretAutoProgress.value = ZapretAutoProgress(
            running = true,
            preset = "Прямая сеть",
            message = "Проверяем, нужен ли обход"
        )
        startForegroundNow("Проверка прямой сети")
        val directResults = diagnostics.runDirect(
            targetsToTest = ConnectivityDiagnostics.autoTargets,
            onResult = { result ->
                _zapretAutoProgress.value = _zapretAutoProgress.value.copy(
                    target = result.target.label
                )
            }
        )
        val cached = XrayPreferences.zapretAutoCache(this, networkKey)
            ?.takeIf {
                it.networkKey == networkKey &&
                    it.algorithmVersion == ZAPRET_AUTO_ALGORITHM_VERSION
            }
            ?.preset
        val candidates = buildList {
            if (ConnectivityDiagnostics.bypassWorks(directResults)) add(ZapretPreset.DIRECT)
            if (cached != null) add(cached)
            addAll(ZapretPreset.testable)
        }.distinct()
        val scores = mutableListOf<Pair<ZapretPreset, Int>>()
        for ((index, preset) in candidates.withIndex()) {
            var candidatePassed = false
            _zapretAutoProgress.value = ZapretAutoProgress(
                running = true,
                preset = preset.title,
                tested = index,
                total = candidates.size,
                message = "Подбираем стратегию"
            )
            startForegroundNow("Подбор ByeDPI ${index + 1}/${candidates.size}")
            try {
                prepareZapretCandidate(preset)
                delay(250)
                check(isProcessAlive(tun2socksProcess)) {
                    "tun2socks завершился во время проверки"
                }
                val results = testZapretCandidate(diagnostics)
                val score = ConnectivityDiagnostics.bypassScore(results)
                scores += preset to score
                if (ConnectivityDiagnostics.bypassWorks(results)) {
                    candidatePassed = true
                    XrayPreferences.saveZapretAutoCache(
                        this,
                        ZapretAutoCache(
                            networkKey,
                            preset,
                            score,
                            ZAPRET_AUTO_ALGORITHM_VERSION
                        )
                    )
                    return AutoPresetResolution(
                        preset = preset,
                        verified = true,
                        score = score
                    )
                }
            } catch (e: Exception) {
                Log.w("ByeDPI", "Preset ${preset.name} test failed", e)
            } finally {
                if (!candidatePassed) stopTransportProcesses()
            }
        }
        val best = ZapretAutoSelection.fallback(scores)
            ?: error("ByeDPI не запустился ни с одной стратегией")
        prepareZapretCandidate(best.first)
        XrayPreferences.saveZapretAutoCache(
            this,
            ZapretAutoCache(
                networkKey,
                best.first,
                best.second,
                ZAPRET_AUTO_ALGORITHM_VERSION
            )
        )
        return AutoPresetResolution(
            preset = best.first,
            verified = false,
            score = best.second
        )
    }

    private suspend fun testZapretCandidate(
        diagnostics: ConnectivityDiagnostics
    ): List<DiagnosticResult> {
        val results = mutableListOf<DiagnosticResult>()
        val controls = ConnectivityDiagnostics.autoTargets.filter {
            it.group == DiagnosticGroup.CONTROL
        }
        for (target in controls) {
            _zapretAutoProgress.value = _zapretAutoProgress.value.copy(
                target = target.label
            )
            results += diagnostics.runSocks(
                ZAPRET_BRIDGE_PORT,
                resolveForSocks = true,
                targetsToTest = listOf(target)
            ).single()
        }
        if (!ConnectivityDiagnostics.controlWorks(results)) return results

        val dpiTargets = ConnectivityDiagnostics.autoTargets.filter {
            it.group == DiagnosticGroup.DPI
        }
        for ((index, target) in dpiTargets.withIndex()) {
            _zapretAutoProgress.value = _zapretAutoProgress.value.copy(
                target = target.label
            )
            results += diagnostics.runSocks(
                ZAPRET_BRIDGE_PORT,
                resolveForSocks = true,
                targetsToTest = listOf(target)
            ).single()
            val score = ConnectivityDiagnostics.bypassScore(results)
            if (score >= ConnectivityDiagnostics.REQUIRED_DPI_SUCCESSES) break
            val remaining = dpiTargets.size - index - 1
            if (score + remaining < ConnectivityDiagnostics.REQUIRED_DPI_SUCCESSES) break
        }
        return results
    }

    private suspend fun prepareZapretCandidate(preset: ZapretPreset) {
        stopTransportProcesses()
        proxyProcess = createZapretProcess(preset)
        pipeLogs("ByeDPI", proxyProcess!!)
        waitForPort(proxyProcess!!, ZAPRET_SOCKS_PORT, 3_000)
        startZapretBridge()
        startTun2socks(createTun(), ZAPRET_BRIDGE_PORT)
    }

    private suspend fun startFullAutoBackend(): BackendStart {
        val socksPort = startXrayBackend(fullAuto = false)
        runningLabel = "[BETA] ${selectedProfileLabel()} · Xray · Telegram"
        _zapretAutoProgress.value = ZapretAutoProgress(
            message = "VPN запущен через Xray, подбираем локальный обход"
        )
        verificationMessage =
            "VPN уже работает через Xray; YouTube-обход подбирается в фоне"
        return BackendStart(socksPort)
    }

    private fun launchFullAutoOptimization(generation: Long, socksPort: Int) {
        fullAutoOptimizationJob?.cancel()
        fullAutoOptimizationJob = scope.launch {
            runCatching {
                optimizeFullAutoYoutube(generation, socksPort)
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                if (generation != startGeneration.get()) return@onFailure
                Log.w("ByeDPI-YouTube", "Background optimization failed", error)
                ensurePlainXrayRunning(socksPort)
                stopFullAutoOptimizationProcesses()
                XrayPreferences.clearYoutubeAutoCache(this@XrayVpnService)
                runningLabel = "[BETA] ${selectedProfileLabel()} · YouTube через Xray · Telegram"
                _zapretAutoProgress.value = ZapretAutoProgress(
                    message = "Локальный обход не подошёл, используем Xray"
                )
                verificationMessage =
                    "YouTube оставлен через Xray: локальные стратегии не прошли проверку"
                writeRuntime(VpnRunStatus.CONNECTED)
                startForegroundNow(STATE_CONNECTED)
            }
        }
    }

    private suspend fun optimizeFullAutoYoutube(generation: Long, socksPort: Int) {
        val networkKey = networkKey()
        val cached = XrayPreferences.youtubeAutoCache(this, networkKey)
            ?.takeIf {
                it.networkKey == networkKey &&
                    it.algorithmVersion == YOUTUBE_AUTO_ALGORITHM_VERSION
            }
            ?.preset
        val candidates = buildList {
            if (cached != null) add(cached)
            addAll(ZapretPreset.youtubeTestable)
        }.distinct()
        for ((index, preset) in candidates.withIndex()) {
            _zapretAutoProgress.value = ZapretAutoProgress(
                running = true,
                preset = preset.title,
                target = ConnectivityDiagnostics.youtubeAutoTargets.first().label,
                tested = index,
                total = candidates.size,
                message = "Проверяем полный маршрут YouTube"
            )
            startForegroundNow("YouTube: стратегия ${index + 1}/${candidates.size}")
            stopFullAutoOptimizationProcesses()
            try {
                check(generation == startGeneration.get()) { "Запуск отменён" }
                auxiliaryProcess = createZapretProcess(preset)
                pipeLogs("ByeDPI-YouTube", auxiliaryProcess!!)
                waitForPort(auxiliaryProcess!!, ZAPRET_SOCKS_PORT, 3_000)
                val preflight = ConnectivityDiagnostics().runSocks(
                    ZAPRET_SOCKS_PORT,
                    resolveForSocks = true,
                    targetsToTest = listOf(ConnectivityDiagnostics.youtubeAutoTargets.first())
                ).single()
                check(preflight.reachable) {
                    "Preflight: ${preflight.error.ifBlank { "YouTube недоступен" }}"
                }
                startZapretBridge()
                switchXrayToFullAuto(socksPort, generation)
                _zapretAutoProgress.value = _zapretAutoProgress.value.copy(
                    target = "YouTube Media"
                )
                val playback = ConnectivityDiagnostics().probeYoutubePlaybackSocks(
                    socksPort,
                    resolveForSocks = true
                )
                check(playback.reachable) {
                    "${playback.target.label}: " +
                        playback.error.ifBlank { "видеоданные недоступны" }
                }
                var failed: DiagnosticResult? = null
                for (target in ConnectivityDiagnostics.youtubeAutoTargets) {
                    _zapretAutoProgress.value = _zapretAutoProgress.value.copy(
                        target = target.label
                    )
                    val result = ConnectivityDiagnostics().runSocks(
                        socksPort,
                        resolveForSocks = true,
                        targetsToTest = listOf(target)
                    ).single()
                    if (!result.reachable) {
                        failed = result
                        break
                    }
                }
                check(failed == null && isProcessAlive(proxyProcess) &&
                    isProcessAlive(bridgeProcess) && isProcessAlive(auxiliaryProcess)
                ) {
                    failed?.let {
                        "${it.target.label}: ${it.error.ifBlank { "недоступен" }}"
                    } ?: "Один из процессов завершился"
                }
                XrayPreferences.saveYoutubeAutoCache(
                    this,
                    ZapretAutoCache(
                        networkKey,
                        preset,
                        1,
                        YOUTUBE_AUTO_ALGORITHM_VERSION
                    )
                )
                runningLabel =
                    "[BETA] ${selectedProfileLabel()} · YouTube: ${preset.title} · Telegram"
                _zapretAutoProgress.value = ZapretAutoProgress(
                    message = "YouTube работает через Xray → ByeDPI"
                )
                verificationMessage =
                    "YouTube проверен через локальный обход: ${preset.title}"
                writeRuntime(VpnRunStatus.CONNECTED)
                startForegroundNow(STATE_CONNECTED)
                return
            } catch (e: Exception) {
                Log.w("ByeDPI-YouTube", "Preset ${preset.name} failed", e)
                ensurePlainXrayRunning(socksPort)
                stopFullAutoOptimizationProcesses()
            }
        }
        error("Локальный YouTube-обход не прошёл проверку")
    }

    private suspend fun switchXrayToFullAuto(expectedSocksPort: Int, generation: Long) {
        check(generation == startGeneration.get()) { "Запуск отменён" }
        closeProcess(proxyProcess)
        proxyProcess = null
        xrayRuntime = XrayRuntime.STOPPED
        try {
            val socksPort = startXrayBackend(fullAuto = true)
            check(socksPort == expectedSocksPort) {
                "SOCKS-порт Xray изменился с $expectedSocksPort на $socksPort"
            }
        } catch (e: Exception) {
            ensurePlainXrayRunning(expectedSocksPort)
            throw e
        }
    }

    private suspend fun ensurePlainXrayRunning(expectedSocksPort: Int) {
        if (isProcessAlive(proxyProcess) && xrayRuntime == XrayRuntime.PLAIN_PROFILE) return
        closeProcess(proxyProcess)
        proxyProcess = null
        xrayRuntime = XrayRuntime.STOPPED
        val socksPort = startXrayBackend(fullAuto = false)
        check(socksPort == expectedSocksPort) {
            "SOCKS-порт Xray изменился с $expectedSocksPort на $socksPort"
        }
    }

    private suspend fun startTelegramProxy() {
        TelegramProxyService.acquireForVpn(this)
        if (!TelegramProxyService.awaitRunning(this)) {
            TelegramProxyService.releaseForVpn(this)
            error(
                TelegramProxyRuntimeState.read(this).message
                    .ifBlank { "Telegram Proxy не открыл порт ${TelegramProxyConfig.PORT}" }
            )
        }
        telegramStarted = true
    }

    private fun verifyRunningTunnel() {
        verificationMessage = "Проверяем полный VPN-маршрут..."
        scope.launch {
            delay(250)
            val results = ConnectivityDiagnostics().runSocks(
                _socksPort.value ?: return@launch,
                resolveForSocks = runningBackend == VpnBackend.LOCAL_BYPASS,
                targetsToTest = ConnectivityDiagnostics.autoTargets
            )
            val tunnelAlive = isProcessAlive(tun2socksProcess) && tunController.established
            val pingMs = results.firstOrNull {
                it.reachable && it.target.group == DiagnosticGroup.CONTROL
            }?.delayMs
            verificationMessage = if (
                tunnelAlive && ConnectivityDiagnostics.bypassWorks(results)
            ) {
                pingMs?.let { "Пинг: $it мс" } ?: "Подключение проверено"
            } else {
                "VPN включён, но обход ограничений не подтверждён"
            }
            val runtime = VpnRuntimeState.read(this@XrayVpnService)
            if (runtime.status == VpnRunStatus.CONNECTED) {
                publishRuntime(
                    status = VpnRunStatus.CONNECTED,
                    message = verificationMessage,
                    connectedAtMillis = runtime.connectedAtMillis,
                    components = componentSnapshots()
                )
            }
        }
    }

    private fun createZapretProcess(preset: ZapretPreset): Process {
        val binary = File(applicationInfo.nativeLibraryDir, "libciadpi.so")
        check(binary.exists()) { "ByeDPI не найден" }
        runningZapretPreset = preset
        return ProcessBuilder(
            ZapretCommand.build(
                binary.absolutePath,
                ZAPRET_SOCKS_PORT,
                preset,
                runningSettings.zapretCustomArguments
            )
        )
            .directory(filesDir)
            .redirectErrorStream(true)
            .start()
    }

    private suspend fun startZapretBridge() {
        val binary = File(applicationInfo.nativeLibraryDir, "libxray.so")
        check(binary.exists()) { "libxray.so не найден" }
        val configFile = File(filesDir, "zapret-bridge.json").apply {
            writeText(
                ZapretBridgeConfig.build(
                    inboundPort = ZAPRET_BRIDGE_PORT,
                    upstreamPort = ZAPRET_SOCKS_PORT
                )
            )
        }
        bridgeProcess = ProcessBuilder(
            binary.absolutePath,
            "run",
            "-config",
            configFile.absolutePath
        )
            .directory(filesDir)
            .redirectErrorStream(true)
            .start()
        pipeLogs("ZapretBridge", bridgeProcess!!)
        waitForPort(bridgeProcess!!, ZAPRET_BRIDGE_PORT, 5_000)
    }

    private fun scheduleNetworkRecoveryCheck() {
        val runtime = VpnRuntimeState.read(this)
        if (!runtime.requested || runtime.failureKind == VpnFailureKind.AUTHORIZATION) return
        networkRecoveryJob?.cancel()
        networkRecoveryJob = scope.launch {
            delay(NETWORK_DEBOUNCE_MS)
            handleNetworkRecoveryCheck()
        }
    }

    private suspend fun handleNetworkRecoveryCheck() {
        val runtime = VpnRuntimeState.read(this)
        if (!runtime.requested || runtime.failureKind == VpnFailureKind.AUTHORIZATION) return
        val network = currentNetwork()
        if (network == null) {
            publishRuntime(
                status = VpnRunStatus.WAITING_FOR_NETWORK,
                message = "VPN продолжит работу после появления сети",
                failureKind = VpnFailureKind.NETWORK,
                connectedAtMillis = runtime.connectedAtMillis,
                components = componentSnapshots()
            )
            startForegroundNow("Ожидание сети")
            return
        }
        if (runtime.status == VpnRunStatus.CONNECTING ||
            runtime.status == VpnRunStatus.RECOVERING
        ) {
            return
        }
        if (runtime.status == VpnRunStatus.WAITING_FOR_NETWORK) {
            beginStart(recoveryAttempt = 1)
            return
        }
        if (runtime.status == VpnRunStatus.ERROR) {
            if (runtime.networkKey.isNotBlank() && runtime.networkKey != network.key) {
                beginStart(recoveryAttempt = 1)
            }
            return
        }
        if (_socksPort.value == null) {
            beginStart(recoveryAttempt = 1)
            return
        }
        when (NetworkRecoveryPolicy.networkChanged(activeNetworkKey, network.key)) {
            NetworkRecoveryDecision.NONE -> {
                if (runtime.networkType != network.type) {
                    VpnRuntimeState.publish(
                        this,
                        runtime.copy(networkType = network.type, networkKey = network.key)
                    )
                }
            }
            NetworkRecoveryDecision.WAIT_FOR_NETWORK -> Unit
            NetworkRecoveryDecision.VERIFY_ROUTE -> verifyRouteAfterNetworkChange(network)
            NetworkRecoveryDecision.RECONNECT -> beginStart(recoveryAttempt = 1)
            NetworkRecoveryDecision.FAIL -> publishRuntime(
                status = VpnRunStatus.ERROR,
                message = "VPN не удалось восстановить после смены сети",
                failureKind = VpnFailureKind.HEALTH_CHECK,
                components = componentSnapshots(failed = true)
            )
        }
    }

    private suspend fun verifyRouteAfterNetworkChange(network: ActiveNetwork) {
        val generation = startGeneration.get()
        val previous = VpnRuntimeState.read(this)
        publishRuntime(
            status = VpnRunStatus.RECOVERING,
            message = "Проверяем маршрут через ${network.type.title}",
            connectedAtMillis = previous.connectedAtMillis,
            components = componentSnapshots()
        )
        startForegroundNow("Проверка VPN-маршрута")
        val port = _socksPort.value
        val healthy = port != null && requiredProcessesRunning() && runCatching {
            val results = ConnectivityDiagnostics().runSocks(
                port,
                resolveForSocks = runningBackend == VpnBackend.LOCAL_BYPASS,
                targetsToTest = ConnectivityDiagnostics.autoTargets
            )
            ConnectivityDiagnostics.bypassWorks(results)
        }.getOrDefault(false)
        if (generation != startGeneration.get()) return
        when (NetworkRecoveryPolicy.routeChecked(healthy, automaticAttempts = 0)) {
            NetworkRecoveryDecision.NONE -> {
                activeNetworkKey = network.key
                publishRuntime(
                    status = VpnRunStatus.CONNECTED,
                    message = "Маршрут восстановлен без переподключения",
                    connectedAtMillis = previous.connectedAtMillis,
                    components = componentSnapshots()
                )
                startForegroundNow(STATE_CONNECTED)
                if (runningBackend == VpnBackend.FULL_AUTO && port != null) {
                    refreshFullAutoForNetwork(generation, port)
                }
            }
            NetworkRecoveryDecision.RECONNECT -> beginStart(recoveryAttempt = 1)
            NetworkRecoveryDecision.FAIL -> {
                publishRuntime(
                    status = VpnRunStatus.ERROR,
                    message = "Проверка маршрута не пройдена",
                    failureKind = VpnFailureKind.HEALTH_CHECK,
                    components = componentSnapshots(failed = true)
                )
                startForegroundNow("Нужна проверка")
            }
            else -> Unit
        }
    }

    private suspend fun refreshFullAutoForNetwork(generation: Long, socksPort: Int) {
        fullAutoOptimizationJob?.cancel()
        runCatching {
            ensurePlainXrayRunning(socksPort)
            stopFullAutoOptimizationProcesses()
            XrayPreferences.clearYoutubeAutoCache(this)
            launchFullAutoOptimization(generation, socksPort)
        }.onFailure {
            Log.w("ByeDPI-YouTube", "Network-change optimization refresh failed", it)
        }
    }

    private fun startProcessMonitor() {
        processMonitorJob?.cancel()
        processMonitorJob = scope.launch {
            while (true) {
                delay(PROCESS_MONITOR_MS)
                val runtime = VpnRuntimeState.read(this@XrayVpnService)
                if (runtime.status != VpnRunStatus.CONNECTED) continue
                if (networkRecoveryJob?.isActive == true) continue
                if (runningBackend == VpnBackend.FULL_AUTO &&
                    fullAutoOptimizationJob?.isActive == true
                ) {
                    val components = componentSnapshots()
                    if (components != runtime.components) {
                        publishRuntime(
                            status = VpnRunStatus.CONNECTED,
                            message = runtime.message,
                            connectedAtMillis = runtime.connectedAtMillis,
                            components = components
                        )
                    }
                    continue
                }
                if (!requiredProcessesRunning()) {
                    if (!recoverDeadProcesses(runtime)) return@launch
                    continue
                }
                supervisedRoles(runningBackend, xrayRuntime).forEach(supervisor::noteHealthy)
                val traffic = trafficCounter.sinceStart()
                if (traffic != _traffic.value) {
                    _traffic.value = traffic
                    startForegroundNow(STATE_CONNECTED)
                }
                val components = componentSnapshots()
                if (components != runtime.components) {
                    publishRuntime(
                        status = VpnRunStatus.CONNECTED,
                        message = runtime.message,
                        connectedAtMillis = runtime.connectedAtMillis,
                        components = components
                    )
                }
            }
        }
    }

    private fun roleProcess(role: SupervisedRole): Process? = when (role) {
        SupervisedRole.XRAY -> proxyProcess
        SupervisedRole.TUN2SOCKS -> tun2socksProcess
        SupervisedRole.BRIDGE -> bridgeProcess
        SupervisedRole.BYEDPI -> when (runningBackend) {
            // Local Bypass runs ByeDPI as the primary process; Full Auto runs it beside Xray.
            VpnBackend.LOCAL_BYPASS -> proxyProcess
            else -> auxiliaryProcess
        }
    }

    private fun deadSupervisedRoles(): List<SupervisedRole> =
        supervisedRoles(runningBackend, xrayRuntime)
            .filterNot { isProcessAlive(roleProcess(it)) }

    /**
     * Restarts just the processes that died, newest failure first, instead of tearing the whole
     * stack down. Returns false when the caller must stop monitoring because a full restart was
     * triggered instead.
     */
    private suspend fun recoverDeadProcesses(runtime: VpnRuntimeSnapshot): Boolean {
        // A lost TUN or a stopped Telegram Proxy cannot be respawned in isolation — those
        // belong to the full start path.
        val dead = deadSupervisedRoles()
        if (!tunController.established || dead.isEmpty()) {
            return fullRestart(runtime, "Один из компонентов VPN остановился")
        }
        val role = dead.first()
        val backoff = supervisor.nextBackoffMs(role)
            ?: return fullRestart(
                runtime,
                "${role.title} не удержался после ${ProcessRestartPolicy.MAX_ATTEMPTS} попыток"
            )
        publishRuntime(
            status = VpnRunStatus.RECOVERING,
            message = "Перезапускаем ${role.title}",
            failureKind = VpnFailureKind.BACKEND,
            connectedAtMillis = runtime.connectedAtMillis,
            components = componentSnapshots()
        )
        delay(backoff)
        val cascade = restartCascade(runningBackend, xrayRuntime, role)
        val restarted = runCatching { cascade.forEach { restartRole(it) } }
        if (restarted.isFailure) {
            Log.w("XrayVpnService", "Targeted restart of ${role.name} failed", restarted.exceptionOrNull())
            return fullRestart(runtime, "Не удалось перезапустить ${role.title}")
        }
        publishRuntime(
            status = VpnRunStatus.CONNECTED,
            message = runtime.message,
            connectedAtMillis = runtime.connectedAtMillis,
            components = componentSnapshots()
        )
        startForegroundNow(STATE_CONNECTED)
        return true
    }

    private fun fullRestart(runtime: VpnRuntimeSnapshot, message: String): Boolean {
        supervisor.reset()
        publishRuntime(
            status = VpnRunStatus.RECOVERING,
            message = message,
            failureKind = VpnFailureKind.BACKEND,
            connectedAtMillis = runtime.connectedAtMillis,
            components = componentSnapshots(failed = true)
        )
        startForegroundNow("Восстановление VPN")
        beginStart(recoveryAttempt = 1)
        return false
    }

    private suspend fun restartRole(role: SupervisedRole) {
        when (role) {
            SupervisedRole.XRAY -> {
                val fullAuto = xrayRuntime == XrayRuntime.FULL_AUTO_YOUTUBE
                closeProcess(proxyProcess)
                proxyProcess = null
                xrayRuntime = XrayRuntime.STOPPED
                startXrayBackend(fullAuto = fullAuto)
            }
            SupervisedRole.BYEDPI -> {
                val preset = runningZapretPreset ?: error("Пресет ByeDPI неизвестен")
                if (runningBackend == VpnBackend.LOCAL_BYPASS) {
                    closeProcess(proxyProcess)
                    proxyProcess = createZapretProcess(preset)
                    pipeLogs("ByeDPI", proxyProcess!!)
                    waitForPort(proxyProcess!!, ZAPRET_SOCKS_PORT, 5_000)
                } else {
                    closeProcess(auxiliaryProcess)
                    auxiliaryProcess = createZapretProcess(preset)
                    pipeLogs("ByeDPI", auxiliaryProcess!!)
                    waitForPort(auxiliaryProcess!!, ZAPRET_SOCKS_PORT, 5_000)
                }
            }
            SupervisedRole.BRIDGE -> {
                closeProcess(bridgeProcess)
                bridgeProcess = null
                startZapretBridge()
            }
            SupervisedRole.TUN2SOCKS -> {
                val port = tun2socksPort ?: error("SOCKS-порт tun2socks неизвестен")
                val fd = tunController.descriptorOrNull ?: error("TUN закрыт")
                closeProcess(tun2socksProcess)
                tun2socksProcess = null
                startTun2socks(fd, port)
            }
        }
    }

    private fun requiredProcessesRunning(): Boolean {
        return requiredProcessesRunning(
            backend = runningBackend,
            xrayRuntime = xrayRuntime,
            health = VpnProcessHealth(
                tun = tunController.established,
                tun2socks = isProcessAlive(tun2socksProcess),
                proxy = isProcessAlive(proxyProcess),
                bridge = isProcessAlive(bridgeProcess),
                auxiliary = isProcessAlive(auxiliaryProcess),
                telegram = TelegramProxyRuntimeState.read(this).running
            )
        )
    }

    private fun startingComponents(): List<VpnComponentSnapshot> =
        relevantComponents().map { VpnComponentSnapshot(it, VpnComponentState.STARTING) }

    private fun componentSnapshots(failed: Boolean = false): List<VpnComponentSnapshot> {
        fun state(running: Boolean): VpnComponentState = when {
            running -> VpnComponentState.RUNNING
            failed -> VpnComponentState.FAILED
            else -> VpnComponentState.STARTING
        }
        return buildList {
            val xrayRunning = when (runningBackend) {
                VpnBackend.LOCAL_BYPASS -> isProcessAlive(bridgeProcess)
                else -> isProcessAlive(proxyProcess)
            }
            add(VpnComponentSnapshot(VpnRuntimeComponent.XRAY, state(xrayRunning)))
            add(VpnComponentSnapshot(VpnRuntimeComponent.TUN, state(tunController.established)))
            add(
                VpnComponentSnapshot(
                    VpnRuntimeComponent.TUN2SOCKS,
                    state(isProcessAlive(tun2socksProcess))
                )
            )
            if (runningBackend != VpnBackend.PROXY_ONLY) {
                val byeDpi = when (runningBackend) {
                    VpnBackend.LOCAL_BYPASS -> state(isProcessAlive(proxyProcess))
                    VpnBackend.FULL_AUTO -> when {
                        isProcessAlive(auxiliaryProcess) -> VpnComponentState.RUNNING
                        xrayRuntime == XrayRuntime.PLAIN_PROFILE -> VpnComponentState.FALLBACK
                        failed -> VpnComponentState.FAILED
                        else -> VpnComponentState.STARTING
                    }
                    else -> VpnComponentState.STARTING
                }
                add(VpnComponentSnapshot(VpnRuntimeComponent.BYEDPI, byeDpi))
                add(
                    VpnComponentSnapshot(
                        VpnRuntimeComponent.TELEGRAM,
                        state(TelegramProxyRuntimeState.read(this@XrayVpnService).running)
                    )
                )
            }
        }
    }

    private fun relevantComponents(): List<VpnRuntimeComponent> = buildList {
        add(VpnRuntimeComponent.XRAY)
        add(VpnRuntimeComponent.TUN)
        add(VpnRuntimeComponent.TUN2SOCKS)
        if (runningBackend != VpnBackend.PROXY_ONLY) {
            add(VpnRuntimeComponent.BYEDPI)
            add(VpnRuntimeComponent.TELEGRAM)
        }
    }

    private fun currentNetwork(): ActiveNetwork? {
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        val link = connectivityManager.getLinkProperties(network)
        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> VpnNetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> VpnNetworkType.MOBILE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> VpnNetworkType.ETHERNET
            else -> VpnNetworkType.OTHER
        }
        val transportKey = when (type) {
            VpnNetworkType.WIFI -> "wifi"
            VpnNetworkType.MOBILE -> "mobile"
            VpnNetworkType.ETHERNET -> "ethernet"
            VpnNetworkType.OTHER -> "other"
            VpnNetworkType.NONE -> "none"
        }
        return ActiveNetwork(
            key = buildString {
                append(network.networkHandle)
                append('|').append(transportKey)
                append('|').append(link?.interfaceName.orEmpty())
                append('|').append(link?.dnsServers?.joinToString(",").orEmpty())
            },
            type = type
        )
    }

    private fun networkKey(): String = currentNetwork()?.key.orEmpty()

    private fun createTun(): ParcelFileDescriptor = tunController.establish(
        session = "SA05 ${runningBackend.title}",
        allowIpv6Bypass = runningSettings.allowIpv6Bypass,
        excludedApps = runningSettings.excludedApps
    )

    private suspend fun startTun2socks(fd: ParcelFileDescriptor, socksPort: Int) {
        val binary = File(applicationInfo.nativeLibraryDir, "libtun2socks.so")
        check(binary.exists()) { "libtun2socks.so не найден" }
        tun2socksPort = socksPort
        val socketFile = File(filesDir, "tun2socks.sock")
        socketFile.delete()
        tun2socksProcess = ProcessBuilder(
            binary.absolutePath,
            "--netif-ipaddr", "10.10.10.2",
            "--netif-netmask", "255.255.255.252",
            "--socks-server-addr", "127.0.0.1:$socksPort",
            "--tunmtu", "1500",
            "--sock-path", socketFile.absolutePath,
            "--enable-udprelay",
            "--loglevel", "notice"
        )
            .directory(filesDir)
            .redirectErrorStream(true)
            .start()
        pipeLogs("tun2socks", tun2socksProcess!!)
        sendTunFd(socketFile, fd)
    }

    private suspend fun sendTunFd(socketFile: File, fd: ParcelFileDescriptor) {
        repeat(50) {
            if (socketFile.exists()) {
                val socket = LocalSocket()
                try {
                    socket.connect(
                        LocalSocketAddress(
                            socketFile.absolutePath,
                            LocalSocketAddress.Namespace.FILESYSTEM
                        )
                    )
                    socket.setFileDescriptorsForSend(arrayOf(fd.fileDescriptor))
                    socket.outputStream.write(42)
                    socket.outputStream.flush()
                    return
                } finally {
                    socket.close()
                }
            }
            delay(40)
        }
        error("tun2socks не создал управляющий сокет")
    }

    private suspend fun waitForPort(process: Process, port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isProcessAlive(process)) error("Прокси завершился при запуске")
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                return
            } catch (_: Exception) {
                delay(100)
            }
        }
        error("Прокси не открыл SOCKS-порт $port")
    }

    private fun pipeLogs(tag: String, process: Process) {
        scope.launch {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        Log.i(tag, it)
                        DiagnosticLog.record(tag, it)
                    }
                }
            } catch (e: Exception) {
                val closedDuringStop = e is InterruptedIOException ||
                    e.message?.contains("interrupted by close", ignoreCase = true) == true
                if (!closedDuringStop && isProcessAlive(process)) {
                    Log.w(tag, "Не удалось прочитать лог", e)
                    DiagnosticLog.record(tag, "log reader failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun isProcessAlive(process: Process?): Boolean {
        if (process == null) return false
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun closeProcess(process: Process?) {
        if (process == null) return
        runCatching { process.inputStream.close() }
        runCatching { process.destroy() }
    }

    private fun copyGeoAssets() {
        listOf("geoip.dat", "geosite.dat").forEach { name ->
            val output = File(filesDir, name)
            if (output.exists() && output.length() > 0) return@forEach
            assets.open(name).use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
        }
    }

    private fun stopTunnel() {
        explicitStop = true
        startGeneration.incrementAndGet()
        startJob?.cancel()
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        processMonitorJob?.cancel()
        processMonitorJob = null
        fullAutoOptimizationJob?.cancel()
        fullAutoOptimizationJob = null
        stopProcesses()
        runningProfile = null
        runningLabel = ""
        _socksPort.value = null
        _zapretAutoProgress.value = ZapretAutoProgress()
        verificationMessage = ""
        VpnRuntimeState.clearIfBackend(this, runningBackend)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopProcesses() {
        fullAutoOptimizationJob?.cancel()
        fullAutoOptimizationJob = null
        stopTransportProcesses()
        if (telegramStarted) {
            telegramStarted = false
            TelegramProxyService.releaseForVpn(this)
        }
    }

    private fun stopFullAutoOptimizationProcesses() {
        closeProcess(bridgeProcess)
        bridgeProcess = null
        closeProcess(auxiliaryProcess)
        auxiliaryProcess = null
    }

    private fun stopTransportProcesses() {
        closeProcess(tun2socksProcess)
        tun2socksProcess = null
        closeProcess(bridgeProcess)
        bridgeProcess = null
        closeProcess(proxyProcess)
        proxyProcess = null
        xrayRuntime = XrayRuntime.STOPPED
        closeProcess(auxiliaryProcess)
        auxiliaryProcess = null
        runningZapretPreset = null
        tun2socksPort = null
        supervisor.reset()
        trafficCounter.reset()
        _traffic.value = VpnTraffic()
        tunController.close()
    }

    private fun selectedLabel(): String = when (runningBackend) {
        VpnBackend.PROXY_ONLY -> selectedProfileLabel()
        VpnBackend.LOCAL_BYPASS ->
            "[BETA] ${runningSettings.zapretPreset.title} · Telegram"
        VpnBackend.FULL_AUTO -> "[BETA] ${selectedProfileLabel()} · локальный обход"
    }

    private fun selectedProfileLabel(): String =
        runningProfile?.remarks.orEmpty().ifBlank { "Локальный профиль" }

    private fun writeRuntime(status: VpnRunStatus) {
        val previous = VpnRuntimeState.read(this)
        publishRuntime(
            status = status,
            message = verificationMessage,
            connectedAtMillis = when {
                status != VpnRunStatus.CONNECTED -> previous.connectedAtMillis
                previous.connectedAtMillis > 0L -> previous.connectedAtMillis
                else -> System.currentTimeMillis()
            },
            components = componentSnapshots()
        )
    }

    private fun publishRuntime(
        status: VpnRunStatus,
        message: String = "",
        failureKind: VpnFailureKind = VpnFailureKind.NONE,
        recoveryAttempt: Int = 0,
        connectedAtMillis: Long? = null,
        components: List<VpnComponentSnapshot> = componentSnapshots()
    ) {
        val previous = VpnRuntimeState.read(this)
        val network = currentNetwork()
        VpnRuntimeState.publish(
            this,
            VpnRuntimeSnapshot(
                status = status,
                backend = runningBackend,
                profileId = runningProfile?.id.orEmpty(),
                profileName = runningLabel.ifBlank { selectedLabel() },
                message = message,
                failureKind = failureKind,
                networkType = network?.type ?: VpnNetworkType.NONE,
                networkKey = network?.key.orEmpty(),
                connectedAtMillis = connectedAtMillis ?: previous.connectedAtMillis,
                automaticRecoveryAttempt = recoveryAttempt,
                components = components
            )
        )
    }

    private fun failureKindFor(error: Throwable): VpnFailureKind {
        val message = error.message.orEmpty().lowercase()
        return when {
            currentNetwork() == null -> VpnFailureKind.NETWORK
            "tun" in message -> VpnFailureKind.TUNNEL
            "proxy" in message || "прокси" in message || "xray" in message ||
                "byedpi" in message || "telegram" in message -> VpnFailureKind.BACKEND
            else -> VpnFailureKind.SERVICE
        }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        networkRecoveryJob?.cancel()
        processMonitorJob?.cancel()
        stopProcesses()
        runningProfile = null
        runningLabel = ""
        _socksPort.value = null
        _zapretAutoProgress.value = ZapretAutoProgress()
        verificationMessage = ""
        val runtime = VpnRuntimeState.read(this)
        if (explicitStop) {
            VpnRuntimeState.clearIfBackend(this, runningBackend)
        } else if (runtime.status != VpnRunStatus.ERROR) {
            VpnRuntimeState.publish(
                this,
                runtime.copy(
                    status = VpnRunStatus.ERROR,
                    message = "VPN-сервис остановлен системой",
                    failureKind = VpnFailureKind.SERVICE,
                    components = componentSnapshots(failed = true)
                )
            )
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNow(text: String) {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val reconnectAction = Intent(this, XrayVpnService::class.java)
            .setAction(ACTION_RECONNECT)
        val reconnectIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                12,
                reconnectAction,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                this,
                12,
                reconnectAction,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val runtime = VpnRuntimeState.read(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_tile)
            .setContentTitle(text)
            .setContentText(
                vpnNotificationContentText(
                    runningProfileName = runtime.profileName,
                    fallbackProfileName = runningLabel.ifBlank { selectedLabel() },
                    traffic = _traffic.value
                )
            )
            .apply {
                // The system ticks the chronometer itself, so uptime stays live without the
                // service waking up for it.
                val connected = runtime.status == VpnRunStatus.CONNECTED &&
                    runtime.connectedAtMillis > 0L
                setUsesChronometer(connected)
                if (connected) setWhen(runtime.connectedAtMillis) else setShowWhen(false)
            }
            .setSubText(runtime.message.ifBlank { "SA05 ${runningBackend.title}" })
            .setContentIntent(openApp)
            .addAction(0, "Отключить", stopIntent)
            .addAction(
                0,
                if (runtime.status == VpnRunStatus.ERROR) "Повторить" else "Переподключить",
                reconnectIntent
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "SA05 VPN",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
