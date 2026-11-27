package com.github.livingwithhippos.phonarr.core

import com.github.livingwithhippos.phonarr.core.data.DefinitionRepository
import com.github.livingwithhippos.phonarr.core.data.IndexerConfig
import com.github.livingwithhippos.phonarr.core.data.IndexerRepository
import com.github.livingwithhippos.phonarr.core.data.SearchService
import com.github.livingwithhippos.phonarr.core.server.ProwlarrApi
import com.github.livingwithhippos.phonarr.core.server.ServerInfo
import com.github.livingwithhippos.phonarr.core.server.TorznabServer
import com.github.livingwithhippos.phonarr.core.torznab.SearchQuery
import com.github.livingwithhippos.phonarr.core.torznab.SearchType
import com.github.livingwithhippos.phonarr.core.util.Log
import io.ktor.client.request.header
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TorznabServerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun server(logRequests: Boolean = false): TorznabServer {
        val dir = tmp.newFolder()
        val defs = File(dir, "Definitions").apply { mkdirs() }
        File(defs, "testsite.yml").writeText(resource("testsite.yml"))
        val client = HttpClient(MockEngine {
            respond(resource("testsite.html"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
        }) { followRedirects = false; expectSuccess = false }
        val indexers = IndexerRepository(dir)
        indexers.save(IndexerConfig(0, "testsite", "Test Site"))
        val service = SearchService(indexers, DefinitionRepository(defs, client), client)
        return TorznabServer(service, ServerInfo(appVersion = "1.0", osVersion = "16"), logRequests) { "secret" }
    }

    @Test
    fun logsRequestsWithoutApiKey() = testApplication {
        val logged = mutableListOf<String>()
        val old = Log.sink
        Log.sink = { _, msg, _ -> if (msg.startsWith("Server ")) synchronized(logged) { logged += msg } }
        try {
            val s = server(logRequests = true)
            application { with(s) { module() } }
            client.get("/1/api?t=caps&apikey=secret")
            client.get("/1/api?apikey=wrong&t=caps")
        } finally {
            Log.sink = old
        }
        assertEquals(logged.toString(), 2, logged.size)
        assertTrue(logged[0], logged[0].startsWith("Server GET /1/api?t=caps&apikey=REDACTED -> 200"))
        assertTrue(logged[1], logged[1].startsWith("Server GET /1/api?apikey=REDACTED&t=caps -> 401"))
        assertTrue(logged.none { "secret" in it || "wrong" in it })
    }

    @Test
    fun noRequestLogsByDefault() = testApplication {
        val logged = mutableListOf<String>()
        val old = Log.sink
        Log.sink = { _, msg, _ -> if (msg.startsWith("Server ")) synchronized(logged) { logged += msg } }
        try {
            val s = server()
            application { with(s) { module() } }
            client.get("/1/api?t=caps&apikey=secret")
        } finally {
            Log.sink = old
        }
        assertTrue(logged.toString(), logged.isEmpty())
    }

    @Test
    fun rejectsWrongApiKey() = testApplication {
        val s = server()
        application { with(s) { module() } }
        val resp = client.get("/1/api?t=caps&apikey=wrong")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertTrue(resp.bodyAsText().contains("code=\"100\""))
    }

    @Test
    fun capsAndSearch() = testApplication {
        val s = server()
        application { with(s) { module() } }
        val caps = client.get("/1/api?t=caps&apikey=secret").bodyAsText()
        assertTrue(caps, caps.contains("<caps>"))

        val all = client.get("/api/v2.0/indexers/all/results/torznab/api?t=search&q=matrix&apikey=secret").bodyAsText()
        assertTrue(all, all.contains("<title>The Matrix 1999 1080p</title>"))
        assertTrue(all, all.contains("<torznab:attr name=\"seeders\" value=\"1024\" />"))
        assertTrue(all, all.contains("<link>magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567"))

        val tv = client.get("/1/api?t=tvsearch&q=show&cat=5000&apikey=secret").bodyAsText()
        assertTrue(tv, tv.contains("Some Show S01E01") && !tv.contains("The Matrix"))

        val missing = client.get("/99/api?t=search&apikey=secret")
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun prowlarrSystemStatus() = testApplication {
        val s = server()
        application { with(s) { module() } }
        val resp = client.get("/api/v1/system/status") { header("X-Api-Key", "secret") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
        val status = obj(resp.bodyAsText())
        assertEquals("Prowlarr", status.str("appName"))
        assertEquals("Phonarr", status.str("instanceName"))
        assertEquals(ProwlarrApi.API_VERSION, status.str("version"))
        assertEquals("1.0", status.str("phonarrVersion"))
        assertEquals("android", status.str("osName"))
        assertEquals("16", status.str("osVersion"))
        assertTrue(status.str("startTime")!!.endsWith("Z"))

        val byQuery = client.get("/api/v1/system/status?apikey=secret")
        assertEquals(HttpStatusCode.OK, byQuery.status)

        val denied = client.get("/api/v1/system/status")
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
        assertEquals("Unauthorized", obj(denied.bodyAsText()).str("message"))
    }

    @Test
    fun prowlarrSearch() = testApplication {
        val s = server()
        application { with(s) { module() } }
        val resp = client.get("/api/v1/search?query=matrix&type=search") { header("X-Api-Key", "secret") }
        assertEquals(HttpStatusCode.OK, resp.status)
        val releases = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(2, releases.size)
        val r = releases[0].jsonObject
        assertEquals("The Matrix 1999 1080p", r.str("title"))
        assertEquals("matrix 1999 1080p", r.str("sortTitle"))
        assertEquals(1536L * 1024 * 1024, r["size"]!!.jsonPrimitive.long)
        assertEquals(1024, r["seeders"]!!.jsonPrimitive.int)
        assertEquals(12, r["leechers"]!!.jsonPrimitive.int)
        assertEquals(1, r["indexerId"]!!.jsonPrimitive.int)
        assertEquals("Test Site", r.str("indexer"))
        assertEquals("torrent", r.str("protocol"))
        assertEquals("The Matrix 1999 1080p.torrent", r.str("fileName"))
        assertTrue(r.str("magnetUrl")!!.startsWith("magnet:?xt=urn:btih:0123"))
        assertEquals(JsonNull, r["downloadUrl"])
        assertEquals(2000, r["categories"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.int)
        // the fixture's dates are relative to DateTimeUtil's clock, which other tests may pin: check consistency
        val ageHours = r["ageHours"]!!.jsonPrimitive.content.toDouble()
        assertEquals((ageHours / 24).toInt(), r["age"]!!.jsonPrimitive.int)
        assertEquals(ageHours * 60, r["ageMinutes"]!!.jsonPrimitive.content.toDouble(), 0.01)
        assertTrue(r.str("publishDate")!!.endsWith("Z"))
        assertTrue(r["indexerFlags"] is JsonArray)

        // repeated and comma-separated lists, as ASP.NET binds them
        val tv = client.get("/api/v1/search?query=show&type=search&categories=5000&indexerIds=1&apikey=secret").bodyAsText()
        val tvTitles = Json.parseToJsonElement(tv).jsonArray.map { it.jsonObject.str("title") }
        assertEquals(listOf("Some Show S01E01 720p"), tvTitles)

        val unknown = client.get("/api/v1/search?query=x&indexerIds=1,99&apikey=secret")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertTrue(obj(unknown.bodyAsText()).str("message")!!.contains("99"))

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/search?query=x").status)
    }

    @Test
    fun torznabWithoutTIsASearch() = testApplication {
        val s = server()
        application { with(s) { module() } }
        val resp = client.get("/api/v2.0/indexers/all/results/torznab/api?apikey=secret")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("<title>The Matrix 1999 1080p</title>"))
        assertEquals(HttpStatusCode.BadRequest, client.get("/1/api?t=nope&apikey=secret").status)
    }

    @Test
    fun prowlarrQueryTokens() {
        val tv = SearchQuery.fromProwlarr("Some Show {Season:1}{Episode:2}{TvdbId:123}", "tvsearch", emptyList(), 50, null)
        assertEquals(SearchType.Tv, tv.type)
        assertEquals("Some Show", tv.q)
        assertEquals(1, tv.season)
        assertEquals("2", tv.ep)
        assertEquals(123, tv.tvdbId)
        assertEquals(50, tv.limit)

        val movie = SearchQuery.fromProwlarr("{imdbid:tt0111161}", "movie", listOf(2000), null, null)
        assertEquals("tt0111161", movie.imdbId)
        assertNull(movie.q)
        assertEquals(listOf(2000), movie.categories)

        val book = SearchQuery.fromProwlarr("{Author:Frank Herbert} {Title:Dune}", "book", emptyList(), null, null)
        assertEquals("Frank Herbert", book.author)
        assertEquals("Dune", book.title)

        // plain searches keep the braces as typed
        assertEquals("a {Season:1}", SearchQuery.fromProwlarr("a {Season:1}", null, emptyList(), null, null).q)
    }
}
