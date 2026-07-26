package com.fife.sa05.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.fife.sa05.R
import com.fife.sa05.ui.theme.Space
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.Content, vertical = Space.Group),
        verticalArrangement = Arrangement.spacedBy(Space.Item),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The screen used to open on a card floating in a third of a screen of nothing, with
        // the app's own mark sitting unused in the resources.
        Image(
            painter = painterResource(R.drawable.logo_sa05),
            contentDescription = null,
            modifier = Modifier
                .padding(top = Space.Group, bottom = Space.Tight)
                .size(96.dp)
        )
        Text(
            "SA05",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            "Обход блокировок через вашу подписку",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = Space.Tight)
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(Space.Content),
                verticalArrangement = Arrangement.spacedBy(Space.Item)
            ) {
                Text("Подключите подписку", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Вставьте HTTPS-ссылку, которую вы получили у провайдера. " +
                        "Профили будут проверены до сохранения.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val error = errorMessage?.takeIf(String::isNotBlank)
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = error != null,
                    label = { Text("HTTPS-ссылка подписки") },
                    placeholder = { Text("https://example.com/token/json") },
                    // The submit button below is disabled until there is a link, which on its
                    // own just looks broken. The field says what it is waiting for, so the
                    // disabled button reads as a consequence rather than a dead end.
                    supportingText = {
                        Text(
                            error ?: if (url.isBlank()) {
                                "Без ссылки войти не получится"
                            } else {
                                "Проверим профили до сохранения"
                            }
                        )
                    },
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
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(Space.Content),
                verticalArrangement = Arrangement.spacedBy(Space.Item)
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
