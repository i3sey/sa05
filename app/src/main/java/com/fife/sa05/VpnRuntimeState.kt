package com.fife.sa05

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService

enum class VpnRunStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

data class VpnRuntimeSnapshot(
    val status: VpnRunStatus,
    val backend: VpnBackend,
    val profileId: String,
    val profileName: String
)

object VpnRuntimeState {
    private const val FILE = "vpn_runtime"
    private const val KEY_STATUS = "status"
    private const val KEY_BACKEND = "backend"
    private const val KEY_PROFILE_ID = "profile_id"
    private const val KEY_PROFILE_NAME = "profile_name"

    fun read(context: Context): VpnRuntimeSnapshot {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return VpnRuntimeSnapshot(
            status = runCatching {
                VpnRunStatus.valueOf(
                    prefs.getString(KEY_STATUS, VpnRunStatus.DISCONNECTED.name)
                        ?: VpnRunStatus.DISCONNECTED.name
                )
            }.getOrDefault(VpnRunStatus.DISCONNECTED),
            backend = runCatching {
                VpnBackend.valueOf(
                    prefs.getString(KEY_BACKEND, VpnBackend.XRAY.name)
                        ?: VpnBackend.XRAY.name
                )
            }.getOrDefault(VpnBackend.XRAY),
            profileId = prefs.getString(KEY_PROFILE_ID, "").orEmpty(),
            profileName = prefs.getString(KEY_PROFILE_NAME, "").orEmpty()
        )
    }

    fun write(
        context: Context,
        status: VpnRunStatus,
        backend: VpnBackend,
        profileId: String = "",
        profileName: String = ""
    ) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, status.name)
            .putString(KEY_BACKEND, backend.name)
            .putString(KEY_PROFILE_ID, profileId)
            .putString(KEY_PROFILE_NAME, profileName)
            .commit()
        requestTileRefresh(context)
    }

    fun clear(context: Context) {
        write(
            context,
            VpnRunStatus.DISCONNECTED,
            XrayPreferences.vpnBackend(context)
        )
    }

    fun requestTileRefresh(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                context,
                ComponentName(context, VpnQuickSettingsTile::class.java)
            )
        }
    }
}
