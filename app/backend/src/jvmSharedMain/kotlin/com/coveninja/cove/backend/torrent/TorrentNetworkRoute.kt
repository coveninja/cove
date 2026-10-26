package com.coveninja.cove.backend.torrent

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The local addresses the system sends internet traffic from right now, one per family.
 *
 * libtorrent opens a socket per local address when told to listen everywhere, and runs a DHT
 * node and tracker announces on each. On a machine with a VPN or a Tailscale exit node that is
 * the wrong thing: only one of those addresses can actually reach the internet at a time, and
 * which one flips when the exit node is switched on or off — a change of routes, not of
 * addresses, so libtorrent's own address watcher never notices. Half of the DHT, the UDP
 * trackers and every uTP connection then go out from an address whose packets are dropped.
 *
 * The probes approximate the public-internet route without sending packets. They do not
 * describe every destination on a split-tunnel or policy-routed network, and are not a VPN
 * kill switch: outgoing TCP connections still follow the operating system's routing policy.
 */
internal data class TorrentRoute(val ipv4: String?, val ipv6: String?) {
    val isOffline: Boolean get() = ipv4 == null && ipv6 == null

    /** libtorrent's `listen_interfaces`: the routed addresses, or everywhere when there are none. */
    fun listenInterfaces(port: Int = TORRENT_LISTEN_PORT): String =
        listOfNotNull(ipv4?.let { "$it:$port" }, ipv6?.let { "[$it]:$port" })
            .ifEmpty { listOf("0.0.0.0:$port", "[::]:$port") }
            .joinToString(",")
}

internal fun currentTorrentRoute(
    sourceFor: (InetAddress) -> InetAddress? = ::routedSourceAddress,
): TorrentRoute = TorrentRoute(
    ipv4 = sourceFor(IPV4_PROBE)?.takeIf { it is Inet4Address }?.hostAddress,
    // A scope suffix ("%tailscale0") is not something libtorrent's address parser accepts.
    ipv6 = sourceFor(IPV6_PROBE)?.takeIf { it is Inet6Address }?.hostAddress?.substringBefore('%'),
)

/**
 * The address the kernel would send from to reach [destination], or null when it has no route.
 *
 * Connecting a UDP socket sends nothing; it only performs the route lookup and fixes the local
 * address, which is what is read back here.
 */
internal fun routedSourceAddress(destination: InetAddress): InetAddress? = runCatching {
    DatagramSocket().use { socket ->
        socket.connect(InetSocketAddress(destination, DNS_PORT))
        socket.localAddress?.takeUnless { it.isAnyLocalAddress || it.isLoopbackAddress }
    }
}.getOrNull()

/** libtorrent's own default port; it moves up by itself if another client holds it. */
internal const val TORRENT_LISTEN_PORT = 6881

private const val DNS_PORT = 53

// Literals rather than names: resolving a host to find a route would make the check depend on
// the very DNS that may be what just changed.
private val IPV4_PROBE: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
private val IPV6_PROBE: InetAddress = InetAddress.getByName("2606:4700:4700::1111")
