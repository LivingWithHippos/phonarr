package com.github.livingwithhippos.phonarr.core.cardigann

/** A page loaded by a real browser engine. */
data class BrowserPage(val url: String, val content: String)

/**
 * Gets past anti-bot pages (Cloudflare, DDoS-Guard) using a real browser.
 * On Android this is a WebView; the core only depends on this interface.
 */
interface ChallengeSolver {
    /** Cookies the browser already holds for this url (e.g. after the user solved a check by hand). */
    fun cookies(url: String): Map<String, String>

    /** Loads [url] until the protection page is gone. Returns the site's cookies, or null if it wasn't solved. */
    suspend fun solve(url: String, userAgent: String): Map<String, String>?

    /** Loads [url] in the browser and returns the page content, or null if the protection page never went away. */
    suspend fun fetch(url: String, userAgent: String): BrowserPage?
}

class ChallengeException(message: String, val url: String) : Exception(message)

object Challenge {
    private val bodyMarkers = listOf(
        "<title>Just a moment...</title>",
        "<title>Attention Required! | Cloudflare</title>",
        "cf-browser-verification",
        "cf_chl_opt",
        "/cdn-cgi/challenge-platform/",
        "challenges.cloudflare.com/turnstile",
        "<title>DDoS-Guard</title>",
        "ddos-guard.net/",
    )

    /** Name of the protection that blocked [resp], or null if it's a normal response. */
    fun detect(resp: HttpResp): String? {
        fun header(name: String) = resp.headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        if (header("cf-mitigated")?.equals("challenge", true) == true) return "Cloudflare"
        if (resp.status !in listOf(403, 429, 503)) return null
        val server = header("Server")?.lowercase() ?: ""
        if (!server.contains("cloudflare") && !server.contains("ddos-guard")) return null
        val head = resp.content.take(20_000)
        if (bodyMarkers.none { head.contains(it, ignoreCase = true) }) return null
        return if (server.contains("ddos-guard")) "DDoS-Guard" else "Cloudflare"
    }
}
