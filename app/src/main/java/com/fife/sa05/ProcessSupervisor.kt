package com.fife.sa05

/** A native process the service can restart on its own, without rebuilding the whole stack. */
internal enum class SupervisedRole(val title: String) {
    XRAY("Xray"),
    BYEDPI("ByeDPI"),
    BRIDGE("мост совместимости"),
    TUN2SOCKS("tun2socks")
}

internal object ProcessRestartPolicy {
    const val MAX_ATTEMPTS = 4
    const val BASE_DELAY_MS = 500L
    const val MAX_DELAY_MS = 8_000L

    /** Exponential backoff: 500, 1000, 2000, 4000 ms, capped at [MAX_DELAY_MS]. */
    fun backoffMs(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be 1-based" }
        val shift = (attempt - 1).coerceAtMost(MAX_SHIFT)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }

    private const val MAX_SHIFT = 16
}

/**
 * Which roles a backend actually runs.
 *
 * [xrayRuntime] matters for Full Auto only: its ByeDPI branch and compatibility bridge exist
 * only after YouTube optimization has swapped the runtime config in.
 */
internal fun supervisedRoles(
    backend: VpnBackend,
    xrayRuntime: XrayRuntime
): List<SupervisedRole> = when (backend) {
    VpnBackend.PROXY_ONLY -> listOf(SupervisedRole.XRAY, SupervisedRole.TUN2SOCKS)
    VpnBackend.LOCAL_BYPASS -> listOf(
        SupervisedRole.BYEDPI,
        SupervisedRole.BRIDGE,
        SupervisedRole.TUN2SOCKS
    )
    VpnBackend.FULL_AUTO -> if (xrayRuntime == XrayRuntime.FULL_AUTO_YOUTUBE) {
        listOf(
            SupervisedRole.XRAY,
            SupervisedRole.BYEDPI,
            SupervisedRole.BRIDGE,
            SupervisedRole.TUN2SOCKS
        )
    } else {
        listOf(SupervisedRole.XRAY, SupervisedRole.TUN2SOCKS)
    }
}

/**
 * Ordered restart cascade for a dead role: the role itself, followed by everything downstream
 * that talks to it and would otherwise be left pointing at a port nobody listens on.
 *
 * Local Bypass chains ByeDPI → bridge → tun2socks. Full Auto's YouTube branch chains
 * ByeDPI → bridge, but Xray itself dials the bridge per connection, so a bridge restart does
 * not drag Xray with it.
 */
internal fun restartCascade(
    backend: VpnBackend,
    xrayRuntime: XrayRuntime,
    failed: SupervisedRole
): List<SupervisedRole> {
    val roles = supervisedRoles(backend, xrayRuntime)
    if (failed !in roles) return emptyList()
    val downstream = when (backend) {
        VpnBackend.PROXY_ONLY -> when (failed) {
            SupervisedRole.XRAY -> listOf(SupervisedRole.TUN2SOCKS)
            else -> emptyList()
        }
        VpnBackend.LOCAL_BYPASS -> when (failed) {
            SupervisedRole.BYEDPI -> listOf(SupervisedRole.BRIDGE, SupervisedRole.TUN2SOCKS)
            SupervisedRole.BRIDGE -> listOf(SupervisedRole.TUN2SOCKS)
            else -> emptyList()
        }
        VpnBackend.FULL_AUTO -> when (failed) {
            SupervisedRole.XRAY -> listOf(SupervisedRole.TUN2SOCKS)
            SupervisedRole.BYEDPI -> listOf(SupervisedRole.BRIDGE)
            else -> emptyList()
        }
    }
    return listOf(failed) + downstream.filter { it in roles }
}

/**
 * Tracks per-role restart attempts so a crash-looping process stops tearing the stack down
 * over and over. Pure bookkeeping — the caller owns the actual respawning.
 */
internal class ProcessSupervisor(
    private val maxAttempts: Int = ProcessRestartPolicy.MAX_ATTEMPTS
) {
    private val attempts = mutableMapOf<SupervisedRole, Int>()

    fun reset() = attempts.clear()

    /** A role that came back healthy earns a fresh budget. */
    fun noteHealthy(role: SupervisedRole) {
        attempts.remove(role)
    }

    fun attemptsFor(role: SupervisedRole): Int = attempts[role] ?: 0

    /**
     * Registers one more restart attempt for [role] and returns how long to wait first,
     * or `null` when the budget is spent and the caller should fall back to a full restart.
     */
    fun nextBackoffMs(role: SupervisedRole): Long? {
        val attempt = attemptsFor(role) + 1
        if (attempt > maxAttempts) return null
        attempts[role] = attempt
        return ProcessRestartPolicy.backoffMs(attempt)
    }
}
