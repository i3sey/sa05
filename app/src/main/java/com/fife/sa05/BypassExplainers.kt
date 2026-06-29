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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Низкоуровневые приёмы обхода DPI, из которых собраны стратегии ByeDPI/zapret.
 * Каждый приём имеет плавную Material You анимацию [BypassAnimation].
 */
enum class BypassPrimitive(
    val title: String,
    val summary: String
) {
    SPLIT("Сегментация", "ClientHello режется на части — DPI не видит SNI целиком"),
    DISORDER("Реордеринг", "Части уходят не по порядку — DPI не собирает поток, сервер собирает по seq"),
    FAKE("Фейк-пакет", "Перед реальным летит пакет-приманка, сервер его отбрасывает"),
    OOB("OOB-байт", "Внеполосный байт ломает разбор потока у DPI"),
    TTL("Малый TTL", "Приманка с низким TTL гаснет до сервера, но обманывает DPI"),
    TLSREC("TLS-фрагмент", "SNI разрывается между двумя TLS-записями"),
    SNI("Фейк-SNI", "DPI видит подставной домен вместо настоящего"),
    HTTPMOD("HTTP-маскировка", "Регистр и формат заголовка Host меняются — сигнатура не совпадает"),
    AUTO("Адаптивно", "Приём подбирается по реакции сети: torst, ssl_err, redirect")
}

/** Какие приёмы задействует стратегия — выводится прямо из её аргументов ByeDPI. */
fun ZapretPreset.bypassPrimitives(): List<BypassPrimitive> {
    if (this == ZapretPreset.AUTO) return listOf(BypassPrimitive.AUTO)
    val out = LinkedHashSet<BypassPrimitive>()
    arguments.forEach { tok ->
        when (tok) {
            "--split" -> out += BypassPrimitive.SPLIT
            "--disorder" -> out += BypassPrimitive.DISORDER
            "--disoob" -> { out += BypassPrimitive.OOB; out += BypassPrimitive.DISORDER }
            "--oob" -> out += BypassPrimitive.OOB
            "--fake-sni", "--fake-tls-mod" -> out += BypassPrimitive.SNI
            "--fake" -> out += BypassPrimitive.FAKE
            "--ttl" -> out += BypassPrimitive.TTL
            "--tlsrec", "--tlsminor" -> out += BypassPrimitive.TLSREC
            "--mod-http" -> out += BypassPrimitive.HTTPMOD
            "--auto", "--auto-mode" -> out += BypassPrimitive.AUTO
        }
    }
    return out.toList()
}

private class BypassPalette(
    val wire: Color,
    val real: Color,
    val onReal: Color,
    val segment: Color,
    val gate: Color,
    val fake: Color,
    val accent: Color,
    val text: Color
)

@Composable
private fun bypassPalette(): BypassPalette {
    val cs = MaterialTheme.colorScheme
    return BypassPalette(
        wire = cs.outlineVariant,
        real = cs.primary,
        onReal = cs.onPrimary,
        segment = cs.secondary,
        gate = cs.tertiary,
        fake = cs.error,
        accent = cs.tertiary,
        text = cs.onSurfaceVariant
    )
}

