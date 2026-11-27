package com.github.livingwithhippos.phonarr.core.server

import com.github.livingwithhippos.phonarr.core.cardigann.DownloadResult
import com.github.livingwithhippos.phonarr.core.data.IndexerNotFoundException
import com.github.livingwithhippos.phonarr.core.data.SearchService
import com.github.livingwithhippos.phonarr.core.torznab.ReleaseInfo
import com.github.livingwithhippos.phonarr.core.torznab.SearchQuery
import com.github.livingwithhippos.phonarr.core.torznab.TorznabXml
import com.github.livingwithhippos.phonarr.core.util.Log
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.userAgent
import io.ktor.server.request.port
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Torznab API, served on localhost only.
 *
 * Routes (same shapes as Prowlarr and Jackett so clients can use either style):
 *  - /{id}/api?t=caps|search|tvsearch|movie|music|book&apikey=...
 *  - /api?t=... (same as /all/api, for clients given only the server address)
 *  - /api/v1/indexer/{id}/newznab?t=...
 *  - /api/v2.0/indexers/{id}/results/torznab/api?t=...
 *  - /{id}/download?apikey=...&link=...
 * `{id}` is an indexer id or `all`.
 *
 * Plus two endpoints of Prowlarr's REST API (JSON), for apps that talk to Prowlarr directly:
 *  - /api/v1/system/status
 *  - /api/v1/search?query=...&type=search&indexerIds=1&categories=2000&limit=&offset=
 */
