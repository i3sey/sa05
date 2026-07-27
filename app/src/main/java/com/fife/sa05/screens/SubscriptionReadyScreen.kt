package com.fife.sa05.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fife.sa05.SubscriptionState
import com.fife.sa05.parseServerRemark

/** The explicit hand-off between a successful first import and VPN permission. */
@Composable
internal fun SubscriptionReadyScreen(
    subscription: SubscriptionState,
    onConnect: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profileName = parseServerRemark(subscription.activeProfile?.remarks.orEmpty())
        .name
        .ifBlank { "первый профиль" }
    Column(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Подписка готова", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Найдено профилей: ${subscription.profiles.size}. " +
                        "Для первого подключения выбран «$profileName» в режиме «Только прокси».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Подключить VPN")
                }
                Text(
                    "Системный Android-диалог попросит разрешение на создание VPN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.TextButton(onClick = onContinue) {
                    Text("Настроить позже")
                }
            }
        }
    }
}
