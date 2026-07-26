package com.fife.sa05

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the update check and download.
 *
 * A download that restarts because the screen rotated wastes the user's data on a ~24 MB APK,
 * so this state belongs outside the composition. The session counter guards against a stale
 * download's progress callbacks overwriting a newer one's state.
 */
internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppUpdateRepository(application)

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val _messages = MutableStateFlow("")
    val messages: StateFlow<String> = _messages.asStateFlow()

    private var downloadJob: Job? = null
    private var downloadSession = 0L

    val downloading: Boolean
        get() = downloadJob?.isActive == true

    fun consumeMessage() {
        _messages.value = ""
    }

    fun check(notify: Boolean = false, silent: Boolean = false) {
        if (downloading) {
            if (!silent) _messages.value = "Дождитесь завершения скачивания обновления"
            return
        }
        viewModelScope.launch {
            _state.value = AppUpdateState.Checking
            val result = try {
                withContext(Dispatchers.IO) {
                    repository.checkLatestRelease(
                        BuildConfig.VERSION_CODE,
                        BuildConfig.VERSION_NAME
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppUpdateState.Error(e.message ?: e.javaClass.simpleName)
            }
            _state.value = result
            if (silent) return@launch
            _messages.value = when {
                notify && result is AppUpdateState.Available ->
                    "Доступна версия ${result.release.versionName}"
                notify -> ""
                result is AppUpdateState.Available ->
                    "Доступна версия ${result.release.versionName}"
                result is AppUpdateState.UpToDate -> "Установлена актуальная версия"
                result is AppUpdateState.Error -> "Ошибка проверки: ${result.message}"
                else -> ""
            }
        }
    }

    fun download(release: AppRelease) {
        if (downloading) {
            _messages.value = "Обновление уже скачивается"
            return
        }
        val session = ++downloadSession
        downloadJob = viewModelScope.launch {
            _state.value = AppUpdateState.Available(release, downloadProgress = 0)
            try {
                val file = withContext(Dispatchers.IO) {
                    repository.downloadRelease(release) { progress ->
                        // 100 is reported before the file is verified, so it is not published
                        // as progress; the finished state below takes over.
                        if (progress < 100 && session == downloadSession) {
                            _state.value = AppUpdateState.Available(
                                release = release,
                                downloadedPath = null,
                                downloadProgress = progress
                            )
                        }
                    }
                }
                if (session != downloadSession) return@launch
                _state.value = AppUpdateState.Available(
                    release = release,
                    downloadedPath = file.absolutePath
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session == downloadSession) {
                    _state.value = AppUpdateState.Error(
                        "Не удалось скачать APK: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            } finally {
                if (session == downloadSession) downloadJob = null
            }
        }
    }

    /**
     * Returns the intent the caller should launch, or null when the file has gone missing.
     * The view model does not start activities itself.
     */
    fun installIntentFor(path: String): android.content.Intent? {
        val context = getApplication<Application>()
        val file = File(path)
        if (!file.exists()) {
            _state.value = AppUpdateState.Error("APK не найден")
            return null
        }
        return if (AppUpdateInstaller.canInstallPackages(context)) {
            AppUpdateInstaller.installIntent(context, file)
        } else {
            AppUpdateInstaller.unknownSourcesIntent(context)
        }
    }
}
