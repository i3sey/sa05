package com.fife.sa05

import android.net.TrafficStats
import android.os.Process

data class VpnTraffic(val rxBytes: Long = 0L, val txBytes: Long = 0L)

/**
 * Counts what the app's own UID moved since the tunnel came up.
 *
 * The client package is always excluded from its own TUN, so this UID carries exactly the
 * outbound sockets of xray, ByeDPI, tun2socks and TG WS Proxy — that is, the tunnel's real
 * traffic. It also includes the app's own housekeeping (connection probes, subscription
 * refresh, update checks), which is small but not zero.
 *
 * [TrafficStats] counters are monotonic per boot and may be [TrafficStats.UNSUPPORTED] on some
 * devices, so both cases collapse to zero rather than to a negative or bogus number.
 */
internal class UidTrafficCounter(
    private val uid: Int = Process.myUid(),
    private val readRx: (Int) -> Long = TrafficStats::getUidRxBytes,
    private val readTx: (Int) -> Long = TrafficStats::getUidTxBytes
) {
    private var baseline: VpnTraffic? = null

    fun reset() {
        baseline = null
    }

    /** Marks the current counters as zero. Call when the tunnel connects. */
    fun start() {
        baseline = read()
    }

    fun sinceStart(): VpnTraffic {
        val base = baseline ?: return VpnTraffic()
        val now = read()
        return VpnTraffic(
            rxBytes = (now.rxBytes - base.rxBytes).coerceAtLeast(0),
            txBytes = (now.txBytes - base.txBytes).coerceAtLeast(0)
        )
    }

    private fun read(): VpnTraffic {
        val rx = readRx(uid)
        val tx = readTx(uid)
        return VpnTraffic(
            rxBytes = rx.takeIf { it >= 0 } ?: 0L,
            txBytes = tx.takeIf { it >= 0 } ?: 0L
        )
    }
}
