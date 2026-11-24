package com.github.livingwithhippos.phonarr.core

import com.github.livingwithhippos.phonarr.core.cardigann.BrowserPage
import com.github.livingwithhippos.phonarr.core.cardigann.Challenge
import com.github.livingwithhippos.phonarr.core.cardigann.ChallengeException
import com.github.livingwithhippos.phonarr.core.cardigann.ChallengeSolver
import com.github.livingwithhippos.phonarr.core.cardigann.HttpReq
import com.github.livingwithhippos.phonarr.core.cardigann.HttpResp
import com.github.livingwithhippos.phonarr.core.cardigann.IndexerHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeTest {
    private val challengeHtml = "<html><head><title>Just a moment...</title></head><body><script src=\"/cdn-cgi/challenge-platform/x.js\"></script></body></html>"

    private class FakeSolver(
        var solveResult: Map<String, String>? = mapOf("cf_clearance" to "ok"),
        var stored: Map<String, String> = emptyMap(),
    ) : ChallengeSolver {
        var solveCalls = 0
        var fetchCalls = 0
        override fun cookies(url: String) = stored
        override suspend fun solve(url: String, userAgent: String): Map<String, String>? {
            solveCalls++
            return solveResult?.also { stored = it }
        }
        override suspend fun fetch(url: String, userAgent: String): BrowserPage? {
            fetchCalls++
            return BrowserPage(url, "<html><body>browser page for $url</body></html>")
        }
    }

    /** A site that challenges unless the request carries cf_clearance=ok (or always, if [fingerprinting]). */
    private fun site(fingerprinting: Boolean = false, calls: MutableList<String> = mutableListOf()) = HttpClient(MockEngine { req ->
        calls += req.url.toString()
        val cookie = req.headers[HttpHeaders.Cookie] ?: ""
        if (!fingerprinting && cookie.contains("cf_clearance=ok")) {
            respond("<html>results</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
        } else {
            respond(
                challengeHtml, HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.Server to listOf("cloudflare"), "cf-mitigated" to listOf("challenge")),
            )
        }
    }) { followRedirects = false; expectSuccess = false }

    private fun resp(status: Int, body: String, vararg headers: Pair<String, String>) =
        HttpResp("https://x/", status, headers.associate { it.first to listOf(it.second) }, body.toByteArray(), Charsets.UTF_8)

    @Test
    fun detection() {
        assertEquals("Cloudflare", Challenge.detect(resp(403, "", "cf-mitigated" to "challenge")))
        assertEquals("Cloudflare", Challenge.detect(resp(503, challengeHtml, "Server" to "cloudflare")))
        assertEquals("DDoS-Guard", Challenge.detect(resp(403, "<title>DDoS-Guard</title>", "Server" to "ddos-guard")))
        // a plain 403 from a Cloudflare site (e.g. a real "forbidden") is not a challenge
        assertNull(Challenge.detect(resp(403, "<html>Forbidden</html>", "Server" to "cloudflare")))
        assertNull(Challenge.detect(resp(200, challengeHtml, "Server" to "cloudflare")))
    }

    @Test
    fun withoutSolverTheErrorIsClear() = runTest {
        val http = IndexerHttp(site(), 0)
        val e = assertThrows(ChallengeException::class.java) { kotlinx.coroutines.runBlocking { http.execute(HttpReq("https://site.example/search")) } }
        assertTrue(e.message!!.contains("Cloudflare"))
    }

    @Test
    fun solvedCookiesAreReused() = runTest {
        val solver = FakeSolver()
        val calls = mutableListOf<String>()
        val http = IndexerHttp(site(calls = calls), 0, solver = solver)
        assertEquals("<html>results</html>", http.execute(HttpReq("https://site.example/a")).content)
        assertEquals("<html>results</html>", http.execute(HttpReq("https://site.example/b")).content)
        assertEquals(1, solver.solveCalls)
        assertEquals(3, calls.size) // challenge, retry, second request directly
    }

    @Test
    fun cookiesFromAManualCheckAreUsedFirst() = runTest {
        val solver = FakeSolver(stored = mapOf("cf_clearance" to "ok"))
        val http = IndexerHttp(site(), 0, solver = solver)
        assertEquals("<html>results</html>", http.execute(HttpReq("https://site.example/a")).content)
        assertEquals(0, solver.solveCalls)
    }

    @Test
    fun fingerprintedSitesGoThroughTheBrowser() = runTest {
        val solver = FakeSolver()
        val calls = mutableListOf<String>()
        val http = IndexerHttp(site(fingerprinting = true, calls = calls), 0, solver = solver)
        assertTrue(http.execute(HttpReq("https://site.example/a")).content.contains("browser page for https://site.example/a"))
        assertTrue(http.browserOnly)
        assertTrue(http.execute(HttpReq("https://site.example/b")).content.contains("browser page"))
        assertEquals(2, calls.size) // later GETs skip the HTTP client
        assertEquals(2, solver.fetchCalls)

        // binary downloads can't come from a browser page
        assertThrows(ChallengeException::class.java) {
            kotlinx.coroutines.runBlocking { http.execute(HttpReq("https://site.example/file.torrent", allowBrowser = false)) }
        }
    }

    @Test
    fun unsolvedCheckAsksTheUser() = runTest {
        val solver = FakeSolver(solveResult = null)
        val http = IndexerHttp(site(), 0, solver = solver)
        val e = assertThrows(ChallengeException::class.java) { kotlinx.coroutines.runBlocking { http.execute(HttpReq("https://site.example/a")) } }
        assertTrue(e.message!!.contains("Open the site from Phonarr"))
        assertEquals("https://site.example/a", e.url)
    }
}
