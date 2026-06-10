package com.fife.sa05

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppUpdateCard(
    currentVersionName: String,
    currentVersionCode: Int,
    updateState: AppUpdateState,
    canInstallPackages: Boolean,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: (AppRelease) -> Unit,
    onInstallUpdate: (String) -> Unit,
    onOpenUnknownSources: () -> Unit
) {
    val checking = updateState == AppUpdateState.Checking
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Обновление приложения", style = MaterialTheme.typography.titleLarge)
            Text(
                "Текущая версия: $currentVersionName ($currentVersionCode)",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (updateState) {
                AppUpdateState.Idle -> Text(
                    "Проверка ещё не запускалась",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AppUpdateState.Checking -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text("Проверяем последнюю версию")
                }
                AppUpdateState.UpToDate -> Text(
                    "Установлена актуальная версия",
                    color = MaterialTheme.colorScheme.primary
                )
                is AppUpdateState.Error -> Text(
                    "Ошибка проверки: ${updateState.message}",
                    color = MaterialTheme.colorScheme.error
                )
                is AppUpdateState.Available -> {
                    val versionCodeText = updateState.release.versionCode?.let { " ($it)" }.orEmpty()
                    val downloadedPath = updateState.downloadedPath
                    val installPath = downloadedPath.orEmpty()
                    Text(
                        "Доступна версия ${updateState.release.versionName}$versionCodeText",
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (updateState.release.notes.isNotBlank()) {
                        Text(
                            updateState.release.notes,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    updateState.downloadProgress?.let { progress ->
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (progress >= 99) {
                                "Почти готово"
                            } else {
                                "Загрузка $progress%"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            downloadedPath.isNullOrBlank() && updateState.downloadProgress == null ->
                                Button(onClick = { onDownloadUpdate(updateState.release) }) {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Скачать")
                                }
                            downloadedPath.isNullOrBlank() ->
                                OutlinedButton(onClick = {}, enabled = false) {
                                    Text("Загружаем")
                                }
                            canInstallPackages ->
                                Button(
                                    onClick = { onInstallUpdate(installPath) }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Установить")
                                }
                            else -> Button(onClick = onOpenUnknownSources) {
                                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Разрешить установку")
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onCheckUpdate,
                enabled = !checking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (checking) "Проверяем..." else "Проверить обновление")
            }
        }
    }
}
