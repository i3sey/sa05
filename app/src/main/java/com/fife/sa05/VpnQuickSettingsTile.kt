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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VpnQuickSettingsTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { updateTile() }
    }

    override fun onClick() {
        super.onClick()
        scope.launch { handleClick() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun handleClick() {
        val settings = XrayPreferences.snapshot(this)
        val runtime = VpnRuntimeState.read(this)
        if (runtime.status in setOf(
                VpnRunStatus.CONNECTING,
                VpnRunStatus.CONNECTED,
                VpnRunStatus.RECOVERING,
                VpnRunStatus.WAITING_FOR_NETWORK
            )
        ) {
            BackendController.stopRunning(this)
            VpnRuntimeState.clear(this)
            renderTile(VpnRuntimeState.read(this), settings)
            return
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
            if (!BackendController.startSelected(this)) {
                VpnRuntimeState.clear(this)
                renderTile(VpnRuntimeState.read(this), settings)
                openAppForPermission()
            }
        } else {
            openAppForPermission()
        }
    }

    private suspend fun updateTile() {
        renderTile(VpnRuntimeState.read(this), XrayPreferences.snapshot(this))
    }

    private fun renderTile(runtime: VpnRuntimeSnapshot, settings: XraySettings) {
        val tile = qsTile ?: return
        val backend = effectiveVpnBackend(settings)
        val selected = selectedLabel(backend, settings)
        tile.state = when (runtime.status) {
            VpnRunStatus.CONNECTED -> Tile.STATE_ACTIVE
            VpnRunStatus.CONNECTING -> Tile.STATE_ACTIVE
            VpnRunStatus.RECOVERING -> Tile.STATE_ACTIVE
            VpnRunStatus.WAITING_FOR_NETWORK -> Tile.STATE_ACTIVE
            VpnRunStatus.ERROR -> Tile.STATE_INACTIVE
            VpnRunStatus.DISCONNECTED -> Tile.STATE_INACTIVE
        }
        tile.label = "SA05"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (runtime.status) {
                VpnRunStatus.CONNECTED -> runtime.profileName.ifBlank { "Подключено" }
                VpnRunStatus.CONNECTING -> runtime.profileName.ifBlank { "Подключение" }
                VpnRunStatus.RECOVERING -> "Восстановление"
                VpnRunStatus.WAITING_FOR_NETWORK -> "Ожидание сети"
                VpnRunStatus.ERROR -> "Нужна проверка"
                VpnRunStatus.DISCONNECTED -> if (
                    SubscriptionAuth.isAuthorized(settings.subscription)
                ) {
                    selected.ifBlank { "Отключено" }
                } else {
                    "Нужна ссылка"
                }
            }
        }
        tile.contentDescription = when (runtime.status) {
            VpnRunStatus.CONNECTED -> "SA05 подключён: ${runtime.profileName}"
            VpnRunStatus.CONNECTING -> "SA05 подключается: ${runtime.profileName}"
            VpnRunStatus.RECOVERING -> "SA05 восстанавливает VPN"
            VpnRunStatus.WAITING_FOR_NETWORK -> "SA05 ожидает сеть"
            VpnRunStatus.ERROR -> "SA05: ${runtime.message.ifBlank { "ошибка VPN" }}"
            VpnRunStatus.DISCONNECTED -> "SA05 отключён"
        }
        tile.updateTile()
    }

    private fun selectedLabel(backend: VpnBackend, settings: XraySettings): String = when (backend) {
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