class TorznabServer(
    private val search: SearchService,
    private val info: ServerInfo = ServerInfo(),
    /** log every request (debug builds only: queries can name what the user searches for) */
    private val logRequests: Boolean = false,
    private val apiKey: () -> String,
) {
    private var server: EmbeddedServer<*, *>? = null
    private var startTime: Instant = Instant.now()

    val isRunning: Boolean get() = server != null

    @Synchronized
    fun start(port: Int, host: String = "127.0.0.1") {
        if (server != null) return
        startTime = Instant.now()
        server = embeddedServer(CIO, port = port, host = host) { module() }.also { it.start(wait = false) }
        Log.i("Torznab server listening on $host:$port")
    }

    @Synchronized
    fun stop() {
        server?.stop(500, 2000)
        server = null
    }

    fun Application.module() {
        if (logRequests) {
            intercept(ApplicationCallPipeline.Monitoring) {
                val started = System.nanoTime()
                try {
                    proceed()
                } finally {
                    val req = context.request
                    val query = req.queryString().let { if (it.isEmpty()) "" else "?" + redactQuery(it) }
                    val ms = (System.nanoTime() - started) / 1_000_000
                    val status = context.response.status()?.value ?: "-"
                    Log.d("Server ${req.httpMethod.value} ${req.path()}$query -> $status in ${ms}ms (${req.userAgent() ?: "no user agent"})")
                }
            }
        }
        routing {
            get("/") {
                call.respondText("Phonarr is running. Use /api, /all/api or /{indexerId}/api with t=caps.", ContentType.Text.Plain)
            }
            // plain Torznab layout: clients given just the server address append /api
            get("/api") { handleApi("all") }
            get("/{id}/api") { handleApi(call.parameters["id"]!!) }
            get("/api/v1/indexer/{id}/newznab") { handleApi(call.parameters["id"]!!) }
            get("/api/v1/indexer/{id}/newznab/api") { handleApi(call.parameters["id"]!!) }
            get("/api/v2.0/indexers/{id}/results/torznab") { handleApi(call.parameters["id"]!!) }
            get("/api/v2.0/indexers/{id}/results/torznab/api") { handleApi(call.parameters["id"]!!) }
            get("/{id}/download") { handleDownload(call.parameters["id"]!!) }
            get("/api/v1/system/status") {
                if (!checkKey(call)) return@get respondUnauthorizedJson()
                respondJson(ProwlarrApi.SystemStatus.serializer(), ProwlarrApi.status(info, startTime))
            }
            get("/api/v1/search") { handleProwlarrSearch() }
        }
    }

    private fun checkKey(call: ApplicationCall): Boolean {
        val key = call.request.queryParameters["apikey"] ?: call.request.headers["X-Api-Key"]
        return key != null && key == apiKey()
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleApi(id: String) {
        if (!checkKey(call)) {
            call.respondText(TorznabXml.error(100, "Incorrect user credentials"), ContentType.Application.Xml, HttpStatusCode.Unauthorized)
            return
        }
        val params = call.request.queryParameters
        val t = params["t"]?.lowercase()
        try {
            when (t) {
                "caps" -> call.respondText(search.capabilities(id).toXml(), ContentType.Application.Xml)
                // Jackett and Prowlarr treat a missing t as a plain search (latest releases)
                null, "search", "tvsearch", "movie", "music", "audio", "book" -> {
                    val query = SearchQuery.fromParams { params[it] }
                    val outcome = search.search(id, query)
                    if (outcome.releases.isEmpty() && outcome.errors.isNotEmpty() && !id.equals("all", true)) {
                        call.respondText(TorznabXml.error(900, outcome.errors.values.first()), ContentType.Application.Xml, HttpStatusCode.InternalServerError)
                        return
                    }
                    val base = "http://${call.request.host()}:${call.request.port()}"
                    val xml = TorznabXml.results(outcome.releases) { r -> linkFor(base, r) }
                    call.respondText(xml, ContentType.Application.Xml)
                }
                else -> call.respondText(TorznabXml.error(202, "No such function ($t)"), ContentType.Application.Xml, HttpStatusCode.BadRequest)
            }
        } catch (e: IndexerNotFoundException) {
            call.respondText(TorznabXml.error(201, e.message ?: "Indexer not found"), ContentType.Application.Xml, HttpStatusCode.NotFound)
        } catch (e: Exception) {
            Log.e("API request failed", e)
            call.respondText(TorznabXml.error(900, e.message ?: e.toString()), ContentType.Application.Xml, HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleProwlarrSearch() {
        if (!checkKey(call)) return respondUnauthorizedJson()
        val params = call.request.queryParameters
        // ASP.NET binds lists from repeated parameters; accept comma-separated values too
        fun ints(name: String) = params.getAll(name).orEmpty().flatMap { it.split(',') }.mapNotNull { it.trim().toIntOrNull() }
        val query = SearchQuery.fromProwlarr(
            query = params["query"],
            type = params["type"],
            categories = ints("categories"),
            limit = params["limit"]?.toIntOrNull(),
            offset = params["offset"]?.toIntOrNull(),
        )
        try {
            val outcome = search.search(ints("indexerIds"), query)
            if (outcome.releases.isEmpty() && outcome.allFailed) {
                return respondJson(ProwlarrApi.Message.serializer(), ProwlarrApi.Message(outcome.errors.values.first()), HttpStatusCode.BadRequest)
            }
            val base = "http://${call.request.host()}:${call.request.port()}"
            val now = Instant.now()
            val releases = outcome.releases.map { r -> ProwlarrApi.release(r, r.downloadUrl?.let { proxyLink(base, r, it) }, now) }
            respondJson(ListSerializer(ProwlarrApi.Release.serializer()), releases)
        } catch (e: IndexerNotFoundException) {
            respondJson(ProwlarrApi.Message.serializer(), ProwlarrApi.Message(e.message ?: "Indexer not found"), HttpStatusCode.BadRequest)
        } catch (e: Exception) {
            Log.e("Prowlarr API search failed", e)
            respondJson(ProwlarrApi.Message.serializer(), ProwlarrApi.Message(e.message ?: e.toString()), HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun <T> io.ktor.server.routing.RoutingContext.respondJson(
        serializer: KSerializer<T>,
        value: T,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = call.respondText(ProwlarrApi.json.encodeToString(serializer, value), ContentType.Application.Json, status)

    private suspend fun io.ktor.server.routing.RoutingContext.respondUnauthorizedJson() =
        respondJson(ProwlarrApi.Message.serializer(), ProwlarrApi.Message("Unauthorized"), HttpStatusCode.Unauthorized)

    /** Magnets are returned as-is; http links go through the download proxy so cookies and download rules apply. */
    private fun linkFor(base: String, r: ReleaseInfo): String? {
        val download = r.downloadUrl ?: return r.magnetUrl
        return proxyLink(base, r, download)
    }

    /** A link to this server's download proxy for [download], so the indexer's cookies and rules apply. */
    private fun proxyLink(base: String, r: ReleaseInfo, download: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(download.toByteArray(StandardCharsets.UTF_8))
        val file = URLEncoder.encode(r.title ?: "release", StandardCharsets.UTF_8)
        return "$base/${r.indexerId}/download?apikey=${apiKey()}&link=$encoded&file=$file"
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleDownload(id: String) {
        if (!checkKey(call)) {
            call.respondText("Invalid API key", status = HttpStatusCode.Unauthorized)
            return
        }
        val indexerId = id.toIntOrNull()
        val linkParam = call.request.queryParameters["link"]
        if (indexerId == null || linkParam == null) {
            call.respondText("Missing indexer or link", status = HttpStatusCode.BadRequest)
            return
        }
        try {
            val link = String(Base64.getUrlDecoder().decode(linkParam), StandardCharsets.UTF_8)
            when (val result = search.download(indexerId, link)) {
                is DownloadResult.MagnetLink -> call.respondRedirect(result.uri, permanent = false)
                is DownloadResult.Torrent -> {
                    val name = result.fileName ?: ((call.request.queryParameters["file"] ?: "release") + ".torrent")
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString(),
                    )
                    call.respondBytes(result.bytes, ContentType("application", "x-bittorrent"))
                }
            }
        } catch (e: IndexerNotFoundException) {
            call.respondText(e.message ?: "Not found", status = HttpStatusCode.NotFound)
        } catch (e: Exception) {
            Log.e("Download failed", e)
            call.respondText("Download failed: ${e.message}", status = HttpStatusCode.InternalServerError)
        }
    }
}

private val API_KEY_PARAM = Regex("""(?i)(^|&)(apikey|api_key)=[^&]*""")

/** Hides the API key in a query string, for logs. */
internal fun redactQuery(query: String): String = query.replace(API_KEY_PARAM, "$1$2=REDACTED")
