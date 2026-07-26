package com.fife.sa05

/**
 * Bounded in-memory log of native process output, so a shared report can explain what the
 * stack was doing without keeping anything on disk between runs.
 *
 * Kept small on purpose: this exists to diagnose the last failure, not to be an audit trail.
 */
internal class RingLog(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lines = ArrayDeque<String>(capacity)

    @Synchronized
    fun record(tag: String, line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast("[$tag] $line")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    @Synchronized
    fun size(): Int = lines.size

    companion object {
        const val DEFAULT_CAPACITY = 400
    }
}

/** Process output shared by the VPN service and the report builder. */
internal object DiagnosticLog {
    private val log = RingLog()

    fun record(tag: String, line: String) = log.record(tag, line)

    fun snapshot(): List<String> = log.snapshot()

    fun clear() = log.clear()
}
