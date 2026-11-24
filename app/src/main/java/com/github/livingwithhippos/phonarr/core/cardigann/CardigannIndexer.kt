package com.github.livingwithhippos.phonarr.core.cardigann

import com.github.livingwithhippos.phonarr.core.torznab.IndexerCapabilities
import com.github.livingwithhippos.phonarr.core.torznab.IndexerCapabilitiesCategories
import com.github.livingwithhippos.phonarr.core.torznab.IndexerCategory
import com.github.livingwithhippos.phonarr.core.torznab.NewznabStandardCategory
import com.github.livingwithhippos.phonarr.core.torznab.ReleaseInfo
import com.github.livingwithhippos.phonarr.core.torznab.SearchQuery
import com.github.livingwithhippos.phonarr.core.torznab.SearchType
import com.github.livingwithhippos.phonarr.core.util.DateTimeUtil
import com.github.livingwithhippos.phonarr.core.util.Log
import com.github.livingwithhippos.phonarr.core.util.Magnet
import com.github.livingwithhippos.phonarr.core.util.ParseUtil
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.net.URI
import java.nio.charset.Charset
import java.time.LocalDate

class CardigannException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One request produced from a definition's search paths (Prowlarr's CardigannRequest). */
class CardigannRequest(val http: HttpReq, val variables: MutableMap<String, Any?>, val searchPath: SearchPathBlock)

sealed interface DownloadResult {
    data class MagnetLink(val uri: String) : DownloadResult
    class Torrent(val bytes: ByteArray, val fileName: String?) : DownloadResult
}

/**
 * A configured Cardigann indexer. Merges Prowlarr's CardigannBase, CardigannRequestGenerator,
 * CardigannParser and the Cardigann indexer class.
 *
 * @param settings user settings keyed by the definition's setting names (select settings store the option key).
 */
