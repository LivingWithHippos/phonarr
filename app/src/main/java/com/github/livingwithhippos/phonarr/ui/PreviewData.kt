package com.github.livingwithhippos.phonarr.ui

import com.github.livingwithhippos.phonarr.core.torznab.IndexerCategory
import com.github.livingwithhippos.phonarr.core.torznab.ReleaseInfo
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Sample search results for Compose previews. */
internal fun sampleReleases(): List<ReleaseInfo> {
    val now = ZonedDateTime.of(2026, 11, 27, 12, 0, 0, 0, ZoneOffset.UTC)
    return listOf(
        ReleaseInfo().apply {
            title = "The Matrix 1999 1080p BluRay x264"
            indexerName = "Test Site"
            size = 8_500_000_000
            seeders = 1024
            peers = 87
            grabs = 15_230
            files = 3
            publishDate = now.minusDays(3)
            categories = mutableListOf(IndexerCategory(2040, "Movies/HD"))
            magnetUrl = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=The+Matrix"
            infoHash = "0123456789ABCDEF0123456789ABCDEF01234567"
            infoUrl = "https://example.org/torrent/1"
            imdbId = 133093
            tmdbId = 603
            year = 1999
            downloadVolumeFactor = 0.0
            uploadVolumeFactor = 1.0
            description = "<b>Remastered</b> release with English subtitles."
        },
        ReleaseInfo().apply {
            title = "The Matrix Reloaded 2003 2160p WEB-DL DDP5.1 HDR"
            indexerName = "Private Site"
            indexerPrivacy = "private"
            size = 21_300_000_000
            seeders = 4
            peers = 9
            publishDate = now.minusDays(40)
            categories = mutableListOf(IndexerCategory(2045, "Movies/UHD"))
            downloadUrl = "https://example.org/download/2.torrent"
            commentUrl = "https://example.org/torrent/2#comments"
            minimumRatio = 1.0
            minimumSeedTime = 172_800
        },
        ReleaseInfo().apply {
            title = "The Matrix Revolutions 2003 720p"
            indexerName = "Test Site"
            size = 4_200_000_000
            seeders = 0
            peers = 0
            publishDate = now.minusDays(400)
            downloadUrl = "https://example.org/download/3.torrent"
        },
    )
}
