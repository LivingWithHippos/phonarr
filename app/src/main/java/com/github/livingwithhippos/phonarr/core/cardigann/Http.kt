package com.github.livingwithhippos.phonarr.core.cardigann

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.parseServerSetCookieHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.nio.charset.Charset

data class HttpReq(
    val url: String,
    val method: String = "GET",
    val form: List<Pair<String, String>> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val followRedirects: Boolean = false,
    val charset: Charset = Charsets.UTF_8,
    val referer: String? = null,
    /** false for binary downloads, which can't be read back from a browser page */
    val allowBrowser: Boolean = true,
    /** send [form] as multipart/form-data instead of URL-encoded */
    val multipart: Boolean = false,
)

class HttpResp(
    val url: String,
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    private val charset: Charset,
) {
    val content: String by lazy { String(body, charset) }
    val isRedirect: Boolean get() = status in 300..399 && location != null
    val location: String? get() = headers.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull()
    val redirectUrl: String? get() = location?.let { runCatching { URI(url).resolve(it.replace(" ", "%20")).toString() }.getOrDefault(it) }
}

class HttpException(message: String, val response: HttpResp? = null) : Exception(message)

/**
 * Executes requests for one indexer: keeps its cookies, applies the definition's request
 * delay and follows redirects manually so cookies set along the way are kept.
 *
 * When a site answers with an anti-bot page, [solver] is used to get past it: first by
 * reusing the browser's cookies, and if the site still refuses this HTTP client, by
 * loading pages through the browser.
 */
class IndexerHttp(
    private val client: HttpClient,
    private val requestDelayMs: Long,
    val userAgent: String = DEFAULT_USER_AGENT,
    private val solver: ChallengeSolver? = null,
) {
    val cookies: MutableMap<String, String> = LinkedHashMap()
    private val mutex = Mutex()
    private var lastRequest = 0L

    /** Set once cookies alone weren't enough: GET requests then go through the browser. */
    @Volatile
    var browserOnly = false
        private set

    suspend fun execute(req: HttpReq): HttpResp {
        if (browserOnly && solver != null && canUseBrowser(req)) return fetchWithBrowser(req)

        val resp = executeFollowing(req)
        val provider = Challenge.detect(resp) ?: return resp
        if (solver == null) {
            throw ChallengeException("$provider protection blocked the request to ${host(req.url)}", req.url)
        }

        // the user may have solved the check in the app already
        val stored = solver.cookies(req.url)
        if (stored.isNotEmpty() && stored.any { cookies[it.key] != it.value }) {
            cookies.putAll(stored)
            val retry = executeFollowing(req)
            if (Challenge.detect(retry) == null) return retry
        }

        val solved = solver.solve(req.url, userAgent)
            ?: throw ChallengeException(
                "$provider check for ${host(req.url)} couldn't be passed automatically. Open the site from Phonarr to pass it once.",
                req.url,
            )
        cookies.putAll(solved)
        val retry = executeFollowing(req)
        if (Challenge.detect(retry) == null) return retry

        // the site still refuses this client (e.g. TLS fingerprinting): use the browser from now on
        if (canUseBrowser(req)) {
            browserOnly = true
            return fetchWithBrowser(req)
        }
        throw ChallengeException("$provider still blocks ${host(req.url)} after the browser check", req.url)
    }

    private fun canUseBrowser(req: HttpReq) = req.allowBrowser && !req.method.equals("POST", true)

    private suspend fun fetchWithBrowser(req: HttpReq): HttpResp {
        waitForTurn()
        val page = solver!!.fetch(req.url, userAgent)
            ?: throw ChallengeException(
                "The protection page for ${host(req.url)} didn't go away in the browser. Open the site from Phonarr to pass it once.",
                req.url,
            )
        cookies.putAll(solver.cookies(req.url))
        return HttpResp(page.url, 200, emptyMap(), page.content.toByteArray(req.charset), req.charset)
    }

    private suspend fun waitForTurn() {
        mutex.withLock {
            val wait = lastRequest + requestDelayMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequest = System.currentTimeMillis()
        }
    }

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull() ?: url

    private suspend fun executeFollowing(req: HttpReq): HttpResp {
        var current = req
        repeat(MAX_REDIRECTS) {
            val resp = executeOnce(current)
            if (!current.followRedirects || !resp.isRedirect) return resp
            val next = resp.redirectUrl!!
            if (!next.startsWith("http")) return resp // e.g. magnet: links
            current = current.copy(url = next, method = "GET", form = emptyList(), referer = current.url)
        }
        throw HttpException("Too many redirects for ${req.url}")
    }

    private suspend fun executeOnce(req: HttpReq): HttpResp {
        waitForTurn()
        val response = client.request {
            url(req.url)
            method = if (req.method.equals("POST", true)) HttpMethod.Post else HttpMethod.Get
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8")
            req.referer?.let { header(HttpHeaders.Referrer, it) }
            if (cookies.isNotEmpty()) header(HttpHeaders.Cookie, cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            req.headers.forEach { (k, v) -> header(k, v) }
            if (method == HttpMethod.Post && req.multipart) {
                setBody(MultiPartFormDataContent(formData { req.form.forEach { (k, v) -> append(k, v) } }))
            } else if (method == HttpMethod.Post) {
                val body = req.form.joinToString("&") { (k, v) ->
                    Filters.urlEncode(k, req.charset) + "=" + Filters.urlEncode(v, req.charset)
                }
                setBody(TextContent(body, ContentType.Application.FormUrlEncoded.withParameter("charset", req.charset.name())))
            }
        }
        response.headers.getAll(HttpHeaders.SetCookie)?.forEach { raw ->
            runCatching { parseServerSetCookieHeader(raw) }.getOrNull()?.let { c ->
                if (c.value.isEmpty() || c.value == "deleted") cookies.remove(c.name) else cookies[c.name] = c.value
            }
        }
        val headers = response.headers.entries().associate { it.key to it.value }
        return HttpResp(req.url, response.status.value, headers, response.bodyAsBytes(), req.charset)
    }

    companion object {
        const val MAX_REDIRECTS = 8
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}
