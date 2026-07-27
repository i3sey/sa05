package com.fife.sa05.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.fife.sa05.TelegramProxyRunStatus
import com.fife.sa05.TelegramProxyRuntimeSnapshot
import com.fife.sa05.ui.theme.fadeTransform
import com.fife.sa05.ui.theme.motionEnabled

@Composable
internal fun AuthScreen(
    url: String,
    updating: Boolean,
    errorMessage: String?,
    telegramRuntime: TelegramProxyRuntimeSnapshot,
    onUrlChanged: (String) -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    onStartTelegram: () -> Unit,
    onOpenTelegram: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Подключите подписку", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Вставьте HTTPS-ссылку, которую вы получили у провайдера. " +
                        "Профили будут проверены до сохранения.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("HTTPS-ссылка подписки") },
                    placeholder = { Text("https://example.com/token/json") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() })
                )
                OutlinedButton(
                    onClick = onPaste,
                    enabled = !updating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Вставить из буфера")
                }
                errorMessage?.takeIf(String::isNotBlank)?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    onClick = onSubmit,
                    enabled = !updating && url.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    val loginMotion = motionEnabled()
                    AnimatedContent(
                        targetState = updating,
                        transitionSpec = { fadeTransform(loginMotion) },
                        label = "loginLabel"
                    ) { loading ->
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Войти")
                        }
                    }
                }
                Text(
                    "Можно также открыть ссылку формата sa05://add/… из сообщения " +
                        "или браузера. После первого успешного импорта вход без сети не нужен.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Нужен только Telegram?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Запустим Telegram через локальный прокси. Подписка и VPN-разрешение не нужны.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (telegramRuntime.status) {
                    TelegramProxyRunStatus.RUNNING -> Button(
                        onClick = onOpenTelegram,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Открыть Telegram") }
                    TelegramProxyRunStatus.STARTING -> OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Подключаем Telegram…") }
                    else -> OutlinedButton(
                        onClick = onStartTelegram,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (telegramRuntime.status == TelegramProxyRunStatus.ERROR) {
                                "Попробовать снова"
                            } else {
                                "Включить Telegram"
                            }
                        )
                    }
                }
            }
        }
    }
}
