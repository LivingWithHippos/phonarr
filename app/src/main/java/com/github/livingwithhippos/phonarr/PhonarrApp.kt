package com.github.livingwithhippos.phonarr

import android.app.Application
import android.content.pm.ApplicationInfo
import com.github.livingwithhippos.phonarr.browser.BrowserPages
import com.github.livingwithhippos.phonarr.browser.ChallengeNotifier
import com.github.livingwithhippos.phonarr.browser.WebViewChallengeSolver
import com.github.livingwithhippos.phonarr.core.cardigann.IndexerHttp
import com.github.livingwithhippos.phonarr.core.data.DefinitionRepository
import com.github.livingwithhippos.phonarr.core.data.IndexerRepository
import com.github.livingwithhippos.phonarr.core.data.SearchService
import com.github.livingwithhippos.phonarr.core.data.SettingsRepository
import com.github.livingwithhippos.phonarr.core.net.SwitchableDns
import com.github.livingwithhippos.phonarr.core.server.ServerInfo
import com.github.livingwithhippos.phonarr.core.server.TorznabServer
import com.github.livingwithhippos.phonarr.core.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.OkHttpClient
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
    private val packageInfo =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()

    /** The installed version, e.g. "1.0". */
    val versionName: String = packageInfo?.versionName ?: "0"

    /** .torrent files downloaded for sharing; shared through the FileProvider, emptied at each start */
    val torrentsDir: File = File(app.cacheDir, "torrents").also { dir ->
        Thread { dir.deleteRecursively() }.start()
    }
    val userAgent: String =
        runCatching { BrowserPages.userAgent(app) }.getOrDefault(IndexerHttp.DEFAULT_USER_AGENT)
    val challengeNotifier = ChallengeNotifier(app)
    val solver = WebViewChallengeSolver(app, onUnsolved = challengeNotifier::notify)

    val settings = SettingsRepository(filesDir)

    /** Base OkHttp client; its connection pool is shared with the Ktor engine built from it. */
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .dns(SwitchableDns { settings.settings.value })
        .build()

    val httpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 45_000
            connectTimeoutMillis = 15_000
        }
    }
    val indexers = IndexerRepository(filesDir)
    val definitions = DefinitionRepository(File(filesDir, "Definitions"), httpClient)
    val searchService =
        SearchService(indexers, definitions, httpClient, userAgent = userAgent, solver = solver)
    val server = TorznabServer(
        searchService,
        serverInfo(app),
        logRequests = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    ) { settings.settings.value.apiKey }

    private fun serverInfo(app: Application): ServerInfo {
        return ServerInfo(
            appVersion = versionName,
            osVersion = android.os.Build.VERSION.RELEASE,
            appDataPath = filesDir.absolutePath,
            // the APK's install/update time is the closest thing to a build time available at runtime
            buildTime = java.time.Instant.ofEpochMilli(packageInfo?.lastUpdateTime ?: 0),
        )
    }
}
