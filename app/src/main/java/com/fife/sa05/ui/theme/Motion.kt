package com.fife.sa05.ui.theme

import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * Central motion tokens. Durations match the existing explainer animations so the
 * whole app feels consistent. Every helper here collapses to "instant" when the
 * system "remove animations" setting is on (animator duration scale == 0).
 */
object Motion {
    const val Fast = 150
    const val Medium = 250
    const val Slow = 320
    const val Screen = 300
    const val StaggerStep = 70

    val Standard: Easing = FastOutSlowInEasing
    val Linear: Easing = LinearEasing
}

/** System animator duration scale; 0f when the user disabled animations. */
@Composable
fun motionScale(): Float {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
    }
}

@Composable
fun motionEnabled(): Boolean = motionScale() > 0f

/** Tween that becomes a snap (instant) when motion is disabled. */
@Composable
fun <T> motionTween(
    durationMillis: Int = Motion.Medium,
    easing: Easing = Motion.Standard,
    delayMillis: Int = 0
): FiniteAnimationSpec<T> = tweenOrSnap(motionEnabled(), durationMillis, easing, delayMillis)

/**
 * Non-composable spec builder for use inside non-composable lambdas such as
 * [androidx.compose.animation.AnimatedContent]'s `transitionSpec`. Capture
 * [motionEnabled] in the composable scope, then pass it in here.
 */
fun <T> tweenOrSnap(
    enabled: Boolean,
    durationMillis: Int = Motion.Medium,
    easing: Easing = Motion.Standard,
    delayMillis: Int = 0
): FiniteAnimationSpec<T> =
    if (enabled) {
        tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = easing)
    } else {
        snap()
    }

/** Simple cross-fade content transform (for AnimatedContent.transitionSpec). */
fun fadeTransform(enabled: Boolean): ContentTransform =
    fadeIn(tweenOrSnap(enabled)) togetherWith fadeOut(tweenOrSnap(enabled))

/** Fade + scale content transform, for icon/status swaps. */
fun fadeScaleTransform(enabled: Boolean, scale: Float = 0.7f): ContentTransform =
    (fadeIn(tweenOrSnap(enabled)) + scaleIn(tweenOrSnap(enabled), initialScale = scale)) togetherWith
        (fadeOut(tweenOrSnap(enabled)) + scaleOut(tweenOrSnap(enabled), targetScale = scale))

/** Forward (push deeper) screen transform. */
fun forwardTransform(enabled: Boolean): ContentTransform =
    (slideInHorizontally(tweenOrSnap(enabled, Motion.Screen)) { it / 6 } +
        fadeIn(tweenOrSnap(enabled, Motion.Screen))) togetherWith
        (slideOutHorizontally(tweenOrSnap(enabled, Motion.Screen)) { -it / 6 } +
            fadeOut(tweenOrSnap(enabled, Motion.Screen)))

/** Back (pop shallower) screen transform. */
fun backTransform(enabled: Boolean): ContentTransform =
    (slideInHorizontally(tweenOrSnap(enabled, Motion.Screen)) { -it / 6 } +
        fadeIn(tweenOrSnap(enabled, Motion.Screen))) togetherWith
        (slideOutHorizontally(tweenOrSnap(enabled, Motion.Screen)) { it / 6 } +
            fadeOut(tweenOrSnap(enabled, Motion.Screen)))

/** Shared expand+fade enter used by explainers and reveals. */
@Composable
fun expandFadeIn(durationMillis: Int = Motion.Medium): EnterTransition =
    expandVertically(motionTween(durationMillis)) + fadeIn(motionTween(durationMillis))

@Composable
fun shrinkFadeOut(durationMillis: Int = Motion.Medium): ExitTransition =
    shrinkVertically(motionTween(durationMillis)) + fadeOut(motionTween(durationMillis))

/** Forward (push) screen enter/exit, for navigating deeper. */
@Composable
fun slideForwardIn(): EnterTransition =
    slideInHorizontally(motionTween(Motion.Screen)) { it / 6 } + fadeIn(motionTween(Motion.Screen))

@Composable
fun slideForwardOut(): ExitTransition =
    slideOutHorizontally(motionTween(Motion.Screen)) { -it / 6 } + fadeOut(motionTween(Motion.Screen))

@Composable
fun slideBackIn(): EnterTransition =
    slideInHorizontally(motionTween(Motion.Screen)) { -it / 6 } + fadeIn(motionTween(Motion.Screen))

@Composable
fun slideBackOut(): ExitTransition =
    slideOutHorizontally(motionTween(Motion.Screen)) { it / 6 } + fadeOut(motionTween(Motion.Screen))

/**
 * Gentle press feedback. Pass the SAME [interactionSource] the clickable/Button uses
 * so it receives press events. No-op when motion is disabled.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f
): Modifier = composed {
    if (!motionEnabled()) return@composed this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(Motion.Fast, easing = Motion.Standard),
        label = "pressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Self-contained clickable with built-in press-scale feedback and ripple. */
fun Modifier.clickableScale(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && motionEnabled()) pressedScale else 1f,
        animationSpec = tween(Motion.Fast, easing = Motion.Standard),
        label = "clickableScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        onClick = onClick
    )
}
