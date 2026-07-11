package com.fife.sa05

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The sole owner of the native TG WS Proxy process.
 *
 * A standalone request is made from the simplified UI. Full Auto and Local
 * Bypass acquire a VPN request instead, so both paths cannot start the native
 * singleton twice. The proxy stays alive while at least one requester needs it.
 */
class TelegramProxyService : Service() {
    companion object {
        const val ACTION_START_STANDALONE = "com.fife.sa05.telegram.START_STANDALONE"
        const val ACTION_STOP_STANDALONE = "com.fife.sa05.telegram.STOP_STANDALONE"
        const val ACTION_ACQUIRE_VPN = "com.fife.sa05.telegram.ACQUIRE_VPN"
        const val ACTION_RELEASE_VPN = "com.fife.sa05.telegram.RELEASE_VPN"
        const val ACTION_RELOAD = "com.fife.sa05.telegram.RELOAD"

        private const val CHANNEL_ID = "telegram_proxy"
        private const val NOTIFICATION_ID = 11
        private const val START_TIMEOUT_MS = 5_000L

        fun startStandalone(context: Context) = start(context, ACTION_START_STANDALONE)

        fun stopStandalone(context: Context) {
            context.startService(
                Intent(context, TelegramProxyService::class.java)
                    .setAction(ACTION_STOP_STANDALONE)
            )
        }

        fun acquireForVpn(context: Context) = start(context, ACTION_ACQUIRE_VPN)

        fun releaseForVpn(context: Context) {
            context.startService(
                Intent(context, TelegramProxyService::class.java)
                    .setAction(ACTION_RELEASE_VPN)
            )
        }

        fun reload(context: Context) {
            context.startService(
                Intent(context, TelegramProxyService::class.java)
                    .setAction(ACTION_RELOAD)
            )
        }

        suspend fun awaitRunning(context: Context, timeoutMs: Long = START_TIMEOUT_MS): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                when (TelegramProxyRuntimeState.read(context).status) {
                    TelegramProxyRunStatus.RUNNING -> return true
                    TelegramProxyRunStatus.ERROR -> return false
                    else -> delay(100)
                }
            }
            return false
        }

        private fun start(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TelegramProxyService::class.java).setAction(action)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private var requests = TelegramProxyRequests()
    private var proxyStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requests = when (intent?.action) {
            ACTION_START_STANDALONE -> requests.withStandalone(true)
            ACTION_STOP_STANDALONE -> requests.withStandalone(false)
            ACTION_ACQUIRE_VPN -> requests.withVpn(true)
            ACTION_RELEASE_VPN -> requests.withVpn(false)
            ACTION_RELOAD -> requests
            else -> return START_NOT_STICKY
        }
        if (requests.required) startForegroundNow("Запускаем Telegram")
        scope.launch {
            if (intent.action == ACTION_RELOAD) restartProxy() else updateProxyState()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private suspend fun restartProxy() {
        stateMutex.withLock {
            if (proxyStarted) {
                runCatching { TelegramNativeProxy.stop() }
                proxyStarted = false
            }
        }
        updateProxyState()
    }

    private suspend fun updateProxyState() = stateMutex.withLock {
        if (!requests.required) {
            if (proxyStarted) {
                runCatching { TelegramNativeProxy.stop() }
                proxyStarted = false
            }
            TelegramProxyRuntimeState.clear(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (proxyStarted) {
            TelegramProxyRuntimeState.publish(
                this,
                TelegramProxyRuntimeSnapshot(TelegramProxyRunStatus.RUNNING)
            )
            startForegroundNow("Telegram подключён")
            return
        }

        TelegramProxyRuntimeState.publish(
            this,
            TelegramProxyRuntimeSnapshot(TelegramProxyRunStatus.STARTING, "Открываем локальный прокси")
        )
        try {
            val settings = XrayPreferences.snapshot(this)
            val result = TelegramNativeProxy.start(
                cacheDir = cacheDir.absolutePath,
                secret = XrayPreferences.telegramSecret(this),
                cloudflareEnabled = settings.telegramCfEnabled,
                cloudflareDomain = settings.telegramCfDomain
            )
            check(result == 0) { "Telegram Proxy завершился с ошибкой $result" }
            waitForLocalPort()
            proxyStarted = true
            TelegramProxyRuntimeState.publish(
                this,
                TelegramProxyRuntimeSnapshot(TelegramProxyRunStatus.RUNNING)
            )
            startForegroundNow("Telegram подключён")
        } catch (error: Exception) {
            TelegramProxyRuntimeState.publish(
                this,
                TelegramProxyRuntimeSnapshot(
                    TelegramProxyRunStatus.ERROR,
                    error.message ?: error.javaClass.simpleName
                )
            )
            startForegroundNow("Не удалось запустить Telegram")
        }
    }

    private suspend fun waitForLocalPort() {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
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

    private fun startForegroundNow(text: String) {
        val openApp = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_tile)
            .setContentTitle(text)
            .setContentText("Локальный прокси Telegram активен")
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                if (requests.standalone) {
                    val stopIntent = PendingIntent.getService(
                        this@TelegramProxyService,
                        21,
                        Intent(this@TelegramProxyService, TelegramProxyService::class.java)
                            .setAction(ACTION_STOP_STANDALONE),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    addAction(0, "Отключить Telegram", stopIntent)
                }
            }
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
                    "SA05 Telegram",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (proxyStarted) runCatching { TelegramNativeProxy.stop() }
        proxyStarted = false
        TelegramProxyRuntimeState.clear(this)
        super.onDestroy()
    }
}
