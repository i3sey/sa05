package com.fife.sa05

import java.util.Locale

/** `1 ч 05 мин`, `12 мин 03 с`, `47 с`. Negative input is clamped to zero. */
internal fun formatUptime(millis: Long): String {
    val totalSeconds = (millis / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> String.format(Locale.US, "%d ч %02d мин", hours, minutes)
        minutes > 0 -> String.format(Locale.US, "%d мин %02d с", minutes, seconds)
        else -> "$seconds с"
    }
}

/**
 * Binary units, because that is what every other traffic counter on the device shows.
 * One decimal below 10 units so `1,4 МБ` does not read as a jump from `1 МБ` to `2 МБ`.
 */
internal fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    if (safe < 1024) return "$safe Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var value = safe.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val pattern = if (value < 10) "%.1f %s" else "%.0f %s"
    return String.format(Locale.US, pattern, value, units[unitIndex]).replace('.', ',')
}
