package com.github.livingwithhippos.phonarr.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/** Shared WebView helpers for the hidden solver and the visible challenge screen. */
object BrowserPages {

    /**
     * The WebView user agent without the "; wv" and "Version/4.0" markers, so sites see a normal
     * Chrome. The HTTP client uses the same value: Cloudflare ties its cookie to the user agent.
     */
    fun userAgent(context: Context): String =
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace(Regex("Version/\\d+(\\.\\d+)*\\s"), "")

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, userAgent: String) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgent
            loadsImagesAutomatically = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    fun cookies(url: String): Map<String, String> {
        val raw = CookieManager.getInstance().getCookie(url) ?: return emptyMap()
        return raw.split(';').mapNotNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) kv[0] to kv[1] else null
        }.toMap()
    }

    /** JS returning [readyState, isChallengePage, contentType]. */
    private const val STATE_JS = """(function(){
        var t = document.title || '';
        var challenge = !!document.querySelector('#challenge-form,#challenge-running,#challenge-stage,#cf-challenge-running,.cf-turnstile,#turnstile-wrapper,#cf-wrapper,#ddg-captcha,#ddg-l10n-title')
            || /just a moment|attention required|please wait|checking your browser|ddos-guard|un instant|einen moment/i.test(t);
        return JSON.stringify([document.readyState, challenge, document.contentType || '']);
    })()"""

    private const val CONTENT_JS = """(function(){
        var ct = document.contentType || 'text/html';
        if ((ct.indexOf('html') >= 0 || ct.indexOf('xml') >= 0) && document.documentElement)
            return document.documentElement.outerHTML;
        return document.body ? document.body.innerText : '';
    })()"""

    data class PageState(val complete: Boolean, val challenge: Boolean)

    suspend fun state(webView: WebView): PageState? {
        val raw = evaluate(webView, STATE_JS) ?: return null
        return runCatching {
            // evaluateJavascript returns a JSON-encoded string holding our JSON array
            val inner = Json.parseToJsonElement(raw).jsonPrimitive.content
            val arr = Json.parseToJsonElement(inner).jsonArray
            PageState(arr[0].jsonPrimitive.content == "complete", arr[1].jsonPrimitive.content == "true")
        }.getOrNull()
    }

    suspend fun content(webView: WebView): String? {
        val raw = evaluate(webView, CONTENT_JS) ?: return null
        return runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull()
    }

    private suspend fun evaluate(webView: WebView, js: String): String? = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(js) { if (cont.isActive) cont.resume(it) }
    }
}
