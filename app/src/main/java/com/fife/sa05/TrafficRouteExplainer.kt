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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fife.sa05.ui.theme.motionEnabled

/**
 * Куда уходит какой трафик в выбранном режиме.
 *
 * Дорожки повторяют правила маршрутизации из [XrayConfig.buildFullAutoConfig]: YouTube по TCP
 * уходит в обход на телефоне и дальше напрямую к Google, QUIC на UDP 443 блокируется, остальное
 * идёт на сервер подписки. Не абстрактные пакеты, а имена сервисов — иначе по картинке нельзя
 * понять, что режим делает именно с твоим YouTube.
 */
internal enum class TrafficLaneKind {
    /** Обход DPI прямо на телефоне: пакет режется и уходит к сайту мимо сервера подписки. */
    BYPASS,

    /** Через сервер подписки. */
    PROXY,

    /** Никуда: правило blackhole. */
    BLOCKED
}

internal data class TrafficLane(
    /** Что за трафик — «YouTube», «Весь остальной трафик». */
    val traffic: String,
    /** Через что он идёт — «Обход на телефоне», «Сервер подписки». */
    val hop: String,
    /** Куда приходит в итоге. */
    val destination: String,
    val kind: TrafficLaneKind
)

/**
 * Дорожки трафика для режима. Порядок — от самого заметного пользователю к служебному.
 */
internal fun VpnBackend.trafficLanes(): List<TrafficLane> = buildList {
    if (this@trafficLanes != VpnBackend.PROXY_ONLY) {
        add(
            TrafficLane(
                traffic = "YouTube · googlevideo.com",
                hop = "Обход на телефоне",
                destination = "Напрямую к YouTube, через провайдера",
                kind = TrafficLaneKind.BYPASS
            )
        )
    }
    if (usesTelegram) {
        add(
            TrafficLane(
                traffic = "Telegram",
                hop = "MTProto на телефоне",
                destination = "Напрямую к Telegram",
                kind = TrafficLaneKind.BYPASS
            )
        )
    }
    add(
        when (this@trafficLanes) {
            VpnBackend.FULL_AUTO -> TrafficLane(
                traffic = "Остальной трафик",
                hop = "Сервер подписки",
                destination = "Интернет",
                kind = TrafficLaneKind.PROXY
            )

            VpnBackend.LOCAL_BYPASS -> TrafficLane(
                traffic = "Остальной трафик",
                hop = "Обход на телефоне",
                destination = "Напрямую к сайту",
                kind = TrafficLaneKind.BYPASS
            )

            VpnBackend.PROXY_ONLY -> TrafficLane(
                traffic = "Весь трафик",
                hop = "Сервер подписки",
                destination = "Интернет",
                kind = TrafficLaneKind.PROXY
            )
        }
    )
    if (this@trafficLanes == VpnBackend.FULL_AUTO) {
        add(
            TrafficLane(
                traffic = "QUIC · UDP 443",
                hop = "Блокируется",
                destination = "Сайт уходит на TCP",
                kind = TrafficLaneKind.BLOCKED
            )
        )
    }
}

@Composable
internal fun TrafficRouteExplainer(backend: VpnBackend, modifier: Modifier = Modifier) {
    val lanes = backend.trafficLanes()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Куда идёт трафик", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                backend.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            lanes.forEachIndexed { index, lane ->
                Spacer(Modifier.height(if (index == 0) 10.dp else 12.dp))
                TrafficLaneRow(lane, index)
            }
        }
    }
}

@Composable
private fun TrafficLaneRow(lane: TrafficLane, index: Int) {
    val animated = motionEnabled()
    val transition = rememberInfiniteTransition(label = "lane-$index")
    val running by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "lane-packet-$index"
    )
    val pulsing by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lane-pulse-$index"
    )
    // Дорожки стартуют вразнобой, иначе три пакета идут строем и читаются как один.
    val progress = if (animated) (running + index * 0.27f) % 1f else 0.32f
    val pulse = if (animated) pulsing else 1f

    val wire = MaterialTheme.colorScheme.outlineVariant
    val device = MaterialTheme.colorScheme.secondary
    val accent = when (lane.kind) {
        TrafficLaneKind.BYPASS -> MaterialTheme.colorScheme.primary
        TrafficLaneKind.PROXY -> MaterialTheme.colorScheme.primary
        TrafficLaneKind.BLOCKED -> MaterialTheme.colorScheme.error
    }
    val hopFill = when (lane.kind) {
        TrafficLaneKind.BYPASS -> MaterialTheme.colorScheme.secondaryContainer
        TrafficLaneKind.PROXY -> MaterialTheme.colorScheme.primaryContainer
        TrafficLaneKind.BLOCKED -> MaterialTheme.colorScheme.errorContainer
    }
    val target = MaterialTheme.colorScheme.tertiary

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            drawLane(lane.kind, progress, pulse, wire, device, accent, hopFill, target)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LaneLabel(lane.traffic, TextAlign.Start, Modifier.weight(1f), emphasised = true)
            LaneLabel(lane.hop, TextAlign.Center, Modifier.weight(1f))
            LaneLabel(lane.destination, TextAlign.End, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LaneLabel(
    text: String,
    align: TextAlign,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasised) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = align,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

private fun DrawScope.drawLane(
    kind: TrafficLaneKind,
    progress: Float,
    pulse: Float,
    wire: Color,
    device: Color,
    accent: Color,
    hopFill: Color,
    target: Color
) {
    val y = size.height / 2f
    val startX = 12.dp.toPx()
    val endX = size.width - 12.dp.toPx()
    val hopX = size.width * 0.5f
    val hopHalf = 22.dp.toPx()

    drawLine(
        color = accent.copy(alpha = pulse),
        start = Offset(startX, y),
        end = Offset(hopX - hopHalf, y),
        strokeWidth = 5.dp.toPx()
    )
    if (kind == TrafficLaneKind.BLOCKED) {
        // Пунктир за блоком: дороги дальше нет, но видно, куда она вела.
        drawLine(
            color = wire.copy(alpha = 0.5f),
            start = Offset(hopX + hopHalf, y),
            end = Offset(endX, y),
            strokeWidth = 3.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(4.dp.toPx(), 5.dp.toPx())
            )
        )
    } else {
        drawLine(
            color = wire,
            start = Offset(hopX + hopHalf, y),
            end = Offset(endX, y),
            strokeWidth = 4.dp.toPx()
        )
    }

    drawCircle(color = device, radius = 7.dp.toPx(), center = Offset(startX, y))
    drawCircle(
        color = if (kind == TrafficLaneKind.BLOCKED) wire else target,
        radius = 7.dp.toPx(),
        center = Offset(endX, y)
    )

    drawHop(kind, hopX, y, hopHalf, pulse, accent, hopFill)
    drawLanePacket(kind, progress, startX, hopX, endX, y, hopHalf, accent)
}

