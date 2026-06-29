package com.fife.sa05.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fife.sa05.ui.theme.fadeTransform
import com.fife.sa05.ui.theme.motionEnabled

@Composable
internal fun AuthScreen(
    url: String,
    updating: Boolean,
    onUrlChanged: (String) -> Unit,
    onSubmit: () -> Unit,
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
                Text("Вход в SA05", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Чтобы пользоваться приложением, нужна действующая HTTPS-ссылка " +
                        "подписки с JSON-профилями Xray.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Ссылка подписки") },
                    placeholder = { Text("https://example.com/token/json") }
                )
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
                    "После первой успешной проверки ссылка сохраняется, повторный вход " +
                        "без сети не требуется.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
