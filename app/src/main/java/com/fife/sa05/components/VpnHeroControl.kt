package com.fife.sa05.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fife.sa05.R
import com.fife.sa05.ui.theme.Motion
import com.fife.sa05.ui.theme.motionEnabled
import com.fife.sa05.ui.theme.motionTween

/** What the hero should look like, independent of how the VPN reports itself. */
internal enum class VpnHeroState {
    OFF,
    BUSY,
    ON,
    FAILED
}

private const val RING_SWEEP_DEGREES = 90f
private val HeroSize = 168.dp
private val RingWidth = 6.dp

/**
 * The one thing this screen is for.
 *
 * Connecting used to be an ordinary button inside a card stacked among four others of equal
 * weight, so the app's single most important action carried no more visual authority than
 * "Исключения приложений". The control is now a target you cannot miss, and it carries the
 * state itself: colour says what is happening, and the ring turns only while the VPN is
 * actually working on something.
 */
@Composable
internal fun VpnHeroControl(
    state: VpnHeroState,
    actionLabel: String,
    statusDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = motionEnabled()
    val container by animateColorAsState(
        targetValue = when (state) {
            VpnHeroState.ON -> MaterialTheme.colorScheme.primary
            VpnHeroState.FAILED -> MaterialTheme.colorScheme.errorContainer
            VpnHeroState.BUSY -> MaterialTheme.colorScheme.secondaryContainer
            // surfaceVariant sits too close to the background on a dark scheme; the one
            // control this screen exists for must not fade into it.
            VpnHeroState.OFF -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = motionTween(Motion.Slow),
        label = "heroContainer"
    )
    val content by animateColorAsState(
        targetValue = when (state) {
            VpnHeroState.ON -> MaterialTheme.colorScheme.onPrimary
            VpnHeroState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
            VpnHeroState.BUSY -> MaterialTheme.colorScheme.onSecondaryContainer
            VpnHeroState.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = motionTween(Motion.Slow),
        label = "heroContent"
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion) 0.96f else 1f,
        animationSpec = tween(Motion.Fast, easing = Motion.Standard),
        label = "heroPress"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            BusyRing(active = state == VpnHeroState.BUSY, color = content, motion = motion)
            Surface(
                onClick = onClick,
                modifier = Modifier
                    .size(HeroSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                shape = CircleShape,
                color = container,
                contentColor = content,
                // An outline keeps the target legible when it is off and its fill is quiet;
                // once it is on, the fill carries itself and a border would only add noise.
                border = if (state == VpnHeroState.OFF) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
                } else {
                    null
                },
                interactionSource = interaction
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_power),
                        // The label below names the action; repeating it here would make
                        // TalkBack read the control twice.
                        contentDescription = null,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
        }
        Text(
            actionLabel,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            statusDescription,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Turns only while the VPN is doing something, so motion always means progress. */
@Composable
private fun BusyRing(active: Boolean, color: Color, motion: Boolean) {
    val sweepColor = color.copy(alpha = 0.9f)
    if (!active) return
    if (!motion) {
        // Animations are off system-wide; a static ring still marks the busy state.
        Box(
            Modifier
                .size(HeroSize + RingWidth * 4)
                .drawBehind {
                    drawArc(
                        color = sweepColor,
                        startAngle = -90f,
                        sweepAngle = RING_SWEEP_DEGREES,
                        useCenter = false,
                        style = Stroke(width = RingWidth.toPx())
                    )
                }
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "heroRing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "heroRingAngle"
    )
    Box(
        Modifier
            .size(HeroSize + RingWidth * 4)
            .drawBehind {
                drawArc(
                    color = sweepColor,
                    startAngle = angle - 90f,
                    sweepAngle = RING_SWEEP_DEGREES,
                    useCenter = false,
                    style = Stroke(width = RingWidth.toPx())
                )
            }
    )
}
