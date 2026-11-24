package com.github.livingwithhippos.phonarr.browser

import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.github.livingwithhippos.phonarr.core.cardigann.BrowserPage
import com.github.livingwithhippos.phonarr.core.cardigann.ChallengeSolver
import com.github.livingwithhippos.phonarr.core.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Passes anti-bot checks in a hidden WebView. One page at a time, on the main thread.
 *
 * @param onUnsolved called when a check couldn't be passed automatically, so the app can ask the user.
 */
class WebViewChallengeSolver(
    context: Context,
    private val timeoutMs: Long = 30_000,
    private val onUnsolved: (url: String) -> Unit = {},
) : ChallengeSolver {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    override fun cookies(url: String): Map<String, String> = BrowserPages.cookies(url)

    override suspend fun solve(url: String, userAgent: String): Map<String, String>? {
        val page = load(url, userAgent)
        if (page == null) {
            onUnsolved(url)
            return null
        }
        return cookies(url)
    }

    override suspend fun fetch(url: String, userAgent: String): BrowserPage? {
        val page = load(url, userAgent)
        if (page == null) onUnsolved(url)
        return page
    }

    /** Loads [url] and waits until the page is complete and not a protection page. */
    private suspend fun load(url: String, userAgent: String): BrowserPage? = mutex.withLock {
        withContext(Dispatchers.Main) {
            val webView = WebView(appContext)
            try {
                BrowserPages.configure(webView, userAgent)
                webView.webViewClient = WebViewClient()
                // give the off-screen view a real size: some checks look at the viewport
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, 1080, 1920)
                webView.loadUrl(url)

                withTimeoutOrNull(timeoutMs) {
                    var cleanPolls = 0
                    while (true) {
                        delay(POLL_MS)
                        val state = BrowserPages.state(webView) ?: continue
                        cleanPolls = if (state.complete && !state.challenge) cleanPolls + 1 else 0
                        // two clean polls in a row: the check's redirect has settled
                        if (cleanPolls >= 2) {
                            CookieManager.getInstance().flush()
                            val content = BrowserPages.content(webView) ?: return@withTimeoutOrNull null
                            return@withTimeoutOrNull BrowserPage(webView.url ?: url, content)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }.also { if (it == null) Log.w("Browser check not passed for $url") }
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    companion object {
        private const val POLL_MS = 750L
    }
}
