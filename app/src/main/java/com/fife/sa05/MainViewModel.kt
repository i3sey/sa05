package com.fife.sa05

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Latency measurement for one subscription profile, shown in the server sheet. */
internal sealed interface ProfilePing {
    data object Running : ProfilePing
    data class Success(val delayMs: Long) : ProfilePing
    data class Failure(val message: String) : ProfilePing
}

internal data class MainUiState(
    val connectionCheck: ConnectionCheckState = ConnectionCheckState.Idle,
    val refreshing: Boolean = false,
    val subscriptionError: String? = null,
    val importedSubscription: SubscriptionState? = null,
    val pings: Map<String, ProfilePing> = emptyMap(),
    val activePingProfileId: String? = null
)

/**
 * Owns the main screen's state so it survives configuration changes and so the screen's
 * features (quick check, per-profile ping, subscription refresh) live outside the composable.
 *
 * Diagnostics, app updates and app exclusions still keep their own `remember` state in
 * `XrayScreen` — moving those is a separate step.
 */
internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val diagnostics = ConnectivityDiagnostics()
    private val pingEngine = XrayPingEngine(application)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val _messages = MutableStateFlow("")
    val messages: StateFlow<String> = _messages.asStateFlow()

    private var quickCheckJob: Job? = null
    private var pingJob: Job? = null

    fun postMessage(message: String) {
        _messages.value = message
    }

    fun consumeMessage() {
        _messages.value = ""
    }

    fun consumeImportedSubscription() {
        _state.update { it.copy(importedSubscription = null) }
    }

    fun clearSubscriptionError() {
        _state.update { it.copy(subscriptionError = null) }
    }

    /**
     * [authorized] decides where a failure is reported: before the first successful import the
     * login screen owns the error, afterwards it is a snackbar so the cached profiles stay usable.
     */
    fun refreshSubscription(
        url: String,
        authorized: Boolean,
        silent: Boolean = false,
        onFinished: (SubscriptionState?) -> Unit = {}
    ) {
        if (_state.value.refreshing) return
        val showFirstImportSuccess = !authorized
        _state.update { it.copy(refreshing = true, subscriptionError = null) }
        viewModelScope.launch {
            var refreshed: SubscriptionState? = null
            try {
                val result = SubscriptionRefreshRunner.refresh(context, url)
                refreshed = when (result) {
                    is SubscriptionUpdateResult.Updated -> result.state
                    is SubscriptionUpdateResult.NotModified -> result.state
                }
                SubscriptionRefreshScheduler.sync(context, refreshed)
                _state.update {
                    it.copy(
                        pings = emptyMap(),
                        importedSubscription = if (showFirstImportSuccess) refreshed else null
                    )
                }
                if (!silent && !showFirstImportSuccess) {
                    postMessage(
                        when (result) {
                            is SubscriptionUpdateResult.Updated -> "Подписка обновлена"
                            is SubscriptionUpdateResult.NotModified -> "Подписка уже актуальна"
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = "Не удалось обновить подписку: " +
                    (e.message ?: e.javaClass.simpleName)
                if (!authorized) {
                    _state.update { it.copy(subscriptionError = error) }
                } else if (!silent) {
                    postMessage(error)
                }
            } finally {
                _state.update { it.copy(refreshing = false) }
                onFinished(refreshed)
            }
        }
    }

    fun selectProfile(profileId: String, currentProfileId: String) {
        val action = profileSwitchAction(
            currentProfileId = currentProfileId,
            selectedProfileId = profileId,
            runtimeStatus = VpnRuntimeState.read(context).status
        )
        if (action == ProfileSwitchAction.NO_CHANGE) return
        viewModelScope.launch {
            val repository = SubscriptionRepository(context)
            val updated = repository.setActiveProfile(profileId)
            _state.update { it.copy(pings = emptyMap()) }
            if (action == ProfileSwitchAction.SAVE_AND_RECONNECT) {
                XrayVpnService.reconnect(context)
                postMessage("Переподключение: " + updated.activeProfile?.remarks.orEmpty())
            }
        }
    }

    fun runQuickConnectionCheck(socksPort: Int?, backend: VpnBackend) {
        val port = socksPort ?: return
        quickCheckJob?.cancel()
        _state.update { it.copy(connectionCheck = ConnectionCheckState.Running) }
        quickCheckJob = viewModelScope.launch {
            val target = ConnectivityDiagnostics.target("google")
            val result = runCatching {
                diagnostics.runSocks(
                    socksPort = port,
                    resolveForSocks = backend == VpnBackend.LOCAL_BYPASS,
                    targetsToTest = listOf(target)
                ).single()
            }.getOrElse { error ->
                DiagnosticResult(
                    target = target,
                    status = DiagnosticStatus.FAILED,
                    error = error.message ?: error.javaClass.simpleName
                )
            }
            _state.update { it.copy(connectionCheck = connectionCheckState(result)) }
            quickCheckJob = null
        }
    }

    fun resetConnectionCheck() {
        quickCheckJob?.cancel()
        quickCheckJob = null
        _state.update { it.copy(connectionCheck = ConnectionCheckState.Idle) }
    }

    /**
     * Measures one profile through a temporary Xray process. The engine handles a single
     * measurement at a time, so starting another cancels the previous one.
     */
    fun pingProfile(profile: SubscriptionProfile) {
        pingEngine.cancel()
        pingJob?.cancel()
        _state.update {
            it.copy(
                pings = it.pings + (profile.id to ProfilePing.Running),
                activePingProfileId = profile.id
            )
        }
        pingJob = viewModelScope.launch {
            val outcome = try {
                val host = XrayConfig.extractHosts(profile.json).firstOrNull()
                    ?: error("Прокси-хосты не найдены")
                ProfilePing.Success(pingEngine.ping(profile.json, host))
            } catch (e: CancellationException) {
                _state.update { it.copy(pings = it.pings - profile.id) }
                throw e
            } catch (e: Exception) {
                ProfilePing.Failure(e.message ?: e.javaClass.simpleName)
            }
            _state.update {
                it.copy(
                    pings = it.pings + (profile.id to outcome),
                    activePingProfileId = it.activePingProfileId.takeIf { id -> id != profile.id }
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pingEngine.cancel()
    }
}