/**
 * Раскрывающийся пояснитель для одной стратегии: чипы приёмов + плавная анимация каждого.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StrategyExplainer(preset: ZapretPreset, modifier: Modifier = Modifier) {
    val primitives = remember(preset) { preset.bypassPrimitives() }
    Column(modifier = modifier.fillMaxWidth()) {
        if (preset == ZapretPreset.CUSTOM) {
            Text(
                "Свои параметры ByeDPI. Приёмы зависят от введённых флагов.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        // Чипы приёмов — анимированный вход со сдвигом.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            primitives.forEachIndexed { index, primitive ->
                val appear by animateFloatAsState(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 320,
                        delayMillis = index * 70,
                        easing = FastOutSlowInEasing
                    ),
                    label = "chip-${primitive.name}"
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.graphicsLayer {
                        alpha = appear
                        val s = 0.8f + 0.2f * appear
                        scaleX = s
                        scaleY = s
                    }
                ) {
                    Text(
                        primitive.title,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        primitives.forEach { primitive ->
            PrimitiveCard(primitive)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun PrimitiveCard(primitive: BypassPrimitive) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                primitive.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                primitive.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            BypassAnimation(
                primitive,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            )
        }
    }
}

@Composable
private fun BypassAnimation(primitive: BypassPrimitive, modifier: Modifier = Modifier) {
    val palette = bypassPalette()
    val transition = rememberInfiniteTransition(label = "anim-${primitive.name}")
    val duration = when (primitive) {
        BypassPrimitive.AUTO -> 4200
        BypassPrimitive.HTTPMOD -> 3000
        else -> 3400
    }
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = duration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "p-${primitive.name}"
    )
    val density = LocalDensity.current
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
    }
    val labelSizePx = remember(density) { with(density) { 10.sp.toPx() } }
    Canvas(modifier = modifier) {
        labelPaint.textSize = labelSizePx
        drawScene(primitive, progress, palette, labelPaint)
    }
}

// ---------------------------------------------------------------------------
// Рисование сцены
// ---------------------------------------------------------------------------

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun smooth(t: Float): Float = (t * t * (3f - 2f * t)).coerceIn(0f, 1f)
private fun clamp01(t: Float) = t.coerceIn(0f, 1f)

private fun DrawScope.packet(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    color: Color,
    alpha: Float = 1f,
    stroke: Boolean = false
) {
    val topLeft = Offset(cx - w / 2f, cy - h / 2f)
    val sz = Size(w, h)
    val radius = CornerRadius(h / 2.6f, h / 2.6f)
    if (stroke) {
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = topLeft,
            size = sz,
            cornerRadius = radius,
            style = Stroke(width = h * 0.16f)
        )
    } else {
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = topLeft,
            size = sz,
            cornerRadius = radius
        )
    }
}

/** Провод client→server + ворота DPI по центру. Возвращает x ворот. */
private fun DrawScope.baseScene(p: BypassPalette, cy: Float): Float {
    val left = size.width * 0.08f
    val right = size.width * 0.92f
    drawLine(
        color = p.wire,
        start = Offset(left, cy),
        end = Offset(right, cy),
        strokeWidth = size.height * 0.03f
    )
    val gateX = size.width * 0.5f
    val gateH = size.height * 0.62f
    drawRoundRect(
        color = p.gate.copy(alpha = 0.16f),
        topLeft = Offset(gateX - size.height * 0.07f, cy - gateH / 2f),
        size = Size(size.height * 0.14f, gateH),
        cornerRadius = CornerRadius(6f, 6f)
    )
    drawLine(
        color = p.gate,
        start = Offset(gateX, cy - gateH / 2f),
        end = Offset(gateX, cy + gateH / 2f),
        strokeWidth = size.height * 0.04f
    )
    return gateX
}

private fun DrawScope.label(text: String, x: Float, y: Float, color: Color, paint: android.graphics.Paint) {
    paint.color = android.graphics.Color.argb(
        (color.alpha * 255).roundToInt(),
        (color.red * 255).roundToInt(),
        (color.green * 255).roundToInt(),
        (color.blue * 255).roundToInt()
    )
    drawContext.canvas.nativeCanvas.drawText(text, x, y - (paint.descent() + paint.ascent()) / 2f, paint)
}

