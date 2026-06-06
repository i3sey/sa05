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

class VpnQuickSettingsTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val runtime = VpnRuntimeState.read(this)
        if (runtime.status != VpnRunStatus.DISCONNECTED) {
            VpnRuntimeState.clear(this)
            renderTile(VpnRuntimeState.read(this))
            XrayVpnService.stop(this)
            return
        }

        val notificationDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (VpnService.prepare(this) == null && !notificationDenied) {
            val selected = XrayPreferences.subscription(this).activeProfile
            VpnRuntimeState.write(this, VpnRunStatus.CONNECTING, selected)
            renderTile(VpnRuntimeState.read(this))
            XrayVpnService.start(this)
        } else {
            openAppForPermission()
        }
    }

    private fun updateTile() {
        renderTile(VpnRuntimeState.read(this))
    }

    private fun renderTile(runtime: VpnRuntimeSnapshot) {
        val tile = qsTile ?: return
        val selected = XrayPreferences.subscription(this).activeProfile?.remarks.orEmpty()
        tile.state = when (runtime.status) {
            VpnRunStatus.CONNECTED -> Tile.STATE_ACTIVE
            VpnRunStatus.CONNECTING -> Tile.STATE_ACTIVE
            VpnRunStatus.DISCONNECTED -> Tile.STATE_INACTIVE
        }
        tile.label = "SA05 Xray"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (runtime.status) {
                VpnRunStatus.CONNECTED -> runtime.profileName.ifBlank { "Подключено" }
                VpnRunStatus.CONNECTING -> runtime.profileName.ifBlank { "Подключение" }
                VpnRunStatus.DISCONNECTED -> selected.ifBlank { "Отключено" }
            }
        }
        tile.contentDescription = when (runtime.status) {
            VpnRunStatus.CONNECTED -> "VPN подключён: ${runtime.profileName}"
            VpnRunStatus.CONNECTING -> "VPN подключается: ${runtime.profileName}"
            VpnRunStatus.DISCONNECTED -> "VPN отключён"
        }
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
