package com.github.livingwithhippos.phonarr.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.livingwithhippos.phonarr.ui.theme.PhonarrTheme
import kotlinx.coroutines.delay
import java.net.URI

/**
 * Shows a site in a visible WebView so the user can pass a check the hidden solver couldn't
 * (e.g. an interactive Turnstile). Cookies end up in the shared CookieManager, where the
 * solver picks them up on the next request.
 */
class ChallengeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val userAgent = BrowserPages.userAgent(this)
        setContent {
            PhonarrTheme {
                ChallengeScreen(url, userAgent) { solved ->
                    if (solved) {
                        CookieManager.getInstance().flush()
                        Toast.makeText(this, "Check passed. Phonarr can search this site now.", Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, ChallengeActivity::class.java).putExtra(EXTRA_URL, url)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChallengeScreen(url: String, userAgent: String, onDone: (Boolean) -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("Waiting for the site's check…") }
    val host = remember(url) { runCatching { URI(url).host }.getOrNull() ?: url }

    // close automatically once the protection page is gone
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        var cleanPolls = 0
        var sawChallenge = false
        while (true) {
            delay(1000)
            val state = BrowserPages.state(view) ?: continue
            if (state.challenge) sawChallenge = true
            cleanPolls = if (state.complete && !state.challenge) cleanPolls + 1 else 0
            if (cleanPolls >= 2) {
                if (sawChallenge) {
                    onDone(true)
                    return@LaunchedEffect
                }
                status = "No check showing right now. You can go back, or browse to a search page if the site needs it."
            } else if (state.challenge) {
                status = "Complete the check below if asked."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify $host") },
                actions = { TextButton(onClick = { onDone(BrowserPages.cookies(url).isNotEmpty()) }) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(status, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).also {
                        BrowserPages.configure(it, userAgent)
                        it.webViewClient = WebViewClient()
                        it.loadUrl(url)
                        webView = it
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
