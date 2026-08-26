package com.fife.sa05

import kotlin.math.roundToLong

/**
 * Объём трафика, прошедший через БС-туннель за текущую сессию.
 *
 * Считается по счётчикам UID приложения ([android.net.TrafficStats]): в режиме
 * БС-туннеля весь трафик идёт сокетами приложения (relayc → HTTPS → Functions),
 * а само приложение исключено из TUN, поэтому счётчик UID отражает именно
 * wire-трафик туннеля, а не внутрипроцессный loopback.
 */
data class TrafficUsage(
    val rxBytes: Long,
    val txBytes: Long
) {
    val totalBytes: Long
        get() = if (rxBytes < 0 || txBytes < 0) UNKNOWN else rxBytes + txBytes

    val isKnown: Boolean
        get() = rxBytes >= 0 && txBytes >= 0

    companion object {
        const val UNKNOWN = -1L
        val EMPTY = TrafficUsage(0L, 0L)
        val UNAVAILABLE = TrafficUsage(UNKNOWN, UNKNOWN)
    }
}

/**
 * Форматирует объём байт в человекочитаемую строку с русскими единицами.
 * Возвращает «—» для неизвестного значения (счётчик недоступен).
 */
fun formatTrafficBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes Б"
    var value = bytes.toDouble()
    var unitIndex = -1
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val unit = units[unitIndex]
    val text = if (value >= 100) {
        value.roundToLong().toString()
    } else {
        val tenths = (value * 10).roundToLong()
        "${tenths / 10},${tenths % 10}"
    }
    return "$text $unit"
}
