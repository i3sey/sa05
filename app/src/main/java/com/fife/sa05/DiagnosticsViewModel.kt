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

internal data class DiagnosticsUiState(
    /** Null until a run has been started, which is what "not run yet" looks like on screen. */
    val results: List<DiagnosticResult>? = null,
    val running: Boolean = false,
    val activeTargetId: String? = null,
    /** Which path the probes take, so a result can be read in context. */
    val route: String = ""
)

/**
 * Owns the full diagnostics run, which is long enough that it must survive a rotation: losing
 * seven sequential probes because the screen turned is the difference between a useful tool and
 * one people stop using.
 */
internal class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val diagnostics = ConnectivityDiagnostics()

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    private val _messages = MutableStateFlow("")
    val messages: StateFlow<String> = _messages.asStateFlow()

    private var job: Job? = null

    fun consumeMessage() {
        _messages.value = ""
    }

    fun cancel() {
        job?.cancel()
    }

    fun run(connected: Boolean, socksPort: Int?) {
        if (_state.value.running) return
        job?.cancel()
        val runtime = VpnRuntimeState.read(context)
        _state.value = DiagnosticsUiState(
            results = emptyList(),
            running = true,
            activeTargetId = ConnectivityDiagnostics.targets.first().id,
            route = if (connected) {
                "через VPN · " + runtime.backend.title
            } else {
                "напрямую, без VPN"
            }
        )
        job = viewModelScope.launch {
            try {
                val onResult: suspend (DiagnosticResult) -> Unit = { result ->
                    _state.update { current ->
                        val results = current.results.orEmpty() + result
                        current.copy(
                            results = results,
                            activeTargetId = ConnectivityDiagnostics.targets
                                .getOrNull(results.size)
                                ?.id
                        )
                    }
                }
                val results = if (connected) {
                    diagnostics.runSocks(
                        socksPort ?: error("SOCKS-порт VPN недоступен"),
                        resolveForSocks = runtime.backend == VpnBackend.LOCAL_BYPASS,
                        targetsToTest = ConnectivityDiagnostics.targets,
                        onResult = onResult
                    )
                } else {
                    diagnostics.runDirect(ConnectivityDiagnostics.targets, onResult)
                }
                _state.update { it.copy(results = results) }
            } catch (_: CancellationException) {
                _messages.value = "Проверка остановлена"
            } catch (e: Exception) {
                _messages.value = e.message ?: "Проверка не завершилась"
            } finally {
                _state.update { it.copy(running = false, activeTargetId = null) }
                job = null
            }
        }
    }
}
