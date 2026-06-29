package com.fife.sa05

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class ProfileRouteInfo(
    val protocol: String,
    val transport: String,
    val security: String,
    val endpoint: String
)

internal fun parseProfileRoute(raw: String): ProfileRouteInfo {
    val root = JSONObject(raw)
    val outbounds = root.getJSONArray("outbounds")
    val outbound = (0 until outbounds.length())
        .mapNotNull(outbounds::optJSONObject)
        .first { it.optString("protocol") !in LOCAL_PROTOCOLS }
    val protocol = outbound.getString("protocol").uppercase()
    val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
    val transport = stream.optString("network", "tcp").ifBlank { "tcp" }.uppercase()
    val security = stream.optString("security", "none").ifBlank { "none" }.uppercase()
    val settings = outbound.getJSONObject("settings")
    val endpoint = when {
        settings.optJSONArray("vnext") != null ->
            settings.getJSONArray("vnext").getJSONObject(0)
        settings.optJSONArray("servers") != null ->
            settings.getJSONArray("servers").getJSONObject(0)
        else -> settings
    }

    return ProfileRouteInfo(
        protocol = protocol,
        transport = transport,
        security = security,
        endpoint = "${endpoint.getString("address")}:${endpoint.getInt("port")}"
    )
}

private val LOCAL_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileExplainer(
    profile: SubscriptionProfile,
    modifier: Modifier = Modifier
) {
    val info = remember(profile.id, profile.json) {
        runCatching { parseProfileRoute(profile.json) }.getOrNull()
    }
    if (info == null) {
        Text(
            "Параметры маршрута не удалось распознать.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }

    var chipsVisible by remember(info) { mutableStateOf(false) }
    LaunchedEffect(info) { chipsVisible = true }
    val chips = remember(info) {
        listOf(info.protocol, info.transport, info.security)
            .filter(String::isNotBlank)
            .distinct()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEachIndexed { index, chip ->
                val appear by animateFloatAsState(
                    targetValue = if (chipsVisible) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = 320,
                        delayMillis = index * 70,
                        easing = FastOutSlowInEasing
                    ),
                    label = "profile-chip-$index"
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.graphicsLayer {
                        alpha = appear
                        val scale = 0.8f + 0.2f * appear
                        scaleX = scale
                        scaleY = scale
                    }
                ) {
                    Text(
                        chip,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "Маршрут профиля",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${info.protocol} передаёт трафик через ${info.transport}; " +
                        securitySummary(info.security),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                ProfileRouteAnimation(info)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ProfileRouteAnimation(info: ProfileRouteInfo) {
    val transition = rememberInfiniteTransition(label = "profile-route")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "profile-packet"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "profile-security"
    )
    val wire = MaterialTheme.colorScheme.outlineVariant
    val packet = MaterialTheme.colorScheme.primary
    val client = MaterialTheme.colorScheme.secondary
    val tunnel = MaterialTheme.colorScheme.secondaryContainer
    val tunnelOutline = MaterialTheme.colorScheme.secondary
    val server = MaterialTheme.colorScheme.tertiary

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            val y = size.height / 2f
            val startX = 20.dp.toPx()
            val endX = size.width - 20.dp.toPx()
            val tunnelWidth = 70.dp.toPx()
            val tunnelHeight = 38.dp.toPx()
            val tunnelLeft = size.width / 2f - tunnelWidth / 2f

            drawLine(
                color = wire,
                start = Offset(startX, y),
                end = Offset(endX, y),
                strokeWidth = 4.dp.toPx()
            )
            drawCircle(color = client, radius = 10.dp.toPx(), center = Offset(startX, y))
            drawCircle(color = server, radius = 10.dp.toPx(), center = Offset(endX, y))
            drawRoundRect(
                color = tunnel,
                topLeft = Offset(tunnelLeft, y - tunnelHeight / 2f),
                size = Size(tunnelWidth, tunnelHeight),
                cornerRadius = CornerRadius(12.dp.toPx())
            )
            drawRoundRect(
                color = tunnelOutline.copy(alpha = pulse),
                topLeft = Offset(tunnelLeft, y - tunnelHeight / 2f),
                size = Size(tunnelWidth, tunnelHeight),
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )

            listOf(progress, (progress + 0.42f) % 1f).forEach { packetProgress ->
                val x = startX + (endX - startX) * packetProgress
                drawCircle(
                    color = packet.copy(alpha = 0.18f),
                    radius = 10.dp.toPx(),
                    center = Offset(x, y)
                )
                drawCircle(
                    color = packet,
                    radius = 5.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                "Устройство",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    info.transport,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    securityLabel(info.security),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                info.endpoint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun securitySummary(security: String): String =
    if (security == "NONE") "шифрование транспорта не задано." else "защита — $security."

private fun securityLabel(security: String): String =
    if (security == "NONE") "Без TLS" else security
