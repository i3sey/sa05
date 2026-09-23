package com.fife.sa05

import android.annotation.SuppressLint
import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

enum class VpnTileVisualState {
    ACTIVE,
    INACTIVE
}

data class VpnTilePresentation(
    val state: VpnTileVisualState,
    val label: String,
    val subtitle: String,
    val contentDescription: String
)

internal fun shouldStopVpnOnTileClick(status: VpnRunStatus): Boolean =
    status in setOf(
        VpnRunStatus.CONNECTING,
        VpnRunStatus.CONNECTED,
        VpnRunStatus.RECOVERING,
        VpnRunStatus.WAITING_FOR_NETWORK
    )

internal fun vpnTilePresentation(
    runtime: VpnRuntimeSnapshot,
    settings: XraySettings?
): VpnTilePresentation {
    val backend = settings?.let { effectiveVpnBackend(it) } ?: runtime.backend
    val selected = settings?.let { selectedLabel(backend, it) } ?: runtime.profileName
    val state = when (runtime.status) {
        VpnRunStatus.CONNECTED -> VpnTileVisualState.ACTIVE
        VpnRunStatus.CONNECTING -> VpnTileVisualState.ACTIVE
        VpnRunStatus.RECOVERING -> VpnTileVisualState.ACTIVE
        VpnRunStatus.WAITING_FOR_NETWORK -> VpnTileVisualState.ACTIVE
        VpnRunStatus.ERROR -> VpnTileVisualState.INACTIVE
        VpnRunStatus.DISCONNECTED -> VpnTileVisualState.INACTIVE
    }
    val subtitle = when (runtime.status) {
        VpnRunStatus.CONNECTED -> runtime.profileName.ifBlank { "Подключено" }
        VpnRunStatus.CONNECTING -> runtime.profileName.ifBlank { "Подключение" }
        VpnRunStatus.RECOVERING -> "Восстановление"
        VpnRunStatus.WAITING_FOR_NETWORK -> "Ожидание сети"
        VpnRunStatus.ERROR -> "Нужна проверка"
        VpnRunStatus.DISCONNECTED -> if (
            settings != null && SubscriptionAuth.isAuthorized(settings.subscription)
        ) {
            selected.ifBlank { "Отключено" }
        } else if (settings != null) {
            "Нужна ссылка"
        } else {
            "Отключено"
        }
    }
    val contentDescription = when (runtime.status) {
        VpnRunStatus.CONNECTED -> "SA05 подключён: ${runtime.profileName}"
        VpnRunStatus.CONNECTING -> "SA05 подключается: ${runtime.profileName}"
        VpnRunStatus.RECOVERING -> "SA05 восстанавливает VPN"
        VpnRunStatus.WAITING_FOR_NETWORK -> "SA05 ожидает сеть"
        VpnRunStatus.ERROR -> "SA05: ${runtime.message.ifBlank { "ошибка VPN" }}"
        VpnRunStatus.DISCONNECTED -> "SA05 отключён"
    }
    return VpnTilePresentation(
        state = state,
        label = "SA05",
        subtitle = subtitle,
        contentDescription = contentDescription
    )
}

internal fun selectedLabel(backend: VpnBackend, settings: XraySettings): String = when (backend) {
    VpnBackend.PROXY_ONLY ->
        settings.subscription.activeProfile?.remarks.orEmpty()
            .ifBlank { "Xray" }
    VpnBackend.LOCAL_BYPASS ->
        "[BETA] ${settings.zapretPreset.title} + Telegram"
    VpnBackend.FULL_AUTO ->
        "[BETA] " + settings.subscription.activeProfile?.remarks.orEmpty()
            .ifBlank { "Xray" } + " + локальный обход"
    VpnBackend.YCTUN ->
        settings.subscription.activeProfile?.remarks.orEmpty()
            .ifBlank { "Xray" } + " + БС"
}

class VpnQuickSettingsTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null
    private val clickMutex = Mutex()

    override fun onStartListening() {
        super.onStartListening()
        // Immediate synchronous render on main thread so SystemUI sees fresh state right away
        renderTile(VpnRuntimeState.read(this), XrayPreferences.cachedSettings)

        listeningJob?.cancel()
        listeningJob = scope.launch {
            combine(
                VpnRuntimeState.observe(this@VpnQuickSettingsTile),
                XrayPreferences.settings(this@VpnQuickSettingsTile)
            ) { runtime, settings ->
                runtime to settings
            }.collectLatest { (runtime, settings) ->
                renderTile(runtime, settings)
            }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            if (!clickMutex.tryLock()) return@launch
            try {
                handleClick()
            } finally {
                clickMutex.unlock()
            }
        }
    }

    override fun onDestroy() {
        listeningJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun handleClick() {
        val runtime = VpnRuntimeState.read(this)
        val settings = XrayPreferences.cachedSettings ?: XrayPreferences.snapshot(this)

        if (shouldStopVpnOnTileClick(runtime.status)) {
            BackendController.stopRunning(this)
            VpnRuntimeState.clear(this)
            renderTile(VpnRuntimeState.read(this), settings)
            return
        }

        if (runtime.status == VpnRunStatus.ERROR) {
            BackendController.stopRunning(this)
        }

        val backend = effectiveVpnBackend(settings)
        if (!SubscriptionAuth.isAuthorized(settings.subscription)) {
            renderTile(VpnRuntimeState.read(this), settings)
            openAppForPermission()
            return
        }

        val notificationDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (VpnService.prepare(this) == null && !notificationDenied) {
            val selected = selectedLabel(backend, settings)
            VpnRuntimeState.write(
                this,
                VpnRunStatus.CONNECTING,
                backend,
                profileName = selected
            )
            renderTile(VpnRuntimeState.read(this), settings)
            try {
                if (!BackendController.startSelected(this)) {
                    VpnRuntimeState.clear(this)
                    renderTile(VpnRuntimeState.read(this), settings)
                    openAppForPermission()
                }
            } catch (e: Exception) {
                Log.e("VpnQuickSettingsTile", "Failed to start VPN from tile", e)
                VpnRuntimeState.clear(this)
                renderTile(VpnRuntimeState.read(this), settings)
            }
        } else {
            renderTile(VpnRuntimeState.read(this), settings)
            openAppForPermission()
        }
    }

    private fun renderTile(runtime: VpnRuntimeSnapshot, settings: XraySettings?) {
        val tile = qsTile ?: return
        val presentation = vpnTilePresentation(runtime, settings)
        tile.state = when (presentation.state) {
            VpnTileVisualState.ACTIVE -> Tile.STATE_ACTIVE
            VpnTileVisualState.INACTIVE -> Tile.STATE_INACTIVE
        }
        tile.label = presentation.label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = presentation.subtitle
        }
        tile.contentDescription = presentation.contentDescription
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppForPermission() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_REQUEST_VPN, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                30,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
