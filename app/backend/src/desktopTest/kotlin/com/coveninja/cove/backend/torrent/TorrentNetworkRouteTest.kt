package com.coveninja.cove.backend.torrent

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TorrentNetworkRouteTest {

    private fun routeVia(v4: String?, v6: String?) = currentTorrentRoute { destination ->
        when (destination) {
            is Inet4Address -> v4?.let(InetAddress::getByName)
            else -> v6?.let(InetAddress::getByName)
        }
    }

    // Exit node on: the kernel sends from the Tailscale addresses, and only those may carry
    // DHT, trackers and uTP.
    @Test
    fun `the session listens only on the addresses the system routes from`() {
        val route = routeVia("100.64.120.23", "fd7a:115c:a1e0::4536:7819")

        assertEquals("100.64.120.23:6881,[fd7a:115c:a1e0:0:0:0:4536:7819]:6881", route.listenInterfaces())
    }

    // Exit node off on a network without IPv6: one address, and no IPv6 socket that cannot
    // reach anything.
    @Test
    fun `a family with no route gets no socket`() {
        assertEquals("192.168.32.56:6881", routeVia("192.168.32.56", null).listenInterfaces())
    }

    @Test
    fun `with no route at all libtorrent listens everywhere rather than nowhere`() {
        val route = routeVia(null, null)

        assertTrue(route.isOffline)
        assertEquals("0.0.0.0:6881,[::]:6881", route.listenInterfaces())
    }

    @Test
    fun `a scope suffix is dropped because libtorrent cannot parse it`() {
        val route = TorrentRoute(ipv4 = null, ipv6 = "fe80:0:0:0:1:2:3:4%tailscale0".substringBefore('%'))

        assertEquals("[fe80:0:0:0:1:2:3:4]:6881", route.listenInterfaces())
    }

    @Test
    fun `the probe answers with the machine's real routed address or nothing`() {
        // Whatever this machine's network is, the answer must be a usable local address or none:
        // never the wildcard or loopback, which would bind the session to nothing useful.
        val route = currentTorrentRoute()
        listOfNotNull(route.ipv4, route.ipv6).map(InetAddress::getByName).forEach { address ->
            assertTrue(!address.isAnyLocalAddress && !address.isLoopbackAddress, "was: $address")
        }
    }
}
