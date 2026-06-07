package com.fife.sa05

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
        const val STATE_DISCONNECTED = "Отключено"
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
        private val _state = MutableStateFlow(STATE_DISCONNECTED)
        val state = _state.asStateFlow()
        private val _socksPort = MutableStateFlow<Int?>(null)
        val socksPort = _socksPort.asStateFlow()
        private val _zapretAutoProgress = MutableStateFlow(ZapretAutoProgress())
        val zapretAutoProgress = _zapretAutoProgress.asStateFlow()
        private val _verificationMessage = MutableStateFlow("")
        val verificationMessage = _verificationMessage.asStateFlow()

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
    private var tun: ParcelFileDescriptor? = null
    private var proxyProcess: Process? = null
    private var auxiliaryProcess: Process? = null
    private var bridgeProcess: Process? = null
    private var tun2socksProcess: Process? = null
    private var runningBackend = VpnBackend.PROXY_ONLY
    private var runningProfile: SubscriptionProfile? = null
    private var runningLabel = ""
    @Volatile
    private var telegramStarted = false
    private val startGeneration = AtomicLong()
    private val startMutex = Mutex()
    private data class BackendStart(val socksPort: Int, val tunnelReady: Boolean = false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START, ACTION_RECONNECT -> beginStart()
        }
        return Service.START_NOT_STICKY
    }

    private fun beginStart() {
        runningBackend = XrayPreferences.vpnBackend(this)
        runningProfile = XrayPreferences.subscription(this).activeProfile
            .takeIf { runningBackend.usesXrayProfile }
        runningLabel = selectedLabel()
        _state.value = STATE_CONNECTING
        writeRuntime(VpnRunStatus.CONNECTING)
        startForegroundNow(STATE_CONNECTING)
        startJob?.cancel()
        val generation = startGeneration.incrementAndGet()
        startJob = scope.launch { startTunnel(generation) }
    }

    private suspend fun startTunnel(generation: Long) {
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
                    tun = createTun()
                    startTun2socks(tun!!, backend.socksPort)
                }
                check(generation == startGeneration.get()) { "Запуск отменён" }
                _state.value = STATE_CONNECTED
                writeRuntime(VpnRunStatus.CONNECTED)
                startForegroundNow(STATE_CONNECTED)
                if (runningBackend == VpnBackend.PROXY_ONLY ||
                    (runningBackend == VpnBackend.LOCAL_BYPASS &&
                        XrayPreferences.zapretPreset(this) != ZapretPreset.AUTO)
                ) {
                    verifyRunningTunnel()
                }
            } catch (e: Exception) {
                if (generation != startGeneration.get()) return@withLock
                Log.e("XrayVpnService", "Tunnel startup failed", e)
                _state.value = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
                _socksPort.value = null
                _zapretAutoProgress.value = ZapretAutoProgress(
                    message = e.message ?: "Ошибка запуска"
                )
                stopProcesses()
                runningProfile = null
                runningLabel = ""
                VpnRuntimeState.clearIfBackend(this, runningBackend)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun startXrayBackend(fullAuto: Boolean = false): Int {
        val rawConfig = runningProfile?.json ?: XrayPreferences.config(this)
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
        return validated.socksPort
    }

    private suspend fun startZapretBackend(): BackendStart {
        val selected = XrayPreferences.zapretPreset(this)
        if (selected == ZapretPreset.AUTO) {
            val resolved = resolveAutoPreset()
            runningLabel = "Авто → ${resolved.title} · Telegram"
            _zapretAutoProgress.value = ZapretAutoProgress(
                message = "Backend проверен, TUN запущен"
            )
            _verificationMessage.value =
                "Backend проверен, TUN запущен; проверьте сайт кнопкой «Открыть»"
            return BackendStart(ZAPRET_BRIDGE_PORT, tunnelReady = true)
        }
        runningLabel = "${selected.title} · Telegram"
        _zapretAutoProgress.value = ZapretAutoProgress()
        proxyProcess = createZapretProcess(selected)
        pipeLogs("ByeDPI", proxyProcess!!)
        waitForPort(proxyProcess!!, ZAPRET_SOCKS_PORT, 5_000)
        startZapretBridge()
        return BackendStart(ZAPRET_BRIDGE_PORT)
    }

    private suspend fun resolveAutoPreset(): ZapretPreset {
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
        val cached = XrayPreferences.zapretAutoCache(this)
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
                    return preset
                }
            } catch (e: Exception) {
                Log.w("ByeDPI", "Preset ${preset.name} test failed", e)
                scores += preset to 0
            } finally {
                if (!candidatePassed) stopTransportProcesses()
            }
        }
        val best = ZapretAutoSelection.best(scores)
        _zapretAutoProgress.value = ZapretAutoProgress(
            message = "Рабочая стратегия не найдена"
        )
        error(
            "ByeDPI не прошёл проверку: лучший результат " +
                "${best.second} из ${ConnectivityDiagnostics.dpiTargetIds.size}. " +
                "Используйте Xray или свои параметры"
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
        tun = createTun()
        startTun2socks(tun!!, ZAPRET_BRIDGE_PORT)
    }

    private suspend fun startFullAutoBackend(): BackendStart {
        val networkKey = networkKey()
        val cached = XrayPreferences.youtubeAutoCache(this)
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
            stopFullAutoCandidate()
            try {
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
                val socksPort = startXrayBackend(fullAuto = true)
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
                    "${selectedProfileLabel()} · YouTube: ${preset.title} · Telegram"
                _zapretAutoProgress.value = ZapretAutoProgress(
                    message = "YouTube работает через Xray → ByeDPI"
                )
                _verificationMessage.value =
                    "YouTube проверен через локальный обход: ${preset.title}"
                return BackendStart(socksPort)
            } catch (e: Exception) {
                Log.w("ByeDPI-YouTube", "Preset ${preset.name} failed", e)
                stopFullAutoCandidate()
            }
        }
        XrayPreferences.clearYoutubeAutoCache(this)
        stopFullAutoCandidate()
        runningLabel = "${selectedProfileLabel()} · YouTube через Xray · Telegram"
        _zapretAutoProgress.value = ZapretAutoProgress(
            message = "Локальный обход не подошёл, используем Xray"
        )
        _verificationMessage.value =
            "YouTube переключён на Xray: локальные стратегии не прошли проверку"
        val socksPort = startXrayBackend(fullAuto = false)
        val xrayPlayer = ConnectivityDiagnostics().runSocks(
            socksPort,
            resolveForSocks = false,
            targetsToTest = listOf(ConnectivityDiagnostics.youtubeAutoTargets.first())
        ).single()
        val xrayPlayback = if (xrayPlayer.reachable) {
            ConnectivityDiagnostics().probeYoutubePlaybackSocks(
                socksPort,
                resolveForSocks = false
            )
        } else {
            xrayPlayer
        }
        _verificationMessage.value = if (xrayPlayback.reachable) {
            "YouTube переключён на Xray, видеопоток работает"
        } else {
            "YouTube переключён на Xray, но ${xrayPlayback.target.label}: " +
                xrayPlayback.error.ifBlank { "недоступен" }
        }
        return BackendStart(socksPort)
    }

    private suspend fun startTelegramProxy() {
        val result = TelegramNativeProxy.start(
            cacheDir = cacheDir.absolutePath,
            secret = XrayPreferences.telegramSecret(this),
            cloudflareEnabled = XrayPreferences.telegramCfEnabled(this),
            cloudflareDomain = XrayPreferences.telegramCfDomain(this)
        )
        check(result == 0) { "Telegram Proxy завершился с ошибкой $result" }
        telegramStarted = true
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use {
                    it.connect(
                        InetSocketAddress("127.0.0.1", TelegramProxyConfig.PORT),
                        100
                    )
                }
                return
            } catch (_: Exception) {
                delay(100)
            }
        }
        error("Telegram Proxy не открыл порт ${TelegramProxyConfig.PORT}")
    }

    private fun verifyRunningTunnel() {
        _verificationMessage.value = "Проверяем полный VPN-маршрут..."
        scope.launch {
            delay(250)
            val results = ConnectivityDiagnostics().runSocks(
                _socksPort.value ?: return@launch,
                resolveForSocks = runningBackend == VpnBackend.LOCAL_BYPASS,
                targetsToTest = ConnectivityDiagnostics.autoTargets
            )
            val tunnelAlive = isProcessAlive(tun2socksProcess) && tun != null
            _verificationMessage.value = if (
                tunnelAlive && ConnectivityDiagnostics.bypassWorks(results)
            ) {
                "Backend и TUN работают; проверьте сайт кнопкой «Открыть»"
            } else {
                "VPN включён, но обход ограничений не подтверждён"
            }
        }
    }

    private fun createZapretProcess(preset: ZapretPreset): Process {
        val binary = File(applicationInfo.nativeLibraryDir, "libciadpi.so")
        check(binary.exists()) { "ByeDPI не найден" }
        return ProcessBuilder(
            ZapretCommand.build(
                binary.absolutePath,
                ZAPRET_SOCKS_PORT,
                preset,
                XrayPreferences.zapretCustomArguments(this)
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

    private fun networkKey(): String {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val link = manager.getLinkProperties(network)
        val capabilities = manager.getNetworkCapabilities(network)
        return buildString {
            append(network?.networkHandle ?: 0L)
            append('|').append(link?.interfaceName.orEmpty())
            append('|').append(link?.dnsServers?.joinToString(",").orEmpty())
            append('|').append(capabilities?.toString()?.hashCode() ?: 0)
        }
    }

    private fun createTun(): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("SA05 ${runningBackend.title}")
            .setMtu(1500)
            .addAddress("10.10.10.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .setBlocking(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        builder.addDisallowedApplication(packageName)
        XrayPreferences.excludedApps(this).forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                Log.w("XrayVpnService", "Cannot exclude $pkg", e)
            }
        }
        return builder.establish() ?: error("Android не создал TUN-интерфейс")
    }

    private suspend fun startTun2socks(fd: ParcelFileDescriptor, socksPort: Int) {
        val binary = File(applicationInfo.nativeLibraryDir, "libtun2socks.so")
        check(binary.exists()) { "libtun2socks.so не найден" }
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
                    lines.forEach { Log.i(tag, it) }
                }
            } catch (e: Exception) {
                val closedDuringStop = e is InterruptedIOException ||
                    e.message?.contains("interrupted by close", ignoreCase = true) == true
                if (!closedDuringStop && isProcessAlive(process)) {
                    Log.w(tag, "Не удалось прочитать лог", e)
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
        startGeneration.incrementAndGet()
        startJob?.cancel()
        stopProcesses()
        runningProfile = null
        runningLabel = ""
        _state.value = STATE_DISCONNECTED
        _socksPort.value = null
        _zapretAutoProgress.value = ZapretAutoProgress()
        _verificationMessage.value = ""
        VpnRuntimeState.clearIfBackend(this, runningBackend)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopProcesses() {
        stopTransportProcesses()
        if (telegramStarted) {
            telegramStarted = false
            runCatching { TelegramNativeProxy.stop() }
                .onFailure { Log.w("TelegramProxy", "Native stop failed", it) }
        }
    }

    private fun stopFullAutoCandidate() {
        closeProcess(proxyProcess)
        proxyProcess = null
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
        closeProcess(auxiliaryProcess)
        auxiliaryProcess = null
        runCatching { tun?.close() }
        tun = null
    }

    private fun selectedLabel(): String = when (runningBackend) {
        VpnBackend.PROXY_ONLY -> selectedProfileLabel()
        VpnBackend.LOCAL_BYPASS ->
            "${XrayPreferences.zapretPreset(this).title} · Telegram"
        VpnBackend.FULL_AUTO -> "${selectedProfileLabel()} · локальный обход"
    }

    private fun selectedProfileLabel(): String =
        runningProfile?.remarks.orEmpty().ifBlank { "Локальный профиль" }

    private fun writeRuntime(status: VpnRunStatus) {
        VpnRuntimeState.write(
            context = this,
            status = status,
            backend = runningBackend,
            profileId = runningProfile?.id.orEmpty(),
            profileName = runningLabel.ifBlank { selectedLabel() }
        )
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopProcesses()
        scope.cancel()
        runningProfile = null
        runningLabel = ""
        _state.value = STATE_DISCONNECTED
        _socksPort.value = null
        _zapretAutoProgress.value = ZapretAutoProgress()
        _verificationMessage.value = ""
        VpnRuntimeState.clearIfBackend(this, runningBackend)
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
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_tile)
            .setContentTitle(text)
            .setContentText(runningLabel.ifBlank { selectedLabel() })
            .setSubText("SA05 ${runningBackend.title}")
            .setContentIntent(openApp)
            .addAction(0, "Отключить", stopIntent)
            .addAction(0, "Переподключить", reconnectIntent)
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
