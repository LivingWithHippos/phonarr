package com.github.livingwithhippos.phonarr.core.torznab

import com.github.livingwithhippos.phonarr.core.util.ParseUtil
import java.time.ZonedDateTime

/** A single search result. Mirrors the fields of Prowlarr's ReleaseInfo/TorrentInfo that Torznab exposes. */
class ReleaseInfo {
    var title: String? = null
    var description: String? = null
    var guid: String? = null
    var downloadUrl: String? = null
    var magnetUrl: String? = null
    var infoHash: String? = null
    var infoUrl: String? = null
    var commentUrl: String? = null
    var posterUrl: String? = null
    var categories: MutableList<IndexerCategory> = mutableListOf()
    var size: Long? = null
    var seeders: Int? = null
    var peers: Int? = null
    var files: Int? = null
    var grabs: Int? = null
    var publishDate: ZonedDateTime? = null
    var downloadVolumeFactor: Double? = null
    var uploadVolumeFactor: Double? = null
    var minimumRatio: Double? = null
    var minimumSeedTime: Long? = null
    var imdbId: Int = 0
    var tmdbId: Int = 0
    var tvdbId: Int = 0
    var tvRageId: Int = 0
    var tvMazeId: Int = 0
    var traktId: Int = 0
    var doubanId: Int = 0
    var year: Int = 0
    var genres: MutableList<String>? = null
    var author: String? = null
    var bookTitle: String? = null
    var publisher: String? = null
    var artist: String? = null
    var album: String? = null
    var label: String? = null
    var track: String? = null
    val flags: MutableList<String> = mutableListOf()

    // filled in by the search service
    var indexerId: Int = 0
    var indexerName: String? = null
    var indexerPrivacy: String = "public"

    override fun toString() = "ReleaseInfo(title=$title, size=$size, seeders=$seeders)"
}

enum class SearchType(val torznab: String) {
    Search("search"), Tv("tvsearch"), Movie("movie"), Music("music"), Book("book");

    companion object {
        fun fromTorznab(t: String?): SearchType? = when (t?.lowercase()) {
            "search" -> Search
            "tvsearch" -> Tv
            "movie" -> Movie
            "music", "audio" -> Music
            "book" -> Book
            else -> null
        }
    }
}

