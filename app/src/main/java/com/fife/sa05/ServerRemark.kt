package com.fife.sa05

internal data class ServerRemark(
    val name: String,
    val flag: String?
)

internal fun parseServerRemark(raw: String): ServerRemark {
    val codePoints = raw.codePoints().toArray()
    val flagIndex = (0 until codePoints.lastIndex).firstOrNull { index ->
        codePoints[index].isRegionalIndicator() &&
            codePoints[index + 1].isRegionalIndicator()
    } ?: return ServerRemark(name = raw.trim(), flag = null)

    val flag = String(codePoints, flagIndex, 2)
    val name = buildString {
        append(String(codePoints, 0, flagIndex))
        append(
            String(
                codePoints,
                flagIndex + 2,
                codePoints.size - flagIndex - 2
            )
        )
    }
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('|', '·', '•', '-', '—', '–')
        .trim()

    return ServerRemark(name = name, flag = flag)
}

private fun Int.isRegionalIndicator(): Boolean = this in 0x1F1E6..0x1F1FF