private fun DrawScope.drawScene(
    primitive: BypassPrimitive,
    progress: Float,
    p: BypassPalette,
    paint: android.graphics.Paint
) {
    val cy = size.height / 2f
    val left = size.width * 0.08f
    val right = size.width * 0.92f
    val h = size.height * 0.30f
    val gateX = baseScene(p, cy)

    when (primitive) {
        BypassPrimitive.SPLIT -> {
            val x = lerp(left, right, smooth(progress))
            val gap = smooth(clamp01((x - left) / (gateX - left))) * size.width * 0.05f
            val pw = size.width * 0.16f
            packet(x - pw / 2f - gap, cy, pw, h, p.real)
            packet(x + pw / 2f + gap, cy, pw, h, p.segment)
            if (gap > size.width * 0.01f) {
                drawLine(
                    color = p.accent,
                    start = Offset(x, cy - h),
                    end = Offset(x, cy + h),
                    strokeWidth = size.height * 0.02f
                )
            }
        }

        BypassPrimitive.DISORDER -> {
            // Сегмент 2 уходит первым; к серверу порядок восстановлен (1,2).
            val t = smooth(progress)
            val pw = size.width * 0.16f
            val span = right - left
            val x1 = left + span * lerp(0.10f, 0.78f, t)
            val x2 = left + span * lerp(0.34f, 0.62f, t)
            packet(x2, cy, pw, h, p.segment)
            packet(x1, cy, pw, h, p.real)
            label("2", x2, cy, p.text, paint)
            label("1", x1, cy, p.onReal, paint)
        }

        BypassPrimitive.FAKE -> {
            val realX = lerp(left, right, smooth(progress))
            val fakeLead = size.width * 0.16f
            val fakeX = realX + fakeLead
            val pw = size.width * 0.15f
            // Приманка гаснет на воротах.
            val fakeAlpha = if (fakeX < gateX) 0.9f else (1f - smooth(clamp01((fakeX - gateX) / (right - gateX)))) * 0.9f
            packet(fakeX, cy, pw, h, p.fake, alpha = fakeAlpha, stroke = true)
            packet(realX, cy, pw, h, p.real)
        }

        BypassPrimitive.OOB -> {
            val x = lerp(left, right, smooth(progress))
            val pw = size.width * 0.20f
            packet(x, cy, pw, h, p.real)
            // OOB-байт — яркая точка-нашлёпка над потоком.
            drawCircle(
                color = p.accent,
                radius = size.height * 0.07f,
                center = Offset(x + pw * 0.30f, cy - h * 0.9f)
            )
            if (x in (gateX - pw)..(gateX + pw)) {
                label("?", gateX, cy - size.height * 0.40f, p.gate, paint)
            }
        }

        BypassPrimitive.TTL -> {
            val pw = size.width * 0.15f
            // Реальный пакет идёт до сервера.
            val realX = lerp(left, right, smooth(progress))
            packet(realX, cy + h * 0.05f, pw, h, p.real)
            // Приманка с TTL гаснет около ворот.
            val ft = smooth(clamp01(progress / 0.55f))
            val fakeX = lerp(left, gateX + size.width * 0.04f, ft)
            val ttl = (8 * (1f - ft)).roundToInt().coerceAtLeast(0)
            val fakeAlpha = 1f - smooth(clamp01((progress - 0.5f) / 0.2f))
            if (fakeAlpha > 0.02f) {
                packet(fakeX, cy - h * 0.7f, pw, h * 0.9f, p.fake, alpha = fakeAlpha, stroke = true)
                label(ttl.toString(), fakeX, cy - h * 0.7f, p.fake.copy(alpha = fakeAlpha), paint)
            }
        }

        BypassPrimitive.TLSREC -> {
            val t = smooth(progress)
            val cx = lerp(left, right, t)
            val recW = size.width * 0.22f
            val split = smooth(clamp01((cx - left) / (gateX - left)))
            val gap = split * size.width * 0.045f
            // Две TLS-записи; цветная полоса SNI разорвана между ними.
            packet(cx - recW / 2f - gap, cy, recW, h, p.segment)
            packet(cx + recW / 2f + gap, cy, recW, h, p.segment)
            val bandH = h * 0.5f
            drawRoundRect(
                color = p.real,
                topLeft = Offset(cx - recW / 2f - gap - recW * 0.18f, cy - bandH / 2f),
                size = Size(recW * 0.36f, bandH),
                cornerRadius = CornerRadius(bandH / 3f, bandH / 3f)
            )
            drawRoundRect(
                color = p.real,
                topLeft = Offset(cx + recW / 2f + gap - recW * 0.18f, cy - bandH / 2f),
                size = Size(recW * 0.36f, bandH),
                cornerRadius = CornerRadius(bandH / 3f, bandH / 3f)
            )
        }

        BypassPrimitive.SNI -> {
            val x = lerp(left, right, smooth(progress))
            val pw = size.width * 0.26f
            val passed = x > gateX
            packet(x, cy, pw, h * 1.1f, if (passed) p.real else p.fake, alpha = if (passed) 1f else 0.85f, stroke = !passed)
            label(if (passed) "real" else "ya.ru", x, cy, if (passed) p.onReal else p.fake, paint)
        }

        BypassPrimitive.HTTPMOD -> {
            val x = lerp(left, right, smooth(progress))
            val pw = size.width * 0.30f
            packet(x, cy, pw, h * 1.1f, p.real)
            val variants = listOf("Host:", "hOsT:", "host.:", "HoST:")
            val idx = (progress * variants.size).toInt().coerceIn(0, variants.size - 1)
            label(variants[idx], x, cy, p.onReal, paint)
        }

        BypassPrimitive.AUTO -> {
            // Радар-перебор: подсветка бежит по приёмам и иногда ставит «галку».
            val slots = 4
            val slotW = (right - left) / slots
            val active = (progress * slots).toInt().coerceIn(0, slots - 1)
            for (i in 0 until slots) {
                val sx = left + slotW * (i + 0.5f)
                val on = i == active
                packet(
                    sx, cy, slotW * 0.62f, h * (if (on) 1.25f else 0.9f),
                    if (on) p.real else p.segment,
                    alpha = if (on) 1f else 0.5f
                )
                if (on && progress > 0.85f) {
                    label("✓", sx, cy, p.onReal, paint)
                }
            }
        }
    }
}
