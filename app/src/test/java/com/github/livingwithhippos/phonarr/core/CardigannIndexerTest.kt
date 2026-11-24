package com.github.livingwithhippos.phonarr.core

import com.github.livingwithhippos.phonarr.core.cardigann.CardigannIndexer
import com.github.livingwithhippos.phonarr.core.cardigann.DefinitionParser
import com.github.livingwithhippos.phonarr.core.torznab.SearchQuery
import com.github.livingwithhippos.phonarr.core.torznab.SearchType
import com.github.livingwithhippos.phonarr.core.util.DateTimeUtil
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

fun resource(name: String): String = CardigannIndexerTest::class.java.classLoader!!.getResource(name)!!.readText()

class CardigannIndexerTest {
    init {
        DateTimeUtil.zone = ZoneId.of("UTC")
        DateTimeUtil.clock = { ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneId.of("UTC")) }
    }

    private val requested = mutableListOf<String>()

    private fun client(body: String, contentType: String = "text/html") = HttpClient(MockEngine { req ->
        requested += req.url.toString()
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, contentType))
    }) { followRedirects = false; expectSuccess = false }

    @Test
    fun buildsSearchUrls() {
        val def = DefinitionParser.parse(resource("testsite.yml"))
        val idx = CardigannIndexer(def, mapOf("sort" to "time"), client(""))
        val reqs = idx.buildRequests(SearchQuery(type = SearchType.Search, q = "the matrix", categories = listOf(2000)))
        assertEquals(1, reqs.size)
        // $raw values are url-encoded twice, exactly like Prowlarr does
        assertEquals("https://test.example/search/the%20matrix/1/?sort=time&c[]=1", reqs[0].http.url)

        val rss = idx.buildRequests(SearchQuery())
        assertEquals("https://test.example/latest/?sort=time", rss[0].http.url)
    }

    @Test
    fun tvSearchAddsEpisode() {
        val def = DefinitionParser.parse(resource("testsite.yml"))
        val idx = CardigannIndexer(def, emptyMap(), client(""))
        val reqs = idx.buildRequests(SearchQuery(type = SearchType.Tv, q = "Some Show", season = 1, ep = "2"))
        assertEquals("https://test.example/search/Some%20Show%20S01E02/1/?sort=seeders", reqs[0].http.url)
    }

    @Test
    fun parsesHtmlRows() = runTest {
        val def = DefinitionParser.parse(resource("testsite.yml"))
        val idx = CardigannIndexer(def, emptyMap(), client(resource("testsite.html")))
        val releases = idx.search(SearchQuery(q = "matrix"))
        assertEquals(2, releases.size)
        val r = releases[0]
        assertEquals("The Matrix 1999 1080p", r.title)
        assertEquals("https://test.example/torrent/11/the-matrix-1999/", r.infoUrl)
        assertTrue(r.magnetUrl!!.startsWith("magnet:?xt=urn:btih:0123"))
        assertEquals("0123456789ABCDEF0123456789ABCDEF01234567", r.infoHash)
        assertEquals(1536L * 1024 * 1024, r.size)
        assertEquals(1024, r.seeders)
        assertEquals(1036, r.peers)
        assertEquals(10, r.publishDate!!.hour)
        assertEquals(listOf(2000, 100001), r.categories.map { it.id })
        assertEquals(listOf("internal"), r.flags)
        assertEquals(0.0, r.downloadVolumeFactor!!, 0.0)
        assertNull(releases[1].description)
        assertEquals(listOf(5040, 100002), releases[1].categories.map { it.id })
    }

    @Test
    fun parsesJsonRows() = runTest {
        val json = """
            {"total": 1, "results": [
              {"name": "Film 2020", "type": "movies", "hash": "aabbccddeeff00112233445566778899aabbccdd",
               "size": 2048, "stats": {"seeders": 7, "leechers": 3}, "added": 1700000000, "imdb": "tt0111161",
               "genres": ["Drama", "Crime"]}
            ]}
        """.trimIndent()
        val def = DefinitionParser.parse(resource("testjson.yml"))
        val idx = CardigannIndexer(def, emptyMap(), client(json, "application/json"))
        val releases = idx.search(SearchQuery(type = SearchType.Movie, q = "film", imdbId = "tt0111161"))
        assertTrue(requested.single().contains("q=film") && requested.single().contains("imdb=tt0111161"))
        assertEquals(1, releases.size)
        val r = releases[0]
        assertEquals("Film 2020", r.title)
        assertEquals(2048L, r.size)
        assertEquals(7, r.seeders)
        assertEquals(10, r.peers)
        assertEquals(111161, r.imdbId)
        assertEquals(listOf("Drama", "Crime"), r.genres)
        assertEquals(listOf(2000), r.categories.map { it.id })
        assertTrue(r.magnetUrl!!.contains("AABBCCDDEEFF00112233445566778899AABBCCDD"))
    }

    @Test
    fun jsonNoResults() = runTest {
        val def = DefinitionParser.parse(resource("testjson.yml"))
        val idx = CardigannIndexer(def, emptyMap(), client("""{"total":0,"results":[]}""", "application/json"))
        assertEquals(0, idx.search(SearchQuery(q = "nothing")).size)
    }

    @Test
    fun capabilitiesXml() {
        val def = DefinitionParser.parse(resource("testsite.yml"))
        val xml = CardigannIndexer(def, emptyMap(), client("")).capabilities.toXml()
        assertTrue(xml, xml.contains("<tv-search available=\"yes\" supportedParams=\"q,season,ep\""))
        assertTrue(xml, xml.contains("<movie-search available=\"yes\" supportedParams=\"q,imdbid\""))
        assertTrue(xml, xml.contains("<music-search available=\"no\""))
        assertTrue(xml, xml.contains("<category id=\"2000\" name=\"Movies\""))
        assertTrue(xml, xml.contains("<subcat id=\"5040\" name=\"TV/HD\""))
        assertTrue(xml, xml.contains("<category id=\"100001\" name=\"Movies\""))
    }
}
