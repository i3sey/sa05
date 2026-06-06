package com.fife.sa05

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.IBinder
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
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

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
        private val _state = MutableStateFlow(STATE_DISCONNECTED)
        val state = _state.asStateFlow()

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
    private var xrayProcess: Process? = null
    private var tun2socksProcess: Process? = null
    private var runningProfile: SubscriptionProfile? = null

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
        runningProfile = XrayPreferences.subscription(this).activeProfile
        _state.value = STATE_CONNECTING
        VpnRuntimeState.write(this, VpnRunStatus.CONNECTING, runningProfile)
        startForegroundNow(STATE_CONNECTING, runningProfile)
        startJob?.cancel()
        startJob = scope.launch { startTunnel() }
    }

    private suspend fun startTunnel() {
        try {
            stopProcesses()
            val rawConfig = runningProfile?.json ?: XrayPreferences.config(this)
            val validated = XrayConfig.validate(rawConfig)
            copyGeoAssets()

            val configFile = File(filesDir, "config.json").apply {
                writeText(validated.runtimeJson)
            }
            startXray(configFile)
            waitForPort(validated.socksPort, 10_000)

            tun = createTun()
            startTun2socks(tun!!, validated.socksPort)
            _state.value = STATE_CONNECTED
            VpnRuntimeState.write(this, VpnRunStatus.CONNECTED, runningProfile)
            startForegroundNow(STATE_CONNECTED, runningProfile)
        } catch (e: Exception) {
            Log.e("XrayVpnService", "Tunnel startup failed", e)
            _state.value = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            stopProcesses()
            runningProfile = null
            VpnRuntimeState.clear(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createTun(): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("SA05 Xray")
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

    private fun startXray(configFile: File) {
        val binary = File(applicationInfo.nativeLibraryDir, "libxray.so")
        check(binary.exists()) { "libxray.so не найден" }
        xrayProcess = ProcessBuilder(
            binary.absolutePath,
            "run",
            "-config",
            configFile.absolutePath
        )
            .directory(filesDir)
            .redirectErrorStream(true)
            .apply { environment()["XRAY_LOCATION_ASSET"] = filesDir.absolutePath }
            .start()
        pipeLogs("xray", xrayProcess!!)
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

    private suspend fun waitForPort(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isProcessAlive(xrayProcess)) error("Xray завершился при запуске")
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                return
            } catch (_: Exception) {
                delay(100)
            }
        }
        error("Xray не открыл SOCKS-порт $port")
    }

    private fun pipeLogs(tag: String, process: Process) {
        scope.launch {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { Log.i(tag, it) }
                }
            } catch (e: Exception) {
                if (isProcessAlive(process)) {
                    Log.w(tag, "Не удалось прочитать лог процесса", e)
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
        startJob?.cancel()
        stopProcesses()
        runningProfile = null
        _state.value = STATE_DISCONNECTED
        VpnRuntimeState.clear(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopProcesses() {
        tun2socksProcess?.destroy()
        tun2socksProcess = null
        xrayProcess?.destroy()
        xrayProcess = null
        tun?.close()
        tun = null
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopProcesses()
        scope.cancel()
        runningProfile = null
        _state.value = STATE_DISCONNECTED
        VpnRuntimeState.clear(this)
        super.onDestroy()
    }

    private fun startForegroundNow(text: String, profile: SubscriptionProfile?) {
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
        val profileName = profile?.remarks.orEmpty()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_tile)
            .setContentTitle(text)
            .setContentText(profileName.ifBlank { "Локальный профиль" })
            .setSubText("SA05 Xray")
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
                    "Xray VPN",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
