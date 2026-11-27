package com.github.livingwithhippos.phonarr.core

import com.github.livingwithhippos.phonarr.core.util.ServerAddresses
import com.github.livingwithhippos.phonarr.core.util.serverAddresses
import com.github.livingwithhippos.phonarr.core.util.sortLan
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class NetworkAddressesTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun wifiFirstSiteLocalIpv4Only() {
        val entries = listOf(
            "rndis0" to ip("192.168.42.129"),
            "eth0" to ip("10.0.0.5"),
            "wlan0" to ip("fe80::1"),
            "rmnet0" to ip("100.64.3.2"), // carrier NAT, not reachable from the LAN
            "wlan0" to ip("192.168.1.23"),
            "wlan1" to ip("192.168.1.23"),
        )
        assertEquals(listOf("192.168.1.23", "192.168.42.129", "10.0.0.5"), sortLan(entries))
    }

    @Test
    fun notificationAddresses() {
        assertEquals(
            ServerAddresses("127.0.0.1:9797", listOf("127.0.0.1:9797"), noNetwork = false),
            serverAddresses("127.0.0.1", 9797, listOf("192.168.1.23")),
        )
        assertEquals(
            ServerAddresses("192.168.1.23:9797", listOf("192.168.1.23:9797", "10.0.0.5:9797"), noNetwork = false),
            serverAddresses("0.0.0.0", 9797, listOf("192.168.1.23", "10.0.0.5")),
        )
        assertEquals(
            ServerAddresses("0.0.0.0:9797", emptyList(), noNetwork = true),
            serverAddresses("0.0.0.0", 9797, emptyList()),
        )
    }
}
