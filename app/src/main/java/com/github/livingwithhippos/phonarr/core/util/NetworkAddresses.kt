package com.github.livingwithhippos.phonarr.core.util

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * This device's local network IPv4 addresses (e.g. 192.168.1.23), Wi-Fi first,
 * for showing other devices where to connect.
 */
fun lanAddresses(): List<String> = runCatching {
    val entries = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { iface -> iface.inetAddresses.toList().map { iface.name to it } }
    sortLan(entries)
}.getOrDefault(emptyList())

/** Keeps site-local IPv4 addresses from (interface name, address) pairs, Wi-Fi interfaces (wlan*) first. */
internal fun sortLan(entries: List<Pair<String, InetAddress>>): List<String> =
    entries
        .filter { (_, address) -> address is Inet4Address && address.isSiteLocalAddress }
        // sortedBy is stable, so the system's order is kept within each group
        .sortedBy { (name, _) -> if (name.startsWith("wlan")) 0 else 1 }
        .mapNotNull { (_, address) -> address.hostAddress }
        .distinct()

/**
 * Where the server can be reached, for the notification.
 * [primary] is the address to show, [all] every address other devices can use,
 * [noNetwork] is true when listening on every interface but none has a local address.
 */
data class ServerAddresses(val primary: String, val all: List<String>, val noNetwork: Boolean)

fun serverAddresses(host: String, port: Int, lan: List<String>): ServerAddresses = when {
    host != "0.0.0.0" -> ServerAddresses("$host:$port", listOf("$host:$port"), noNetwork = false)
    lan.isEmpty() -> ServerAddresses("$host:$port", emptyList(), noNetwork = true)
    else -> ServerAddresses("${lan.first()}:$port", lan.map { "$it:$port" }, noNetwork = false)
}
