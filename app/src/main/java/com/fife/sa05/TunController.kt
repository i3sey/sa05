package com.fife.sa05

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

internal const val TUN_MTU = 1500
internal const val TUN_DNS = "1.1.1.1"
internal const val TUN_IPV4_ADDRESS = "10.10.10.1"
internal const val TUN_IPV4_PREFIX = 30
internal const val TUN_IPV6_ADDRESS = "fd00::1"
internal const val TUN_IPV6_PREFIX = 126

/** tun2socks' own addresses on the TUN subnets, one hop up from the interface's own. */
internal const val TUN2SOCKS_IPV4_ADDRESS = "10.10.10.2"
internal const val TUN2SOCKS_IPV4_NETMASK = "255.255.255.252"
internal const val TUN2SOCKS_IPV6_ADDRESS = "fd00::2"

internal data class TunAddress(val address: String, val prefixLength: Int)

internal data class TunRoute(val address: String, val prefixLength: Int)

internal data class TunPlan(
    val addresses: List<TunAddress>,
    val routes: List<TunRoute>
)

/**
 * IPv4 always goes through the tunnel.
 *
 * IPv6 is captured as a blackhole by default: tun2socks only speaks IPv4, so AAAA packets
 * that enter the TUN are dropped and applications fall back to IPv4 instead of leaving the
 * VPN with the real address. Without the `::/0` route Android keeps IPv6 on the underlying
 * network and every AAAA connection leaks around the tunnel.
 *
 * [allowIpv6Bypass] hands IPv6 back to the system for IPv6-only networks (NAT64/DNS64),
 * where blackholing it would break connectivity outright. That re-opens the leak, which is
 * why it is off by default.
 */
internal fun tunPlan(allowIpv6Bypass: Boolean): TunPlan {
    val addresses = mutableListOf(TunAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX))
    val routes = mutableListOf(TunRoute("0.0.0.0", 0))
    if (!allowIpv6Bypass) {
        addresses += TunAddress(TUN_IPV6_ADDRESS, TUN_IPV6_PREFIX)
        routes += TunRoute("::", 0)
    }
    return TunPlan(addresses = addresses, routes = routes)
}

/**
 * Owns the Android TUN interface: builder configuration, address/route plan, application
 * exclusions and the descriptor lifetime. [newBuilder] is supplied by the service because
 * `VpnService.Builder` is an inner class and may only be constructed from the service.
 */
internal class TunController(
    private val newBuilder: () -> VpnService.Builder,
    private val ownPackageName: String
) {
    private var descriptor: ParcelFileDescriptor? = null

    val established: Boolean
        get() = descriptor != null

    /** The live descriptor, so tun2socks can be respawned without rebuilding the interface. */
    val descriptorOrNull: ParcelFileDescriptor?
        get() = descriptor

    fun establish(
        session: String,
        allowIpv6Bypass: Boolean,
        excludedApps: Set<String>
    ): ParcelFileDescriptor {
        close()
        val plan = tunPlan(allowIpv6Bypass)
        val builder = newBuilder()
            .setSession(session)
            .setMtu(TUN_MTU)
            .addDnsServer(TUN_DNS)
            .setBlocking(false)
        plan.addresses.forEach { builder.addAddress(it.address, it.prefixLength) }
        plan.routes.forEach { builder.addRoute(it.address, it.prefixLength) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        // The client package must stay outside its own TUN, otherwise Xray outbound sockets
        // loop back into the tunnel they are supposed to feed.
        builder.addDisallowedApplication(ownPackageName)
        excludedApps.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot exclude $pkg", e)
            }
        }
        return (builder.establish() ?: error("Android не создал TUN-интерфейс"))
            .also { descriptor = it }
    }

    fun close() {
        runCatching { descriptor?.close() }
        descriptor = null
    }

    private companion object {
        const val TAG = "TunController"
    }
}
