package com.fife.sa05

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class ProfileOutboundInfo(
    val tag: String,
    val protocol: String,
    val transport: String,
    val security: String,
    val endpoint: String
)

internal data class ProfileBalancerInfo(
    val tag: String,
    val strategy: String,
    val candidates: List<ProfileOutboundInfo>,
    val fallbackTag: String?
)

internal data class ProfileRouteInfo(
    val primaryOutbound: ProfileOutboundInfo,
    val balancers: List<ProfileBalancerInfo>
) {
    val protocol: String get() = primaryOutbound.protocol
    val transport: String get() = primaryOutbound.transport
    val security: String get() = primaryOutbound.security
    val endpoint: String get() = primaryOutbound.endpoint
}

internal fun parseProfileRoute(raw: String): ProfileRouteInfo {
    val root = JSONObject(raw)
    val remoteOutbounds = root.getJSONArray("outbounds")
        .objects()
        .filter { it.optString("protocol").lowercase() !in LOCAL_PROTOCOLS }
        .mapNotNull(::parseProfileOutbound)
    val primaryOutbound = remoteOutbounds.first()
    val routing = root.optJSONObject("routing")
    val usedBalancerTags = routing
        ?.optJSONArray("rules")
        ?.objects()
        .orEmpty()
        .map { it.optString("balancerTag").trim() }
        .filter(String::isNotEmpty)
        .distinct()
    val configuredBalancers = routing
        ?.optJSONArray("balancers")
        ?.objects()
        .orEmpty()
        .associateBy { it.optString("tag") }
    val balancers = usedBalancerTags.mapNotNull { tag ->
        val balancer = configuredBalancers[tag] ?: return@mapNotNull null
        val selectors = balancer.optJSONArray("selector")
            ?.strings()
            .orEmpty()
            .filter(String::isNotBlank)
        ProfileBalancerInfo(
            tag = tag,
            strategy = balancer.optJSONObject("strategy")
                ?.optString("type", "random")
                ?.ifBlank { "random" }
                ?: "random",
            candidates = remoteOutbounds.filter { outbound ->
                outbound.tag.isNotBlank() && selectors.any(outbound.tag::startsWith)
            },
            fallbackTag = balancer.optString("fallbackTag")
                .trim()
                .takeIf(String::isNotEmpty)
        )
    }

    return ProfileRouteInfo(
        primaryOutbound = primaryOutbound,
        balancers = balancers
    )
}

private fun parseProfileOutbound(outbound: JSONObject): ProfileOutboundInfo? {
    val protocol = outbound.getString("protocol").uppercase()
    val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
    val transport = stream.optString("network", "tcp").ifBlank { "tcp" }.uppercase()
    val security = stream.optString("security", "none").ifBlank { "none" }.uppercase()
    val settings = outbound.optJSONObject("settings") ?: return null
    val endpoint = when {
        settings.optJSONArray("vnext")?.length() ?: 0 > 0 ->
            settings.getJSONArray("vnext").optJSONObject(0)
        settings.optJSONArray("servers")?.length() ?: 0 > 0 ->
            settings.getJSONArray("servers").optJSONObject(0)
        else -> settings
    } ?: return null
    val address = endpoint.optString("address").takeIf(String::isNotBlank) ?: return null
    val port = endpoint.optInt("port").takeIf { it in 1..65535 } ?: return null

    return ProfileOutboundInfo(
        tag = outbound.optString("tag").trim(),
        protocol = protocol,
        transport = transport,
        security = security,
        endpoint = "$address:$port"
    )
}

private fun org.json.JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull(::optJSONObject)

private fun org.json.JSONArray.strings(): List<String> =
    (0 until length()).map(::optString)

