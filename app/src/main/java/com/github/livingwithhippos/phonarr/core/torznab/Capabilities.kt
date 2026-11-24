package com.github.livingwithhippos.phonarr.core.torznab

/** Port of the parts of Prowlarr's IndexerCapabilities used by Cardigann indexers. */
class IndexerCapabilities {
    var limitsDefault: Int? = 100
    var limitsMax: Int? = 100
    var supportsRawSearch = false

    val searchParams = mutableListOf<String>()
    val tvSearchParams = mutableListOf<String>()
    val movieSearchParams = mutableListOf<String>()
    val musicSearchParams = mutableListOf<String>()
    val bookSearchParams = mutableListOf<String>()
    val categories = IndexerCapabilitiesCategories()

    fun paramsFor(type: SearchType): List<String> = when (type) {
        SearchType.Search -> searchParams
        SearchType.Tv -> tvSearchParams
        SearchType.Movie -> movieSearchParams
        SearchType.Music -> musicSearchParams
        SearchType.Book -> bookSearchParams
    }

    fun parseCardigannSearchModes(modes: Map<String, List<String>>?) {
        if (modes.isNullOrEmpty()) throw IllegalArgumentException("At least one search mode is required")
        if (!modes.containsKey("search")) throw IllegalArgumentException("The search mode 'search' is mandatory")
        for ((mode, params) in modes) {
            when (mode) {
                "search" -> {
                    if (params.size != 1 || params[0] != "q") {
                        throw IllegalArgumentException("In search mode 'search' only 'q' parameter is supported and it's mandatory")
                    }
                    searchParams += "q"
                }
                "tv-search" -> addParams(tvSearchParams, params, TV_PARAMS, mode)
                "movie-search" -> addParams(movieSearchParams, params, MOVIE_PARAMS, mode)
                "music-search", "audio-search" -> addParams(musicSearchParams, params, MUSIC_PARAMS, mode)
                "book-search" -> addParams(bookSearchParams, params, BOOK_PARAMS, mode)
                else -> throw IllegalArgumentException("Unsupported search mode: $mode")
            }
        }
    }

    private fun addParams(target: MutableList<String>, params: List<String>, allowed: List<String>, mode: String) {
        for (p in params) {
            val lower = p.lowercase()
            require(lower in allowed) { "Not supported $mode param: $p" }
            require(lower !in target) { "Duplicate $mode param: $p" }
            target += lower
        }
    }

    private fun supported(params: List<String>, order: List<String>): String =
        (listOf("q") + order.filter { it != "q" && it in params }).joinToString(",")

    fun toXml(serverTitle: String = "Phonarr"): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<caps>\n")
        sb.append("  <server title=\"").append(Xml.escape(serverTitle)).append("\" />\n")
        if (limitsDefault != null || limitsMax != null) {
            sb.append("  <limits")
            limitsDefault?.let { sb.append(" default=\"").append(it).append('"') }
            limitsMax?.let { sb.append(" max=\"").append(it).append('"') }
            sb.append(" />\n")
        }
        sb.append("  <searching>\n")
        fun mode(name: String, params: List<String>, order: List<String>) {
            sb.append("    <").append(name)
                .append(" available=\"").append(if (params.isNotEmpty()) "yes" else "no").append('"')
                .append(" supportedParams=\"").append(supported(params, order)).append('"')
            if (supportsRawSearch) sb.append(" searchEngine=\"raw\"")
            sb.append(" />\n")
        }
        mode("search", searchParams, listOf("q"))
        mode("tv-search", tvSearchParams, TV_PARAMS)
        mode("movie-search", movieSearchParams, MOVIE_PARAMS)
        mode("music-search", musicSearchParams, MUSIC_PARAMS)
        mode("audio-search", musicSearchParams, MUSIC_PARAMS)
        mode("book-search", bookSearchParams, BOOK_PARAMS)
        sb.append("  </searching>\n  <categories>\n")
        for (c in categories.getTorznabCategoryTree(sorted = true)) {
            sb.append("    <category id=\"").append(c.id).append("\" name=\"").append(Xml.escape(c.name)).append('"')
            if (c.subCategories.isEmpty()) {
                sb.append(" />\n")
            } else {
                sb.append(">\n")
                for (s in c.subCategories) {
                    sb.append("      <subcat id=\"").append(s.id).append("\" name=\"").append(Xml.escape(s.name)).append("\" />\n")
                }
                sb.append("    </category>\n")
            }
        }
        sb.append("  </categories>\n</caps>\n")
        return sb.toString()
    }

    companion object {
        val TV_PARAMS = listOf("q", "season", "ep", "imdbid", "tvdbid", "rid", "tvmazeid", "traktid", "tmdbid", "doubanid", "genre", "year")
        val MOVIE_PARAMS = listOf("q", "imdbid", "tmdbid", "imdbtitle", "imdbyear", "traktid", "genre", "doubanid", "year")
        val MUSIC_PARAMS = listOf("q", "album", "artist", "label", "year", "genre", "track")
        val BOOK_PARAMS = listOf("q", "title", "author", "publisher", "genre", "year")

        /** Merges capabilities for the "all indexers" endpoint (custom categories are dropped). */
        fun merge(list: List<IndexerCapabilities>): IndexerCapabilities {
            val result = IndexerCapabilities()
            result.searchParams += "q"
            for (c in list) {
                c.tvSearchParams.filter { it !in result.tvSearchParams }.forEach { result.tvSearchParams += it }
                c.movieSearchParams.filter { it !in result.movieSearchParams }.forEach { result.movieSearchParams += it }
                c.musicSearchParams.filter { it !in result.musicSearchParams }.forEach { result.musicSearchParams += it }
                c.bookSearchParams.filter { it !in result.bookSearchParams }.forEach { result.bookSearchParams += it }
                result.categories.concat(c.categories)
            }
            return result
        }
    }
}
