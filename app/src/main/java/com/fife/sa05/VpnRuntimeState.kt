package com.fife.sa05

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnRunStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECOVERING,
    WAITING_FOR_NETWORK,
    ERROR
}

enum class VpnFailureKind {
    NONE,
    AUTHORIZATION,
    NETWORK,
    BACKEND,
    TUNNEL,
    HEALTH_CHECK,
    SERVICE
}

enum class VpnNetworkType(val title: String) {
    NONE("Нет сети"),
    WIFI("Wi-Fi"),
    MOBILE("Мобильная сеть"),
    ETHERNET("Ethernet"),
    OTHER("Другая сеть")
}

enum class VpnRuntimeComponent(val title: String) {
    XRAY("Xray"),
    TUN("TUN"),
    TUN2SOCKS("tun2socks"),
    BYEDPI("ByeDPI"),
    TELEGRAM("Telegram"),
    YCTUN("CDN-туннель")
}

enum class VpnComponentState {
    STARTING,
    RUNNING,
    FALLBACK,
    FAILED
}

data class VpnComponentSnapshot(
    val component: VpnRuntimeComponent,
    val state: VpnComponentState
)

data class VpnRuntimeSnapshot(
    val status: VpnRunStatus,
    val backend: VpnBackend,
    val profileId: String,
    val profileName: String,
    val message: String = "",
    val failureKind: VpnFailureKind = VpnFailureKind.NONE,
    val networkType: VpnNetworkType = VpnNetworkType.NONE,
    val networkKey: String = "",
    val connectedAtMillis: Long = 0L,
    val automaticRecoveryAttempt: Int = 0,
    val components: List<VpnComponentSnapshot> = emptyList()
) {
    val requested: Boolean
        get() = status != VpnRunStatus.DISCONNECTED
}

object VpnRuntimeState {
    private const val FILE = "vpn_runtime"
    private const val KEY_STATUS = "status"
    private const val KEY_BACKEND = "backend"
    private const val KEY_PROFILE_ID = "profile_id"
    private const val KEY_PROFILE_NAME = "profile_name"
    private const val KEY_MESSAGE = "message"
    private const val KEY_FAILURE_KIND = "failure_kind"
    private const val KEY_NETWORK_TYPE = "network_type"
    private const val KEY_NETWORK_KEY = "network_key"
    private const val KEY_CONNECTED_AT = "connected_at"
    private const val KEY_RECOVERY_ATTEMPT = "recovery_attempt"
    private const val KEY_COMPONENTS = "components"

    private val _updates = MutableStateFlow(disconnectedSnapshot(VpnBackend.FULL_AUTO))
    private val updates = _updates.asStateFlow()
    @Volatile private var initialized = false

    fun observe(context: Context): StateFlow<VpnRuntimeSnapshot> {
        ensureInitialized(context)
        return updates
    }

    fun read(context: Context): VpnRuntimeSnapshot {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return VpnRuntimeSnapshot(
            status = enumValue(
                prefs.getString(KEY_STATUS, null),
                VpnRunStatus.DISCONNECTED
            ),
            backend = VpnBackend.fromStoredName(prefs.getString(KEY_BACKEND, null)),
            profileId = prefs.getString(KEY_PROFILE_ID, "").orEmpty(),
            profileName = prefs.getString(KEY_PROFILE_NAME, "").orEmpty(),
            message = prefs.getString(KEY_MESSAGE, "").orEmpty(),
            failureKind = enumValue(
                prefs.getString(KEY_FAILURE_KIND, null),
                VpnFailureKind.NONE
            ),
            networkType = enumValue(
                prefs.getString(KEY_NETWORK_TYPE, null),
                VpnNetworkType.NONE
            ),
            networkKey = prefs.getString(KEY_NETWORK_KEY, "").orEmpty(),
            connectedAtMillis = prefs.getLong(KEY_CONNECTED_AT, 0L),
            automaticRecoveryAttempt = prefs.getInt(KEY_RECOVERY_ATTEMPT, 0),
            components = decodeComponents(prefs.getString(KEY_COMPONENTS, null))
        )
    }

    fun publish(context: Context, snapshot: VpnRuntimeSnapshot) {
        val previous = if (initialized) _updates.value else null
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, snapshot.status.name)
            .putString(KEY_BACKEND, snapshot.backend.name)
            .putString(KEY_PROFILE_ID, snapshot.profileId)
            .putString(KEY_PROFILE_NAME, snapshot.profileName)
            .putString(KEY_MESSAGE, snapshot.message)
            .putString(KEY_FAILURE_KIND, snapshot.failureKind.name)
            .putString(KEY_NETWORK_TYPE, snapshot.networkType.name)
            .putString(KEY_NETWORK_KEY, snapshot.networkKey)
            .putLong(KEY_CONNECTED_AT, snapshot.connectedAtMillis)
            .putInt(KEY_RECOVERY_ATTEMPT, snapshot.automaticRecoveryAttempt)
            .putString(KEY_COMPONENTS, encodeComponents(snapshot.components))
            .apply()
        initialized = true
        _updates.value = snapshot
        if (
            previous == null ||
            previous.status != snapshot.status ||
            previous.backend != snapshot.backend ||
            previous.profileName != snapshot.profileName
        ) {
            requestTileRefresh(context)
        }
    }

    fun write(
        context: Context,
        status: VpnRunStatus,
        backend: VpnBackend,
        profileId: String = "",
        profileName: String = ""
    ) {
        publish(
            context,
            read(context).copy(
                status = status,
                backend = backend,
                profileId = profileId,
                profileName = profileName,
                message = "",
                failureKind = VpnFailureKind.NONE,
                connectedAtMillis = 0L,
                automaticRecoveryAttempt = 0,
                components = emptyList()
            )
        )
    }

    fun clear(context: Context) {
        publish(context, disconnectedSnapshot(read(context).backend))
    }

    fun clearIfBackend(context: Context, backend: VpnBackend) {
        if (read(context).backend == backend) clear(context)
    }

    fun requestTileRefresh(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                context,
                ComponentName(context, VpnQuickSettingsTile::class.java)
            )
        }
    }

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                _updates.value = read(context)
                initialized = true
            }
        }
    }

    private fun disconnectedSnapshot(backend: VpnBackend) = VpnRuntimeSnapshot(
        status = VpnRunStatus.DISCONNECTED,
        backend = backend,
        profileId = "",
        profileName = ""
    )

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T =
        raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: fallback

    private fun encodeComponents(components: List<VpnComponentSnapshot>): String =
        components.joinToString(";") { "${it.component.name}:${it.state.name}" }

    private fun decodeComponents(raw: String?): List<VpnComponentSnapshot> =
        raw.orEmpty().split(';').mapNotNull { encoded ->
            val parts = encoded.split(':', limit = 2)
            val component = parts.getOrNull(0)?.let { name ->
                VpnRuntimeComponent.entries.firstOrNull { it.name == name }
            }
            val state = parts.getOrNull(1)?.let { name ->
                VpnComponentState.entries.firstOrNull { it.name == name }
            }
            if (component != null && state != null) VpnComponentSnapshot(component, state) else null
        }
}