private val LOCAL_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

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

    val primary = info.primaryOutbound

    Column(modifier = modifier.fillMaxWidth()) {
        if (info.balancers.isEmpty()) {
            ProfileRouteCard(primary)
        } else {
            info.balancers.forEach { balancer ->
                BalancerRouteCard(balancer)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun ProfileRouteCard(outbound: ProfileOutboundInfo) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Маршрут профиля", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "${outbound.protocol} передаёт трафик через ${outbound.transport}; " +
                    securitySummary(outbound.security),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            ProfileRouteAnimation(outbound)
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun BalancerRouteCard(balancer: ProfileBalancerInfo) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Балансер ${balancer.tag}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${balancerStrategyLabel(balancer.strategy)} · " +
                    "серверов: ${balancer.candidates.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            BalancerRouteAnimation(balancer)
            if (balancer.fallbackTag != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Резервный маршрут: ${balancer.fallbackTag}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Анимация показывает варианты из конфига, а не активный сервер в реальном времени.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProfileRouteAnimation(outbound: ProfileOutboundInfo) {
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
    val tunnel = MaterialTheme.colorScheme.primaryContainer
    val tunnelOutline = MaterialTheme.colorScheme.primary
    val server = MaterialTheme.colorScheme.tertiary

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        "${outbound.protocol} · ${outbound.transport} · " +
                            securityLabel(outbound.security),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            val y = size.height / 2f
            val startX = 20.dp.toPx()
            val endX = size.width - 20.dp.toPx()
            val serverX = size.width / 2f
            val tunnelWidth = 58.dp.toPx()
            val tunnelHeight = 38.dp.toPx()
            val tunnelLeft = serverX - tunnelWidth / 2f

            drawLine(
                color = tunnelOutline.copy(alpha = pulse),
                start = Offset(startX, y),
                end = Offset(serverX, y),
                strokeWidth = 6.dp.toPx()
            )
            drawLine(
                color = wire,
                start = Offset(serverX, y),
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
                val packetColor = if (x <= serverX) packet else server
                drawCircle(
                    color = packetColor.copy(alpha = 0.18f),
                    radius = 10.dp.toPx(),
                    center = Offset(x, y)
                )
                drawCircle(
                    color = packetColor,
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
                    "VPN-сервер",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    outbound.endpoint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "Интернет",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BalancerRouteAnimation(balancer: ProfileBalancerInfo) {
    val transition = rememberInfiniteTransition(label = "balancer-${balancer.tag}")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "balancer-packet-${balancer.tag}"
    )
    val candidates = balancer.candidates.take(3)
    val client = MaterialTheme.colorScheme.secondary
    val protectedWire = MaterialTheme.colorScheme.primary
    val neutralWire = MaterialTheme.colorScheme.outlineVariant
    val balancerColor = MaterialTheme.colorScheme.primaryContainer
    val serverColor = MaterialTheme.colorScheme.tertiaryContainer
    val internetColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
        ) {
            val centerY = size.height / 2f
            val device = Offset(16.dp.toPx(), centerY)
            val balance = Offset(size.width * 0.31f, centerY)
            val poolX = size.width * 0.66f
            val internet = Offset(size.width - 16.dp.toPx(), centerY)
            val candidatePoints = when (candidates.size) {
                0 -> listOf(Offset(poolX, centerY))
                1 -> listOf(Offset(poolX, centerY))
                2 -> listOf(Offset(poolX, centerY - 18.dp.toPx()), Offset(poolX, centerY + 18.dp.toPx()))
                else -> listOf(
                    Offset(poolX, centerY - 28.dp.toPx()),
                    Offset(poolX, centerY),
                    Offset(poolX, centerY + 28.dp.toPx())
                )
            }

            drawLine(protectedWire, device, balance, strokeWidth = 6.dp.toPx())
            candidatePoints.forEach { candidate ->
                drawLine(neutralWire, balance, candidate, strokeWidth = 3.dp.toPx())
                drawLine(neutralWire, candidate, internet, strokeWidth = 3.dp.toPx())
            }
            drawCircle(client, 9.dp.toPx(), device)
            drawRoundRect(
                color = balancerColor,
                topLeft = Offset(balance.x - 25.dp.toPx(), balance.y - 17.dp.toPx()),
                size = Size(50.dp.toPx(), 34.dp.toPx()),
                cornerRadius = CornerRadius(11.dp.toPx())
            )
            candidatePoints.forEachIndexed { index, candidate ->
                val activeIndex = if (candidates.isEmpty()) -1 else
                    (progress * candidates.size).toInt().coerceAtMost(candidates.lastIndex)
                drawRoundRect(
                    color = when {
                        candidates.isEmpty() -> errorColor.copy(alpha = 0.25f)
                        index == activeIndex -> serverColor
                        else -> serverColor.copy(alpha = 0.48f)
                    },
                    topLeft = Offset(candidate.x - 19.dp.toPx(), candidate.y - 9.dp.toPx()),
                    size = Size(38.dp.toPx(), 18.dp.toPx()),
                    cornerRadius = CornerRadius(7.dp.toPx())
                )
            }
            drawCircle(internetColor, 9.dp.toPx(), internet)

            listOf(progress, (progress + 0.5f) % 1f).forEach { packetProgress ->
                val candidateIndex = if (candidatePoints.size == 1) 0 else
                    (packetProgress * candidatePoints.size).toInt().coerceAtMost(candidatePoints.lastIndex)
                val candidate = candidatePoints[candidateIndex]
                val phase = packetProgress * 3f
                val from: Offset
                val to: Offset
                val localProgress: Float
                when {
                    phase < 1f -> {
                        from = device
                        to = balance
                        localProgress = phase
                    }
                    phase < 2f -> {
                        from = balance
                        to = candidate
                        localProgress = phase - 1f
                    }
                    else -> {
                        from = candidate
                        to = internet
                        localProgress = phase - 2f
                    }
                }
                val point = Offset(
                    from.x + (to.x - from.x) * localProgress,
                    from.y + (to.y - from.y) * localProgress
                )
                drawCircle(protectedWire.copy(alpha = 0.18f), 9.dp.toPx(), point)
                drawCircle(protectedWire, 4.dp.toPx(), point)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            listOf("Устройство", "Балансер", "VPN-серверы", "Интернет").forEachIndexed { index, label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        3 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (balancer.candidates.isEmpty()) {
            Text(
                "Selector не совпал ни с одним VPN outbound.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.forEach { candidate ->
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            "${candidate.tag}: ${candidate.protocol}/${candidate.transport}/" +
                                securityLabel(candidate.security),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                if (balancer.candidates.size > candidates.size) {
                    Text(
                        "+${balancer.candidates.size - candidates.size}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

private fun balancerStrategyLabel(strategy: String): String = when (strategy) {
    "random" -> "Случайный выбор"
    "roundRobin" -> "По очереди"
    "leastPing" -> "Минимальная задержка"
    "leastLoad" -> "Минимальная нагрузка"
    else -> strategy
}

private fun securitySummary(security: String): String =
    if (security == "NONE") "шифрование транспорта не задано." else "защита — $security."

private fun securityLabel(security: String): String =
    if (security == "NONE") "Без TLS" else security