private fun DrawScope.drawHop(
    kind: TrafficLaneKind,
    hopX: Float,
    y: Float,
    hopHalf: Float,
    pulse: Float,
    accent: Color,
    hopFill: Color
) {
    val height = 26.dp.toPx()
    val topLeft = Offset(hopX - hopHalf, y - height / 2f)
    val boxSize = Size(hopHalf * 2f, height)
    val radius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
    drawRoundRect(color = hopFill, topLeft = topLeft, size = boxSize, cornerRadius = radius)
    drawRoundRect(
        color = accent.copy(alpha = pulse),
        topLeft = topLeft,
        size = boxSize,
        cornerRadius = radius,
        style = Stroke(width = 2.dp.toPx())
    )
    when (kind) {
        // Обход: три сегмента — пакет внутри режется на части.
        TrafficLaneKind.BYPASS -> {
            val barW = 3.dp.toPx()
            val barH = height * 0.44f
            listOf(-1, 0, 1).forEach { slot ->
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(hopX + slot * 8.dp.toPx() - barW / 2f, y - barH / 2f),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                )
            }
        }
        // Сервер подписки: туннель, пакет внутри целиком.
        TrafficLaneKind.PROXY -> {
            drawLine(
                color = accent,
                start = Offset(hopX - hopHalf * 0.55f, y),
                end = Offset(hopX + hopHalf * 0.55f, y),
                strokeWidth = 4.dp.toPx()
            )
        }
        // Блок: перечёркнутый круг.
        TrafficLaneKind.BLOCKED -> {
            val r = height * 0.28f
            drawCircle(
                color = accent,
                radius = r,
                center = Offset(hopX, y),
                style = Stroke(width = 2.dp.toPx())
            )
            val d = r * 0.72f
            drawLine(
                color = accent,
                start = Offset(hopX - d, y + d),
                end = Offset(hopX + d, y - d),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

private fun DrawScope.drawLanePacket(
    kind: TrafficLaneKind,
    progress: Float,
    startX: Float,
    hopX: Float,
    endX: Float,
    y: Float,
    hopHalf: Float,
    accent: Color
) {
    val radius = 4.dp.toPx()
    if (kind == TrafficLaneKind.BLOCKED) {
        // Пакет доезжает до блока и гаснет — дальше не уходит ничего.
        val gateX = hopX - hopHalf
        val t = (progress / 0.7f).coerceAtMost(1f)
        val x = startX + (gateX - startX) * t
        val alpha = 1f - ((progress - 0.62f) / 0.2f).coerceIn(0f, 1f)
        if (alpha > 0.02f) {
            drawCircle(accent.copy(alpha = alpha * 0.2f), radius * 2f, Offset(x, y))
            drawCircle(accent.copy(alpha = alpha), radius, Offset(x, y))
        }
        return
    }

    val x = startX + (endX - startX) * progress
    // После обхода пакет идёт частями — то же самое, что рисуют анимации приёмов ByeDPI.
    val split = kind == TrafficLaneKind.BYPASS && x > hopX + hopHalf
    if (split) {
        val gap = 5.dp.toPx()
        drawCircle(accent.copy(alpha = 0.9f), radius * 0.85f, Offset(x - gap, y - gap * 0.6f))
        drawCircle(accent.copy(alpha = 0.9f), radius * 0.85f, Offset(x + gap, y + gap * 0.6f))
    } else {
        drawCircle(accent.copy(alpha = 0.2f), radius * 2f, Offset(x, y))
        drawCircle(accent, radius, Offset(x, y))
    }
}
