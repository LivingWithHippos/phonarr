package com.github.livingwithhippos.phonarr.core.torznab

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Xml {
    // control characters and unpaired surrogates are not allowed in XML 1.0
    private val invalid = Regex("(?<![\\uD800-\\uDBFF])[\\uDC00-\\uDFFF]|[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])|[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F-\\x9F\\uFEFF\\uFFFE\\uFFFF]")

    fun clean(s: String?): String? = s?.replace(invalid, "")

    fun escape(s: String?): String {
        if (s == null) return ""
        val c = clean(s)!!
        val sb = StringBuilder(c.length + 16)
        for (ch in c) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}

/** Builds Torznab RSS output, following Prowlarr's NewznabResults. */
object TorznabXml {
    private val pubDateFormat = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)

    fun error(code: Int, description: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<error code=\"$code\" description=\"${Xml.escape(description)}\" />\n"

    /**
     * @param linkFor returns the URL that clients should use to download a release
     *                (usually a link back to Phonarr's download proxy, or a magnet).
     */
    fun results(
        releases: List<ReleaseInfo>,
        title: String = "Phonarr",
        now: ZonedDateTime = ZonedDateTime.now(),
        linkFor: (ReleaseInfo) -> String?,
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<rss version=\"1.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\" xmlns:torznab=\"http://torznab.com/schemas/2015/feed\">\n")
        sb.append("  <channel>\n")
        sb.append("    <atom:link rel=\"self\" type=\"application/rss+xml\" />\n")
        sb.append("    <title>").append(Xml.escape(title)).append("</title>\n")
        for (r in releases) {
            val link = linkFor(r) ?: ""
            sb.append("    <item>\n")
            el(sb, "title", r.title)
            el(sb, "description", r.description)
            el(sb, "guid", r.guid ?: link)
            sb.append("      <prowlarrindexer id=\"").append(r.indexerId).append("\" type=\"")
                .append(r.indexerPrivacy).append("\">").append(Xml.escape(r.indexerName)).append("</prowlarrindexer>\n")
            r.infoUrl?.let { el(sb, "comments", it) }
            el(sb, "pubDate", pubDateFormat.format(r.publishDate ?: now))
            el(sb, "size", (r.size ?: 0).toString())
            el(sb, "link", link)
            r.categories.forEach { el(sb, "category", it.id.toString()) }
            sb.append("      <enclosure url=\"").append(Xml.escape(link)).append('"')
            r.size?.let { sb.append(" length=\"").append(it).append('"') }
            sb.append(" type=\"application/x-bittorrent\" />\n")
            r.categories.forEach { attr(sb, "category", it.id) }
            r.flags.forEach { attr(sb, "tag", it) }
            r.genres?.let { attr(sb, "genre", it.joinToString(", ")) }
            if (r.tvRageId != 0) attr(sb, "rageid", r.tvRageId)
            if (r.tvdbId != 0) attr(sb, "tvdbid", r.tvdbId)
            if (r.imdbId != 0) attr(sb, "imdb", r.imdbId.toString().padStart(7, '0'))
            if (r.tmdbId != 0) attr(sb, "tmdbid", r.tmdbId)
            if (r.traktId != 0) attr(sb, "traktid", r.traktId)
            if (r.tvMazeId != 0) attr(sb, "tvmazeid", r.tvMazeId)
            if (r.doubanId != 0) attr(sb, "doubanid", r.doubanId)
            attr(sb, "seeders", r.seeders)
            attr(sb, "files", r.files)
            attr(sb, "grabs", r.grabs)
            attr(sb, "peers", r.peers)
            if (r.year != 0) attr(sb, "year", r.year)
            attr(sb, "author", r.author)
            attr(sb, "booktitle", r.bookTitle)
            attr(sb, "artist", r.artist)
            attr(sb, "album", r.album)
            attr(sb, "label", r.label)
            attr(sb, "track", r.track)
            attr(sb, "infohash", r.infoHash)
            if (r.magnetUrl != null) attr(sb, "magneturl", r.magnetUrl)
            attr(sb, "minimumratio", r.minimumRatio)
            attr(sb, "minimumseedtime", r.minimumSeedTime)
            attr(sb, "downloadvolumefactor", r.downloadVolumeFactor)
            attr(sb, "uploadvolumefactor", r.uploadVolumeFactor)
            attr(sb, "coverurl", r.posterUrl)
            sb.append("    </item>\n")
        }
        sb.append("  </channel>\n</rss>\n")
        return sb.toString()
    }

    private fun el(sb: StringBuilder, name: String, value: String?) {
        sb.append("      <").append(name).append('>').append(Xml.escape(value)).append("</").append(name).append(">\n")
    }

    private fun attr(sb: StringBuilder, name: String, value: Any?) {
        if (value == null) return
        val v = if (value is Double) formatDouble(value) else value.toString()
        sb.append("      <torznab:attr name=\"").append(name).append("\" value=\"").append(Xml.escape(v)).append("\" />\n")
    }

    private fun formatDouble(d: Double): String = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
}