/** Search parameters as received from a Torznab client. */
data class SearchQuery(
    val type: SearchType = SearchType.Search,
    val q: String? = null,
    val categories: List<Int> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
    val season: Int? = null,
    val ep: String? = null,
    val imdbId: String? = null,
    val tvdbId: Int? = null,
    val tmdbId: Int? = null,
    val rId: Int? = null,
    val tvMazeId: Int? = null,
    val traktId: Int? = null,
    val doubanId: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val label: String? = null,
    val track: String? = null,
    val title: String? = null,
    val author: String? = null,
    val publisher: String? = null,
) {
    val isIdSearch: Boolean
        get() = !imdbId.isNullOrBlank() || tvdbId != null || tmdbId != null || rId != null ||
                tvMazeId != null || traktId != null || doubanId != null

    val isRssSearch: Boolean get() = q.isNullOrBlank() && !isIdSearch && season == null

    /** Same rules as Prowlarr's TvSearchCriteria.EpisodeSearchString. */
    val episodeSearchString: String
        get() {
            if (season == null || season == 0) return ""
            val s = season.toString().padStart(2, '0')
            if (season in 1000..9999 && ep != null && Regex("^\\d{2}/\\d{2}$").matches(ep)) {
                return "$season.${ep.replace('/', '.')}"
            }
            if (ep.isNullOrBlank()) return "S$s"
            val epInt = runCatching { ParseUtil.coerceInt(ep) }.getOrNull()
            return if (epInt != null && ep.any { it.isDigit() }) "S${s}E${
                epInt.toString().padStart(2, '0')
            }" else "S${s}E$ep"
        }

    /**
     * Port of Prowlarr's NewznabRequest.QueryToParams: moves `{Season:1}`, `{ImdbId:tt123}` and similar
     * tokens typed in the query into the matching fields (used by Prowlarr's /api/v1/search).
     */
    fun withQueryTokens(): SearchQuery {
        val text = q?.takeIf { it.isNotBlank() } ?: return this
        val regex = when (type) {
            SearchType.Tv -> TV_TOKENS
            SearchType.Movie -> MOVIE_TOKENS
            SearchType.Music -> MUSIC_TOKENS
            SearchType.Book -> BOOK_TOKENS
            SearchType.Search -> return this
        }
        var result = this
        var rest = text
        for (m in regex.findAll(text)) {
            fun g(name: String): String? = runCatching { m.groups[name]?.value }.getOrNull()
            fun gi(name: String): Int? = g(name)?.trim()?.toIntOrNull()
            result = result.copy(
                imdbId = g("imdbid") ?: result.imdbId,
                rId = if (g("rid") != null) gi("rid") else result.rId,
                tvdbId = if (g("tvdbid") != null) gi("tvdbid") else result.tvdbId,
                tmdbId = if (g("tmdbid") != null) gi("tmdbid") else result.tmdbId,
                tvMazeId = if (g("tvmazeid") != null) gi("tvmazeid") else result.tvMazeId,
                doubanId = if (g("doubanid") != null) gi("doubanid") else result.doubanId,
                traktId = if (g("traktid") != null) gi("traktid") else result.traktId,
                season = if (g("season") != null) gi("season") else result.season,
                ep = g("episode") ?: result.ep,
                year = if (g("year") != null) gi("year") else result.year,
                genre = g("genre") ?: result.genre,
                artist = g("artist") ?: result.artist,
                album = g("album") ?: result.album,
                track = g("track") ?: result.track,
                label = g("label") ?: result.label,
                author = g("author") ?: result.author,
                title = g("title") ?: result.title,
                publisher = g("publisher") ?: result.publisher,
            )
            rest = rest.replace(m.value, "")
        }
        return result.copy(q = rest.trim().ifEmpty { null })
    }

    companion object {
        private const val TOKEN_OPTS = "(?i)"
        private val TV_TOKENS = Regex(
            TOKEN_OPTS + "\\{((?:imdbid\\:)(?<imdbid>[^{]+)|(?:rid\\:)(?<rid>[^{]+)|(?:tvdbid\\:)(?<tvdbid>[^{]+)|(?:tmdbid\\:)(?<tmdbid>[^{]+)|" +
                    "(?:tvmazeid\\:)(?<tvmazeid>[^{]+)|(?:doubanid\\:)(?<doubanid>[^{]+)|(?:season\\:)(?<season>[^{]+)|(?:episode\\:)(?<episode>[^{]+)|" +
                    "(?:year\\:)(?<year>[^{]+)|(?:genre\\:)(?<genre>[^{]+))\\}",
        )
        private val MOVIE_TOKENS = Regex(
            TOKEN_OPTS + "\\{((?:imdbid\\:)(?<imdbid>[^{]+)|(?:doubanid\\:)(?<doubanid>[^{]+)|(?:tmdbid\\:)(?<tmdbid>[^{]+)|" +
                    "(?:traktid\\:)(?<traktid>[^{]+)|(?:year\\:)(?<year>[^{]+)|(?:genre\\:)(?<genre>[^{]+))\\}",
        )
        private val MUSIC_TOKENS = Regex(
            TOKEN_OPTS + "\\{((?:artist\\:)(?<artist>[^{]+)|(?:album\\:)(?<album>[^{]+)|(?:track\\:)(?<track>[^{]+)|" +
                    "(?:label\\:)(?<label>[^{]+)|(?:year\\:)(?<year>[^{]+)|(?:genre\\:)(?<genre>[^{]+))\\}",
        )
        private val BOOK_TOKENS = Regex(
            TOKEN_OPTS + "\\{((?:author\\:)(?<author>[^{]+)|(?:publisher\\:)(?<publisher>[^{]+)|(?:title\\:)(?<title>[^{]+)|" +
                    "(?:year\\:)(?<year>[^{]+)|(?:genre\\:)(?<genre>[^{]+))\\}",
        )

        /** Query of Prowlarr's `GET /api/v1/search` (query, type, categories, limit, offset). */
        fun fromProwlarr(
            query: String?,
            type: String?,
            categories: List<Int>,
            limit: Int?,
            offset: Int?
        ): SearchQuery =
            SearchQuery(
                type = SearchType.fromTorznab(type) ?: SearchType.Search,
                q = query?.takeIf { it.isNotBlank() },
                categories = categories,
                limit = limit,
                offset = offset,
            ).withQueryTokens()

        fun fromParams(params: (String) -> String?): SearchQuery {
            fun int(name: String) = params(name)?.trim()?.toIntOrNull()
            fun str(name: String) = params(name)?.takeIf { it.isNotBlank() }
            return SearchQuery(
                type = SearchType.fromTorznab(params("t")) ?: SearchType.Search,
                q = str("q"),
                categories = params("cat")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                    ?: emptyList(),
                limit = int("limit"),
                offset = int("offset"),
                season = int("season"),
                ep = str("ep"),
                imdbId = str("imdbid"),
                tvdbId = int("tvdbid"),
                tmdbId = int("tmdbid"),
                rId = int("rid"),
                tvMazeId = int("tvmazeid"),
                traktId = int("traktid"),
                doubanId = int("doubanid"),
                year = int("year"),
                genre = str("genre"),
                album = str("album"),
                artist = str("artist"),
                label = str("label"),
                track = str("track"),
                title = str("title"),
                author = str("author"),
                publisher = str("publisher"),
            )
        }
    }
}