class CardigannIndexer(
    val definition: CardigannDefinition,
    private val settings: Map<String, String>,
    httpClient: HttpClient,
    private val baseUrl: String? = null,
    userAgent: String = IndexerHttp.DEFAULT_USER_AGENT,
    solver: ChallengeSolver? = null,
) {
    private val charset: Charset = runCatching { Charset.forName(definition.encoding) }.getOrDefault(Charsets.UTF_8)
    val http = IndexerHttp(httpClient, ((definition.requestDelay ?: 0.0) * 1000).toLong(), userAgent, solver)
    private val filters = Filters(definition.id, charset) { t, v -> Template.apply(t, v) }

    private val categories = IndexerCapabilitiesCategories()
    private val defaultCategories = mutableListOf<String>()
    val capabilities: IndexerCapabilities = IndexerCapabilities()

    private var loggedIn = false

    init {
        definition.caps.categories?.forEach { (id, name) ->
            val cat = NewznabStandardCategory.getCatByName(name)
            if (cat == null) Log.e("CardigannIndexer (${definition.id}): invalid Torznab category for id $id: $name")
            else categories.addCategoryMapping(id, cat)
        }
        definition.caps.categorymappings?.forEach { m ->
            var cat: IndexerCategory? = null
            if (m.cat != null) {
                cat = NewznabStandardCategory.getCatByName(m.cat)
                if (cat == null) {
                    Log.e("CardigannIndexer (${definition.id}): invalid Torznab category for id ${m.id}: ${m.cat}")
                    return@forEach
                }
            }
            categories.addCategoryMapping(m.id, cat, m.desc)
            if (m.default) defaultCategories += m.id
        }
        capabilities.parseCardigannSearchModes(definition.caps.modes)
        capabilities.supportsRawSearch = definition.caps.allowrawsearch
        capabilities.categories.also { caps ->
            definition.caps.categories?.forEach { (id, name) -> NewznabStandardCategory.getCatByName(name)?.let { caps.addCategoryMapping(id, it) } }
            definition.caps.categorymappings?.forEach { m ->
                val cat = m.cat?.let { NewznabStandardCategory.getCatByName(it) ?: return@forEach }
                caps.addCategoryMapping(m.id, cat, m.desc)
            }
        }
        settings["cookie"]?.takeIf { it.isNotBlank() }?.let { http.cookies.putAll(cookieHeaderToMap(it)) }
    }

    val siteLink: String
        get() {
            val configured = baseUrl?.takeIf { it.isNotBlank() } ?: return definition.links.first()
            return if (definition.legacylinks.contains(configured)) definition.links.first() else configured
        }

    // ------------------------------------------------------------------ variables

    fun baseVariables(): MutableMap<String, Any?> {
        val v = HashMap<String, Any?>()
        v[".Config.sitelink"] = siteLink
        v[".True"] = "True"
        v[".False"] = null
        v[".Today.Year"] = LocalDate.now().year.toString()
        for (s in definition.settings) {
            val name = ".Config." + s.name
            val value = settings[s.name]
            when (s.type) {
                "text", "password" -> v[name] = value ?: s.default
                "checkbox" -> {
                    val checked = (value ?: s.default)?.let { it.equals("true", true) } ?: false
                    v[name] = if (checked) ".True" else null
                }
                "select" -> {
                    val keys = s.options?.map { it.first }.orEmpty()
                    v[name] = value?.takeIf { it in keys } ?: s.default?.takeIf { it in keys } ?: keys.sorted().firstOrNull()
                }
                "multi-select" -> v[name] = value?.split(',')?.filter { it.isNotEmpty() } ?: s.defaults.orEmpty()
                else -> {} // info, info_cookie, info_flaresolverr, ...
            }
        }
        return v
    }

    private fun queryVariables(query: SearchQuery): MutableMap<String, Any?> {
        val v = baseVariables()
        v[".Query.Type"] = query.type.torznab
        v[".Query.Q"] = query.q
        v[".Query.Categories"] = query.categories
        v[".Query.Limit"] = query.limit?.toString()
        v[".Query.Offset"] = query.offset?.toString()
        listOf(
            "Extended", "APIKey", "Genre", "Movie", "Year", "IMDBID", "IMDBIDShort", "TMDBID", "Series", "Ep", "Season",
            "TVDBID", "TVRageID", "TVMazeID", "TraktID", "DoubanID", "Episode", "Album", "Artist", "Label", "Track",
            "Author", "Title", "Publisher",
        ).forEach { v[".Query.$it"] = null }

        val fullImdb = ParseUtil.getFullImdbId(query.imdbId)
        val shortImdb = fullImdb?.removePrefix("tt")
        when (query.type) {
            SearchType.Movie -> {
                v[".Query.Year"] = query.year?.toString()
                v[".Query.Genre"] = query.genre
                v[".Query.IMDBID"] = fullImdb
                v[".Query.IMDBIDShort"] = shortImdb
                v[".Query.TMDBID"] = query.tmdbId?.toString()
                v[".Query.TraktID"] = query.traktId?.toString()
                v[".Query.DoubanID"] = query.doubanId?.toString()
            }
            SearchType.Tv -> {
                v[".Query.Ep"] = query.ep
                v[".Query.Season"] = query.season?.toString()
                v[".Query.Genre"] = query.genre
                v[".Query.Year"] = query.year?.toString()
                v[".Query.IMDBID"] = fullImdb
                v[".Query.IMDBIDShort"] = shortImdb
                v[".Query.TVDBID"] = query.tvdbId?.toString()
                v[".Query.TMDBID"] = query.tmdbId?.toString()
                v[".Query.TVRageID"] = query.rId?.toString()
                v[".Query.TVMazeID"] = query.tvMazeId?.toString()
                v[".Query.TraktID"] = query.traktId?.toString()
                v[".Query.DoubanID"] = query.doubanId?.toString()
                v[".Query.Episode"] = query.episodeSearchString.ifEmpty { null }
            }
            SearchType.Music -> {
                v[".Query.Album"] = query.album
                v[".Query.Artist"] = query.artist
                v[".Query.Label"] = query.label
                v[".Query.Genre"] = query.genre
                v[".Query.Year"] = query.year?.toString()
                v[".Query.Track"] = query.track
            }
            SearchType.Book -> {
                v[".Query.Author"] = query.author
                v[".Query.Title"] = query.title
                v[".Query.Genre"] = query.genre
                v[".Query.Publisher"] = query.publisher
                v[".Query.Year"] = query.year?.toString()
            }
            SearchType.Search -> {}
        }
        return v
    }

    private fun tpl(t: String?, v: Map<String, Any?>, modifier: ((String?) -> String?)? = null) = Template.apply(t, v, modifier)

    private fun resolve(path: String, base: String = siteLink): String {
        val baseUri = URI(base)
        val cleaned = path.trim().replace(" ", "%20")
        return runCatching { baseUri.resolve(URI(cleaned)).toString() }
            .getOrElse { baseUri.resolve(URI(null, null, path.trim(), null)).toString() }
    }

    private fun customHeaders(h: Map<String, List<String>>?, v: Map<String, Any?>): Map<String, String> =
        h?.mapNotNull { (k, values) -> values.firstOrNull()?.let { k to (tpl(it, v) ?: "") } }?.toMap() ?: emptyMap()

    // ------------------------------------------------------------------ requests

    fun buildRequests(query: SearchQuery): List<CardigannRequest> {
        val variables = queryVariables(query)
        val search = definition.search

        var mapped = categories.mapTorznabCapsToTrackers(query.categories)
        if (mapped.isEmpty()) mapped = defaultCategories.toList()

        val tokens = listOf("Q", "Series", "Movie", "Year").mapNotNull { (variables[".Query.$it"] as? String)?.takeIf { s -> s.isNotBlank() } }.toMutableList()
        (variables[".Query.Episode"] as? String)?.takeIf { it.isNotBlank() }?.let { tokens += it }
        variables[".Query.Keywords"] = tokens.joinToString(" ")
        variables[".Keywords"] = filters.apply(variables[".Query.Keywords"] as String, search.keywordsfilters, variables)

        val urls = mutableListOf<String>()
        val result = mutableListOf<CardigannRequest>()
        for (path in search.paths) {
            variables[".Categories"] = mapped
            if (path.categories != null && mapped.isNotEmpty()) {
                var intersects = mapped.any { it in path.categories }
                if (path.categories.firstOrNull() == "!") intersects = !intersects
                if (!intersects) {
                    variables[".Categories"] = mapped.filter { it !in path.categories }
                    continue
                }
                variables[".Categories"] = mapped.filter { it in path.categories }
            }

            var url = resolve(tpl(path.path, variables) { Filters.urlEncode(it) }!!.replace("+", "%20"))
            val isPost = path.method.equals("post", true)
            val query = mutableListOf<Pair<String, String>>()
            val inputsList = buildList {
                if (path.inheritinputs) add(search.inputs)
                add(path.inputs)
            }
            for (inputs in inputsList) {
                inputs?.forEach { (key, value) ->
                    if (key == "\$raw") {
                        val raw = tpl(value, variables) { Filters.urlEncode(it) } ?: ""
                        raw.split('&').forEach { part ->
                            val kv = part.split("=", limit = 2)
                            if (kv[0].isNotEmpty()) query += kv[0] to (kv.getOrNull(1) ?: "")
                        }
                    } else {
                        val v = tpl(value, variables) ?: ""
                        if (v.isNotBlank() || search.allowEmptyInputs) query += key to v
                    }
                }
            }
            if (!isPost && query.isNotEmpty()) {
                url += (if (url.contains('?')) "&" else "?") + query.joinToString("&") { "${it.first}=${Filters.urlEncode(it.second, charset)}" }
            }
            if (!isPost && url in urls) continue
            urls += url

            val req = HttpReq(
                url = url,
                method = if (isPost) "POST" else "GET",
                form = if (isPost) query else emptyList(),
                headers = customHeaders(search.headers, variables),
                followRedirects = path.followredirect || definition.followredirect,
                charset = charset,
            )
            result += CardigannRequest(req, HashMap(variables), path)
        }
        return result
    }

    // ------------------------------------------------------------------ login

    /** Why this definition can't be used yet, or null if it is supported. */
    fun unsupportedReason(): String? = Companion.unsupportedReason(definition)

    private suspend fun ensureLogin(force: Boolean = false) {
        val login = definition.login ?: return
        if (loggedIn && !force) return
        val variables = baseVariables()
        val headers = customHeaders(login.headers ?: definition.search.headers, variables)
        when (login.method) {
            "cookie" -> {
                val cookie = settings["cookie"] ?: throw CardigannException("A cookie is required for this indexer")
                http.cookies.clear()
                http.cookies.putAll(cookieHeaderToMap(cookie))
            }
            "post", "get", "oneurl" -> {
                http.cookies.clear()
                login.cookies?.let { http.cookies.putAll(cookieHeaderToMap(it.joinToString("; "))) }
                val inputs = login.inputs.orEmpty().map { (k, v) -> k to (tpl(v, variables) ?: "") }
                val req = when (login.method) {
                    "post" -> HttpReq(resolve(tpl(login.path, variables) ?: ""), "POST", inputs, headers, true, charset, siteLink)
                    "get" -> {
                        val qs = inputs.joinToString("&") { "${it.first}=${Filters.urlEncode(it.second, charset)}" }
                        HttpReq(resolve((tpl(login.path, variables) ?: "") + "?" + qs), "GET", emptyList(), headers, false, charset, siteLink)
                    }
                    else -> {
                        val oneUrl = tpl(login.inputs?.firstOrNull { it.first == "oneurl" }?.second, variables) ?: ""
                        HttpReq(resolve((tpl(login.path, variables) ?: "") + oneUrl), "GET", emptyList(), headers, false, charset, siteLink)
                    }
                }
                val resp = http.execute(req)
                checkLoginError(resp, login.error)
            }
            "form" -> formLogin(login, variables, headers)
            else -> throw CardigannException("Login method '${login.method}' is not supported yet")
        }
        loggedIn = true
    }

    /** Port of Prowlarr's form login: load the login page, fill its form and submit it. */
    private suspend fun formLogin(login: LoginBlock, variables: Map<String, Any?>, headers: Map<String, String>) {
        http.cookies.clear()
        login.cookies?.let { http.cookies.putAll(cookieHeaderToMap(it.joinToString("; "))) }
        val loginUrl = resolve(tpl(login.path, variables) ?: "")
        val landing = http.execute(HttpReq(loginUrl, "GET", emptyList(), headers, definition.followredirect, charset, siteLink))
        val doc = Jsoup.parse(landing.content, landing.url)

        val formSelector = login.form ?: "form"
        val form = doc.selectFirst(formSelector)
            ?: throw CardigannException("Login failed: No form found on $loginUrl using form selector $formSelector")
        val inputs = form.select("input")
        if (inputs.isEmpty()) throw CardigannException("Login failed: No inputs found on $loginUrl using form selector $formSelector")

        // the form's own values first (hidden fields such as CSRF tokens), then the definition's
        val pairs = LinkedHashMap<String, String>()
        for (input in inputs) {
            val name = input.attr("name").takeIf { input.hasAttr("name") } ?: continue
            if (input.hasAttr("disabled")) continue
            val type = input.attr("type").lowercase()
            if ((type == "checkbox" || type == "radio") && !input.hasAttr("checked")) continue
            pairs[name] = input.attr("value")
        }
        for ((key, value) in login.inputs.orEmpty()) {
            val name = if (login.selectors) {
                doc.selectFirst(key)?.attr("name")
                    ?: throw CardigannException("Login failed: No input found using selector $key")
            } else key
            pairs[name] = tpl(value, variables) ?: ""
        }
        val root = doc.child(0)
        for ((key, sel) in login.selectorinputs.orEmpty()) {
            val value = try {
                handleSelector(sel, root, variables, required = !sel.optional)
            } catch (e: Exception) {
                throw CardigannException("Error while parsing selector input=$key, selector=${sel.selector}: ${e.message}", e)
            }
            if (value != null || !sel.optional) pairs[key] = value ?: ""
        }
        val query = LinkedHashMap<String, String>()
        for ((key, sel) in login.getselectorinputs.orEmpty()) {
            val value = try {
                handleSelector(sel, root, variables, required = !sel.optional)
            } catch (e: Exception) {
                throw CardigannException("Error while parsing get selector input=$key, selector=${sel.selector}: ${e.message}", e)
            }
            if (value != null || !sel.optional) query[key] = value ?: ""
        }

        var submit = login.submitpath ?: form.attr("action")
        if (query.isNotEmpty()) {
            submit += "?" + query.entries.joinToString("&") { "${Filters.urlEncode(it.key, charset)}=${Filters.urlEncode(it.value, charset)}" }
        }
        val req = HttpReq(
            resolve(submit, loginUrl), "POST", pairs.toList(), headers, true, charset, loginUrl,
            multipart = form.attr("enctype").equals("multipart/form-data", ignoreCase = true),
        )
        checkLoginError(http.execute(req), login.error)
    }

    private fun checkLoginError(resp: HttpResp, errors: List<ErrorBlock>?) {
        if (resp.status == 401) throw CardigannException("401 Unauthorized: check your credentials")
        if (errors.isNullOrEmpty()) return
        val doc = Jsoup.parse(resp.content, resp.url)
        for (e in errors) {
            val sel = e.selector ?: continue
            val match = doc.selectFirst(sel) ?: continue
            val message = e.message?.let { runCatching { handleSelector(it, match, baseVariables(), false) }.getOrNull() } ?: textContent(match).trim()
            throw CardigannException("Login failed: $message")
        }
    }

    private fun isLoginNeeded(resp: HttpResp): Boolean {
        val login = definition.login ?: return false
        if (login.method == "cookie") return false
        if (resp.isRedirect && resp.location?.contains("login", true) == true) return true
        val test = login.test ?: return false
        val selector = test.selector ?: return false
        if (resp.content.isBlank()) return false
        return Jsoup.parse(resp.content).selectFirst(selector) == null
    }

    // ------------------------------------------------------------------ search

    suspend fun search(query: SearchQuery): List<ReleaseInfo> {
        unsupportedReason()?.let { throw CardigannException(it) }
        ensureLogin()
        val releases = mutableListOf<ReleaseInfo>()
        for (request in buildRequests(query)) {
            Log.d("${definition.id}: ${request.http.method} ${request.http.url}")
            var resp = http.execute(request.http)
            if (definition.login != null && isLoginNeeded(resp)) {
                ensureLogin(force = true)
                resp = http.execute(request.http)
            }
            releases += parse(resp, request)
        }
        return cleanup(releases, query)
    }

    private fun cleanup(releases: List<ReleaseInfo>, query: SearchQuery): List<ReleaseInfo> {
        var result = releases.filter { !it.title.isNullOrBlank() }
            .distinctBy { it.guid ?: it.downloadUrl ?: it.magnetUrl ?: it.title }
        if (definition.search.rows.filters?.any { it.name == "andmatch" } == true) {
            result = filterByQuery(result, query)
        }
        return result
    }

    /** Port of IndexerBase.FilterReleasesByQuery. */
    private fun filterByQuery(releases: List<ReleaseInfo>, query: SearchQuery): List<ReleaseInfo> {
        if (query.isRssSearch || query.isIdSearch) return releases
        val common = setOf("and", "the", "an", "of")
        val terms = (query.q ?: "").split(Regex("[^\\w]+")).filter { it.length > 1 && it.lowercase() !in common }
        return releases.filter { r ->
            val matches = terms.count { t ->
                r.title?.contains(t, true) == true || r.description?.contains(t, true) == true
            }
            if (terms.size > 1) matches > 1 else matches > 0 || terms.isEmpty()
        }
    }

    // ------------------------------------------------------------------ parsing

    fun parse(resp: HttpResp, request: CardigannRequest): List<ReleaseInfo> {
        if (resp.isRedirect) {
            if (resp.redirectUrl?.contains("/login.php", true) == true) {
                http.cookies.clear()
                loggedIn = false
                throw CardigannException("Redirected to the login page: the session expired or the credentials are wrong")
            }
            throw CardigannException("Redirected to ${resp.redirectUrl} from indexer request")
        }
        if (resp.status != 200) throw CardigannException("Unexpected response status ${resp.status} from indexer request")

        val results = resp.content
        val variables = request.variables
        val search = definition.search
        val searchUrl = request.http.url
        val releases = mutableListOf<ReleaseInfo>()

        if (request.searchPath.response?.type == "json") {
            val noResults = request.searchPath.response.noResultsMessage
            if (noResults != null && ((noResults.isNotBlank() && results.contains(noResults)) || (noResults.isBlank() && results.isBlank()))) {
                return releases
            }
            val json = try {
                Json.parseToJsonElement(results)
            } catch (e: Exception) {
                throw CardigannException("Error parsing JSON response", e)
            }
            search.rows.count?.let { countSel ->
                runCatching { handleJsonSelector(countSel, json, variables) }.getOrNull()
                    ?.toIntOrNull()?.let { if (it < 1) return releases }
            }
            val rows = jsonRows(json, search.rows.selector ?: "")
                ?: if (search.rows.missingAttributeEqualsNoResults) return releases else throw CardigannException("Error parsing rows selector")
            for (row in rows) {
                var sel: JsonElement? = row
                if (search.rows.attribute != null) {
                    sel = JsonPath.select(row, search.rows.attribute)
                    if (sel == null && search.rows.missingAttributeEqualsNoResults) continue
                }
                val multiRows: List<JsonElement> = if (search.rows.multiple) {
                    when (sel) {
                        is JsonArray -> sel.filterIsInstance<JsonObject>()
                        is JsonObject -> sel.values.filterIsInstance<JsonObject>()
                        else -> emptyList()
                    }
                } else listOfNotNull(sel)
                for (mulRow in multiRows) {
                    val release = ReleaseInfo()
                    for ((key, field) in search.fields) {
                        val parts = key.split('|')
                        val name = parts[0]
                        val modifiers = parts.drop(1)
                        val varKey = ".Result.$name"
                        val optional = key in OPTIONAL_FIELDS || "optional" in modifiers || field.optional
                        var value: String? = null
                        try {
                            val parent = if (field.selector?.startsWith("..") == true) row else mulRow
                            value = handleJsonSelector(field, parent, variables, !optional)
                            if (optional && value.isNullOrBlank()) {
                                val def = tpl(field.default, variables)
                                if (def.isNullOrBlank()) {
                                    variables[varKey] = null
                                    continue
                                }
                                value = def
                            }
                            variables[varKey] = parseField(value, name, release, modifiers, searchUrl)
                        } catch (e: Exception) {
                            if (!variables.containsKey(varKey) || optional) variables[varKey] = null
                            if (optional) continue
                            throw CardigannException("Error while parsing field=$key, selector=${field.selector}, value=${value ?: "<null>"}: ${e.message}", e)
                        }
                    }
                    releases += release
                }
            }
        } else {
            val isXml = request.searchPath.response?.type == "xml"
            var content = results
            if (search.preprocessingfilters != null) content = filters.apply(content, search.preprocessingfilters, variables) ?: ""
            val doc: Document = if (isXml) Jsoup.parse(content, searchUrl, Parser.xmlParser()) else Jsoup.parse(content, searchUrl)
            val rowsSelector = tpl(search.rows.selector, variables) ?: throw CardigannException("Missing rows selector")
            val rows = doc.select(rowsSelector).toMutableList()

            val after = search.rows.after
            if (after > 0) {
                var i = 0
                while (i < rows.size) {
                    val current = rows[i]
                    for (j in 0 until after) {
                        val merge = rows.getOrNull(i + j + 1) ?: break
                        merge.childNodes().toList().forEach { current.appendChild(it) }
                    }
                    repeat(after) { if (i + 1 < rows.size) rows.removeAt(i + 1) }
                    i++
                }
            }

            for (row in rows) {
                try {
                    val release = ReleaseInfo()
                    for ((key, field) in search.fields) {
                        val parts = key.split('|')
                        val name = parts[0]
                        val modifiers = parts.drop(1)
                        val varKey = ".Result.$name"
                        val optional = key in OPTIONAL_FIELDS || "optional" in modifiers || field.optional
                        var value: String? = null
                        try {
                            value = handleSelector(field, row, variables, !optional)
                            if (optional && value.isNullOrBlank()) {
                                val def = tpl(field.default, variables)
                                if (def.isNullOrBlank()) {
                                    variables[varKey] = null
                                    continue
                                }
                                value = def
                            }
                            variables[varKey] = parseField(value, name, release, modifiers, searchUrl)
                        } catch (e: Exception) {
                            if (!variables.containsKey(varKey) || optional) variables[varKey] = null
                            if (optional) continue
                            Log.d("${definition.id}: error parsing field=$key selector=${field.selector} value=${value ?: "<null>"}: ${e.message}")
                        }
                    }

                    val dateHeaders = search.rows.dateheaders
                    if (release.publishDate == null && dateHeaders != null) {
                        var value: String? = null
                        var prev: Element? = row.previousElementSibling() ?: row.parent()?.previousElementSibling()
                        while (prev != null) {
                            val cur: Element = prev
                            value = runCatching { handleSelector(dateHeaders, cur, baseVariables()) }.getOrNull()
                            if (value != null) break
                            prev = cur.previousElementSibling() ?: cur.parent()?.previousElementSibling()
                        }
                        if (value == null && !dateHeaders.optional) throw CardigannException("No date header row found for $release")
                        if (value != null) release.publishDate = DateTimeUtil.fromUnknown(value)
                    }
                    releases += release
                } catch (e: Exception) {
                    Log.e("CardigannIndexer (${definition.id}): Error while parsing row: ${e.message}", e)
                }
            }
        }

        for (r in releases) {
            if (r.magnetUrl == null && !r.infoHash.isNullOrBlank() && definition.type != "private") {
                r.magnetUrl = Magnet.buildPublicMagnetLink(r.infoHash!!, r.title)
            }
            if (r.magnetUrl != null && r.infoHash.isNullOrBlank()) r.infoHash = Magnet.getInfoHashFromMagnet(r.magnetUrl!!)
            if (r.description?.startsWith("Internal") == true) r.flags += "internal"
            if (r.guid == null) r.guid = r.downloadUrl ?: r.magnetUrl ?: r.infoUrl
        }
        return releases
    }

    private fun parseField(input: String?, name: String, r: ReleaseInfo, modifiers: List<String>, searchUrl: String): String? {
        var value = input
        when (name) {
            "download" -> {
                if (value.isNullOrEmpty()) {
                    r.downloadUrl = null
                    value = null
                } else if (value.startsWith("magnet:")) {
                    r.magnetUrl = value
                } else {
                    r.downloadUrl = resolve(value, searchUrl)
                    value = r.downloadUrl
                }
                r.guid = value
            }
            "magnet" -> r.magnetUrl = value
            "infohash" -> r.infoHash = value
            "details" -> {
                r.infoUrl = resolve(value!!, searchUrl)
                value = r.infoUrl
            }
            "comments" -> {
                val url = resolve(value!!, searchUrl)
                if (r.commentUrl == null) r.commentUrl = url
                value = url
            }
            "title" -> {
                r.title = if ("append" in modifiers) (r.title ?: "") + value else value
                value = r.title
            }
            "description" -> {
                r.description = if ("append" in modifiers) (r.description ?: "") + value else value
                value = r.description
            }
            "category", "categorydesc" -> {
                val cats = if (name == "category") categories.mapTrackerCatToNewznab(value) else categories.mapTrackerCatDescToNewznab(value)
                if (cats.isNotEmpty()) {
                    if ("noappend" in modifiers) r.categories = cats.toMutableList()
                    else cats.forEach { if (it !in r.categories) r.categories += it }
                }
                if (!value.isNullOrBlank() && r.categories.isEmpty()) {
                    Log.w("[${definition.id}] Invalid category for value: '$value'")
                } else {
                    value = r.categories.joinToString(",") { it.id.toString() }
                }
            }
            "size" -> {
                r.size = ParseUtil.getBytes(value!!)
                value = r.size.toString()
            }
            "leechers" -> {
                var l = ParseUtil.coerceLong(value)
                if (l >= 5_000_000L) l = 0
                r.peers = (r.peers ?: 0) + l.toInt()
                value = l.toString()
            }
            "seeders" -> {
                var s = ParseUtil.coerceInt(value)
                if (s >= 5_000_000) s = 0
                r.seeders = s
                r.peers = (r.peers ?: 0) + s
                value = s.toString()
            }
            "date" -> {
                r.publishDate = DateTimeUtil.fromUnknown(value!!)
                value = DateTimeUtil.format(r.publishDate!!)
            }
            "files" -> { r.files = ParseUtil.coerceInt(value); value = r.files.toString() }
            "grabs" -> { r.grabs = ParseUtil.coerceInt(value); value = r.grabs.toString() }
            "downloadvolumefactor" -> { r.downloadVolumeFactor = ParseUtil.coerceDouble(value); value = r.downloadVolumeFactor.toString() }
            "uploadvolumefactor" -> { r.uploadVolumeFactor = ParseUtil.coerceDouble(value); value = r.uploadVolumeFactor.toString() }
            "minimumratio" -> { r.minimumRatio = ParseUtil.coerceDouble(value); value = r.minimumRatio.toString() }
            "minimumseedtime" -> { r.minimumSeedTime = ParseUtil.coerceLong(value); value = r.minimumSeedTime.toString() }
            "imdb", "imdbid" -> { r.imdbId = idFrom(value); value = r.imdbId.toString() }
            "tmdbid" -> { r.tmdbId = idFrom(value); value = r.tmdbId.toString() }
            "rageid" -> { r.tvRageId = idFrom(value); value = r.tvRageId.toString() }
            "tvdbid" -> { r.tvdbId = idFrom(value); value = r.tvdbId.toString() }
            "tvmazeid" -> { r.tvMazeId = idFrom(value); value = r.tvMazeId.toString() }
            "traktid" -> { r.traktId = idFrom(value); value = r.traktId.toString() }
            "doubanid" -> { r.doubanId = idFrom(value); value = r.doubanId.toString() }
            "poster" -> {
                if (!value.isNullOrBlank()) r.posterUrl = resolve(value, searchUrl)
                value = r.posterUrl
            }
            "genre" -> {
                val genres = (r.genres ?: mutableListOf())
                value!!.split(Regex("[, /)(.;\\[\\]\"|:]")).map { it.trim() }.filter { it.isNotEmpty() }
                    .map { it.replace("_", " ") }.forEach { if (it !in genres) genres += it }
                r.genres = genres
                value = genres.joinToString(", ")
            }
            "year" -> { r.year = ParseUtil.coerceInt(value); value = r.year.toString() }
            "author" -> r.author = value
            "booktitle" -> r.bookTitle = value
            "publisher" -> r.publisher = value
            "artist" -> r.artist = value
            "album" -> r.album = value
            "label" -> r.label = value
            "track" -> r.track = value
        }
        return value
    }

    private fun idFrom(value: String?): Int = (ParseUtil.getLongFromString(value) ?: 0L).toInt()

    // ------------------------------------------------------------------ selectors

    private fun querySelector(element: Element, selector: String): Element? {
        if (selector.startsWith(":root")) {
            var root = element
            while (root.parent() != null) root = root.parent()!!
            val rest = selector.substring(5).trim()
            return if (rest.isEmpty()) root else root.selectFirst(rest)
        }
        return element.selectFirst(selector)
    }

    private fun matches(element: Element, selector: String): Boolean = runCatching { element.`is`(selector) }.getOrDefault(false)

    fun handleSelector(selector: SelectorBlock, dom: Element, variables: Map<String, Any?>, required: Boolean = true): String? {
        if (selector.text != null) return filters.apply(tpl(selector.text, variables), selector.filters, variables)

        var selection: Element = dom
        if (selector.selector != null) {
            val sel = tpl(selector.selector, variables)!!
            selection = if (matches(dom, sel)) dom else querySelector(dom, sel) ?: run {
                if (required) throw CardigannException("Selector \"$sel\" didn't match ${dom.outerHtml().take(500)}")
                return null
            }
        }
        selector.remove?.let { selection.select(it).remove() }

        val value: String
        if (selector.case != null) {
            var found: String? = null
            for ((k, v) in selector.case) {
                if (matches(selection, k) || querySelector(selection, k) != null) {
                    found = tpl(v, variables)
                    break
                }
            }
            value = found ?: run {
                if (required) throw CardigannException("None of the case selectors matched ${selection.outerHtml().take(500)}")
                return null
            }
        } else if (selector.attribute != null) {
            if (!selection.hasAttr(selector.attribute)) {
                if (required) throw CardigannException("Attribute \"${selector.attribute}\" is not set for element ${selection.outerHtml().take(500)}")
                return null
            }
            value = selection.attr(selector.attribute)
        } else {
            value = textContent(selection)
        }
        return filters.apply(value.trim(), selector.filters, variables)
    }

    fun handleJsonSelector(selector: SelectorBlock, parent: JsonElement, variables: Map<String, Any?>, required: Boolean = true): String? {
        if (selector.text != null) return filters.apply(tpl(selector.text, variables), selector.filters, variables)

        var value: String? = null
        if (selector.selector != null) {
            val sel = tpl(selector.selector.trimStart('.'), variables)!!
            val path = jsonFieldSelector(parent, sel)
            val selection = path?.let { JsonPath.select(parent, it) }
            if (selection == null) {
                if (required) throw CardigannException("Selector \"$sel\" didn't match JSON content")
                return null
            }
            value = if (selection is JsonArray) selection.joinToString(",") { JsonPath.asString(it) ?: "" } else JsonPath.asString(selection)
        }
        if (selector.case != null) {
            var matched = false
            for ((k, v) in selector.case) {
                if ((value != null && value == k) || k == "*") {
                    value = tpl(v, variables)
                    matched = true
                    break
                }
            }
            if (!matched && value == null) {
                if (required) throw CardigannException("None of the case selectors matched JSON content")
                return null
            }
        }
        return filters.apply(value?.trim(), selector.filters, variables)
    }

    private fun jsonRows(json: JsonElement, rowSelector: String): List<JsonElement>? {
        val sel = tpl(rowSelector, baseVariables()) ?: ""
        val path = sel.split(':')[0]
        return try {
            val rows = JsonPath.select(json, path) as? JsonArray ?: return null
            val rest = sel.substring(path.length)
            rows.filter { it is JsonObject && jsonFieldSelector(it, rest) != null }
        } catch (e: Exception) {
            Log.d("Failed to parse JSON rows for selector \"$sel\": ${e.message}")
            null
        }
    }

    private fun jsonFieldSelector(json: JsonElement, rowSelector: String): String? {
        val selector = rowSelector.split(':')[0]
        val obj: JsonElement = if (selector.isBlank()) json else JsonPath.select(json, selector) ?: return null
        for (m in JSON_SELECTOR_REGEX.findAll(rowSelector)) {
            val filter = m.groups["filter"]!!.value
            val key = m.groups["key"]!!.value
            when (filter) {
                "has" -> if (if (JSON_SELECTOR_REGEX.containsMatchIn(key)) jsonFieldSelector(obj, key) == null else JsonPath.select(obj, key) == null) return null
                "not" -> if (if (JSON_SELECTOR_REGEX.containsMatchIn(key)) jsonFieldSelector(obj, key) != null else JsonPath.select(obj, key) != null) return null
                "contains" -> if (!obj.toString().contains(key)) return null
                else -> Log.e("CardigannIndexer (${definition.id}): Unsupported selector: $rowSelector")
            }
        }
        return selector
    }

    // ------------------------------------------------------------------ download

    suspend fun download(link: String): DownloadResult {
        ensureLogin()
        val variables = baseVariables()
        addUriVariables(variables, link, ".DownloadUri")
        val download = definition.download
        val headers = customHeaders(download?.headers ?: definition.search.headers, variables)
        var target = link
        val method = if (download?.method == "post") "POST" else "GET"

        if (download != null) {
            val pageReq = HttpReq(link, "GET", headers = headers, followRedirects = true, charset = charset)
            var response: HttpResp? = null
            download.before?.let { before ->
                var beforePath = before.path
                if (before.pathselector != null) {
                    response = http.execute(pageReq)
                    beforePath = matchSelector(response!!, before.pathselector, variables)
                }
                val inputs = before.inputs.orEmpty().map { (k, v) -> k to (tpl(v, variables) ?: "") }
                val url = resolve(tpl(beforePath, variables) ?: "", link)
                response = if (before.method.equals("post", true)) {
                    http.execute(HttpReq(url, "POST", inputs, headers, true, charset, link))
                } else {
                    val qs = inputs.joinToString(before.queryseparator) { "${it.first}=${Filters.urlEncode(it.second, charset)}" }
                    http.execute(HttpReq(if (qs.isEmpty()) url else "$url?$qs", "GET", emptyList(), headers, true, charset, link))
                }
            }

            val infohash = download.infohash
            if (infohash?.hash != null && infohash.title != null) {
                if (!infohash.usebeforeresponse || download.before == null || response == null) response = http.execute(pageReq)
                val hash = matchSelector(response!!, infohash.hash, variables) ?: throw CardigannException("InfoHash selectors didn't match hash.")
                val title = matchSelector(response!!, infohash.title, variables) ?: throw CardigannException("InfoHash selectors didn't match title.")
                return DownloadResult.MagnetLink(Magnet.buildPublicMagnetLink(hash, title))
            } else if (!download.selectors.isNullOrEmpty()) {
                var found: String? = null
                for (selector in download.selectors) {
                    try {
                        if (!selector.usebeforeresponse || download.before == null || response == null) response = http.execute(pageReq)
                        val href = matchSelector(response!!, selector, variables) ?: continue
                        val resolved = if (href.startsWith("magnet:")) href else resolve(href, link)
                        if (!resolved.startsWith("magnet:") && definition.testLinkTorrent) {
                            val test = http.execute(HttpReq(resolved, "GET", emptyList(), headers, true, charset, link, allowBrowser = false))
                            val redirect = test.redirectUrl
                            if (test.isRedirect && redirect?.startsWith("magnet:") == true) return DownloadResult.MagnetLink(redirect)
                            if (test.body.isNotEmpty() && test.body[0] != 'd'.code.toByte()) {
                                Log.d("${definition.id}: download selector ${selector.selector} returned an invalid torrent, trying next")
                                continue
                            }
                        }
                        found = resolved
                        break
                    } catch (e: Exception) {
                        Log.e("${definition.id}: error with download selector ${selector.selector}", e)
                    }
                }
                target = found ?: throw CardigannException("Download selectors didn't match for $link")
            }
        }

        if (target.startsWith("magnet:")) return DownloadResult.MagnetLink(target)
        val resp = http.execute(HttpReq(target, method, emptyList(), headers, true, charset, siteLink, allowBrowser = false))
        resp.redirectUrl?.let { if (resp.isRedirect && it.startsWith("magnet:")) return DownloadResult.MagnetLink(it) }
        if (resp.status !in 200..299) throw CardigannException("Download failed with status ${resp.status}")
        if (resp.body.isEmpty() || resp.body[0] != 'd'.code.toByte()) {
            throw CardigannException("The downloaded file is not a torrent (maybe the site requires a login or blocked the request)")
        }
        val fileName = resp.headers.entries.firstOrNull { it.key.equals("Content-Disposition", true) }?.value?.firstOrNull()
            ?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").find(it)?.groupValues?.get(1) }
        return DownloadResult.Torrent(resp.body, fileName)
    }

    private fun matchSelector(resp: HttpResp, selector: SelectorField, variables: Map<String, Any?>): String? {
        val sel = tpl(selector.selector, variables) ?: return null
        val doc = Jsoup.parse(resp.content, resp.url)
        val element = doc.selectFirst(sel) ?: run {
            Log.d("CardigannIndexer (${definition.id}): Selector $sel could not match any elements.")
            return null
        }
        val value = if (selector.attribute != null) {
            if (!element.hasAttr(selector.attribute)) throw CardigannException("Attribute \"${selector.attribute}\" is not set")
            element.attr(selector.attribute)
        } else textContent(element)
        return filters.apply(value, selector.filters, variables)
    }

    private fun addUriVariables(v: MutableMap<String, Any?>, link: String, prefix: String) {
        val uri = runCatching { URI(link) }.getOrNull() ?: return
        v["$prefix.AbsoluteUri"] = uri.toString()
        v["$prefix.AbsolutePath"] = uri.rawPath
        v["$prefix.Scheme"] = uri.scheme
        v["$prefix.Host"] = uri.host
        v["$prefix.Port"] = (if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80).toString()
        v["$prefix.PathAndQuery"] = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        v["$prefix.Query"] = uri.rawQuery?.let { "?$it" } ?: ""
        ParseUtil.parseQuery(uri.rawQuery ?: "").forEach { (k, values) -> v["$prefix.Query.$k"] = values.firstOrNull() }
    }

    companion object {
        val OPTIONAL_FIELDS = setOf(
            "imdb", "imdbid", "tmdbid", "rageid", "tvdbid", "tvmazeid", "traktid", "doubanid", "poster", "banner", "description", "genre",
        )
        private val JSON_SELECTOR_REGEX = Regex(":(?<filter>.+?)\\((?<key>.+?)\\)(?=:|\\z)")

        fun unsupportedReason(definition: CardigannDefinition): String? {
            val login = definition.login ?: return null
            if (login.hasCaptcha) return "Sites with a login captcha are not supported yet"
            return when (login.method) {
                "cookie", "post", "get", "oneurl", "form" -> null
                else -> "Login method '${login.method}' is not supported yet"
            }
        }

        fun cookieHeaderToMap(header: String): Map<String, String> =
            header.split(';').mapNotNull { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) kv[0].trim() to kv[1].trim() else null
            }.toMap()

        /** AngleSharp's TextContent: every descendant text node, including script/style data, unnormalized. */
        fun textContent(element: Element): String {
            val sb = StringBuilder()
            NodeTraversor.traverse(object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when (node) {
                        is TextNode -> sb.append(node.wholeText)
                        is DataNode -> sb.append(node.wholeData)
                    }
                }
            }, element)
            return sb.toString()
        }
    }
}
