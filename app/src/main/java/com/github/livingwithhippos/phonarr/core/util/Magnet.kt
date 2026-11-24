package com.github.livingwithhippos.phonarr.core.util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Port of Prowlarr's MagnetLinkBuilder. */
object Magnet {
    private val trackers = listOf(
        "http://tracker.opentrackr.org:1337/announce",
        "udp://tracker.auctor.tv:6969/announce",
        "udp://opentracker.i2p.rocks:6969/announce",
        "https://opentracker.i2p.rocks:443/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://uploads.gamecoast.net:6969/announce",
        "udp://tracker1.bt.moack.co.kr:80/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker.skyts.net:6969/announce",
        "udp://tracker-udp.gbitt.info:80/announce",
        "udp://open.tracker.ink:6969/announce",
        "udp://movies.zsw.ca:6969/announce",
    )

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

    fun buildPublicMagnetLink(infoHash: String, title: String?): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(infoHash.trim().uppercase())
        if (!title.isNullOrEmpty()) sb.append("&dn=").append(enc(title))
        trackers.forEach { sb.append("&tr=").append(enc(it)) }
        return sb.toString()
    }

    fun getInfoHashFromMagnet(magnet: String): String? = runCatching {
        ParseUtil.getArgumentFromQueryString(magnet, "xt")?.split(':')?.last()
    }.getOrNull()
}
