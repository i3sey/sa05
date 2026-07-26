package com.fife.sa05.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle

/**
 * The Material 3 type scale, unmodified.
 *
 * Roles are used as Material defines them, so the scale itself needs no overrides. What the app
 * adds is one rule of its own, below: anything whose digits change while the user is looking at
 * it gets tabular figures.
 */
val Typography = Typography()

/**
 * Fixed-width digits.
 *
 * Roboto's default figures are proportional — `1` is narrower than `8` — so a counter that
 * reticks every second reflows its own text and visibly jitters. The uptime, the traffic
 * counters and the latency readouts all update in place, so they opt into `tnum` and stay put.
 * Prose keeps proportional figures, which read better in a sentence.
 */
fun TextStyle.tabularFigures(): TextStyle = copy(
    fontFeatureSettings = listOfNotNull(fontFeatureSettings?.takeIf(String::isNotBlank), "tnum")
        .joinToString(", ")
)
