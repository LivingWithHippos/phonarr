package com.github.livingwithhippos.phonarr

import android.app.Application
import com.github.livingwithhippos.phonarr.browser.BrowserPages
import com.github.livingwithhippos.phonarr.browser.ChallengeNotifier
import com.github.livingwithhippos.phonarr.browser.WebViewChallengeSolver
import com.github.livingwithhippos.phonarr.core.cardigann.IndexerHttp
import com.github.livingwithhippos.phonarr.core.data.DefinitionRepository
import com.github.livingwithhippos.phonarr.core.data.IndexerRepository
import com.github.livingwithhippos.phonarr.core.data.SearchService
import com.github.livingwithhippos.phonarr.core.data.SettingsRepository
import com.github.livingwithhippos.phonarr.core.server.ServerInfo
import com.github.livingwithhippos.phonarr.core.server.TorznabServer
import com.github.livingwithhippos.phonarr.core.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File

class PhonarrApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Log.sink = { level, msg, t ->
            when (level) {
                Log.Level.DEBUG -> android.util.Log.d(TAG, msg, t)
                Log.Level.INFO -> android.util.Log.i(TAG, msg, t)
                Log.Level.WARN -> android.util.Log.w(TAG, msg, t)
                Log.Level.ERROR -> android.util.Log.e(TAG, msg, t)
            }
        }
        container = AppContainer(this)
    }

    companion object {
        const val TAG = "Phonarr"
    }
}

/** Holds the app-wide singletons. */
class AppContainer(app: Application) {
    private val filesDir: File = app.filesDir
    val userAgent: String = runCatching { BrowserPages.userAgent(app) }.getOrDefault(IndexerHttp.DEFAULT_USER_AGENT)
    val challengeNotifier = ChallengeNotifier(app)
    val solver = WebViewChallengeSolver(app, onUnsolved = challengeNotifier::notify)

    val httpClient = HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 45_000
            connectTimeoutMillis = 15_000
        }
    }
    val settings = SettingsRepository(filesDir)
    val indexers = IndexerRepository(filesDir)
    val definitions = DefinitionRepository(File(filesDir, "Definitions"), httpClient)
    val searchService = SearchService(indexers, definitions, httpClient, userAgent = userAgent, solver = solver)
    val server = TorznabServer(searchService, serverInfo(app)) { settings.settings.value.apiKey }

    private fun serverInfo(app: Application): ServerInfo {
        val pkg = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        return ServerInfo(
            appVersion = pkg?.versionName ?: "0",
            osVersion = android.os.Build.VERSION.RELEASE,
            appDataPath = filesDir.absolutePath,
            // the APK's install/update time is the closest thing to a build time available at runtime
            buildTime = java.time.Instant.ofEpochMilli(pkg?.lastUpdateTime ?: 0),
        )
    }
}
