package com.github.livingwithhippos.phonarr.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.livingwithhippos.phonarr.PhonarrApp
import com.github.livingwithhippos.phonarr.core.cardigann.CardigannDefinition
import com.github.livingwithhippos.phonarr.core.cardigann.ChallengeException
import com.github.livingwithhippos.phonarr.core.data.AppSettings
import com.github.livingwithhippos.phonarr.core.data.Backup
import com.github.livingwithhippos.phonarr.core.data.BackupFile
import com.github.livingwithhippos.phonarr.core.data.DohProvider
import com.github.livingwithhippos.phonarr.core.data.IndexerConfig
import com.github.livingwithhippos.phonarr.core.data.ThemeColor
import com.github.livingwithhippos.phonarr.core.data.ThemeMode
import com.github.livingwithhippos.phonarr.core.net.SwitchableDns
import com.github.livingwithhippos.phonarr.core.torznab.ReleaseInfo
import com.github.livingwithhippos.phonarr.core.torznab.SearchQuery
import com.github.livingwithhippos.phonarr.service.ServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.livingwithhippos.phonarr.core.cardigann.DownloadResult
import java.io.File

private const val DOH_TEST_HOST = "github.com"

data class SearchState(
    val running: Boolean = false,
    val results: List<ReleaseInfo> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val searched: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as PhonarrApp).container

    val settings = container.settings.settings
    val versionName = container.versionName
    val indexers = container.indexers.indexers
    val definitions = container.definitions.summaries
    val serverRunning = ServerService.running
    val serverError = ServerService.error

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _updating = MutableStateFlow(false)
    val updatingDefinitions: StateFlow<Boolean> = _updating.asStateFlow()

    private val _testing = MutableStateFlow<Set<Int>>(emptySet())
    val testing: StateFlow<Set<Int>> = _testing.asStateFlow()

    /** Indexer id -> url of a site check the hidden browser couldn't pass. */
    private val _challenges = MutableStateFlow<Map<Int, String>>(emptyMap())
    val challenges: StateFlow<Map<Int, String>> = _challenges.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    init {
        // first launch: fetch definitions so the user can add indexers right away
        if (definitions.value.isEmpty()) updateDefinitions()
    }

    fun clearMessage() { _message.value = null }
    fun showMessage(text: String) { _message.value = text }

    fun startServer() = ServerService.start(getApplication())
    fun stopServer() = ServerService.stop(getApplication())

    fun setPort(port: Int) {
        container.settings.update { it.copy(port = port) }
        if (serverRunning.value) {
            stopServer()
            _message.value = "Port changed: start the server again"
        }
    }

    fun setListenOnNetwork(enabled: Boolean) {
        if (enabled == settings.value.listenOnNetwork) return
        container.settings.update { it.copy(listenOnNetwork = enabled) }
        if (serverRunning.value) {
            stopServer()
            _message.value = "Network access changed: start the server again"
        }
    }

    fun regenerateApiKey() = container.settings.regenerateApiKey()

    /** Writes the indexers and settings to [uri], a document the user picked. */
    fun writeBackup(uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val text = Backup.encode(container.settings.settings.value, container.indexers.indexers.value)
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw IllegalStateException("can't write to the chosen file")
                    out.use { it.write(text.toByteArray()) }
                }
                "Backup saved"
            } catch (e: Exception) {
                "Backup failed: ${e.message}"
            }
        }
    }

    /** Reads and checks a backup; null (with a message) if it can't be used. */
    suspend fun readBackup(uri: Uri): BackupFile? = withContext(Dispatchers.IO) {
        try {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("can't read the chosen file")
            // a backup is a few KB: refuse anything huge instead of loading it into memory
            val bytes = input.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    require(out.size() <= MAX_BACKUP_BYTES) { "Not a Phonarr backup" }
                }
                out.toByteArray()
            }
            Backup.decode(String(bytes))
        } catch (e: Exception) {
            _message.value = "Restore failed: ${e.message}"
            null
        }
    }

    /** Replaces the indexers and settings with the backup's. */
    fun restore(backup: BackupFile) {
        val old = container.settings.settings.value
        container.indexers.replaceAll(backup.indexers)
        container.searchService.forgetAll()
        container.settings.update { backup.settings.copy(lastDefinitionsUpdate = it.lastDefinitionsUpdate) }
        if (serverRunning.value && (backup.settings.port != old.port || backup.settings.listenOnNetwork != old.listenOnNetwork)) {
            stopServer()
            _message.value = "Restored ${backup.indexers.size} indexers. Server address changed: start the server again"
        } else {
            _message.value = "Restored ${backup.indexers.size} indexers"
        }
    }

    fun setOpenSiteIn(browser: com.github.livingwithhippos.phonarr.core.data.SiteBrowser) =
        container.settings.update { it.copy(openSiteIn = browser) }

    fun setDohEnabled(enabled: Boolean) = updateDns { it.copy(dohEnabled = enabled) }
    fun setDohProvider(provider: DohProvider) = updateDns { it.copy(dohProvider = provider) }
    fun setDohCustomUrl(url: String) = updateDns { it.copy(dohCustomUrl = url) }

    /** Saves a DNS setting and drops open connections, so the next requests resolve names again. */
    private fun updateDns(f: (AppSettings) -> AppSettings) {
        container.settings.update(f)
        // closing TLS sockets writes to the network, which Android forbids on the main thread
        viewModelScope.launch(Dispatchers.IO) { container.okHttp.connectionPool.evictAll() }
    }

    private val _testingDoh = MutableStateFlow(false)
    val testingDoh: StateFlow<Boolean> = _testingDoh.asStateFlow()

    /** Resolves a well-known name with the current DNS settings and reports the result. */
    fun testDoh() {
        if (_testingDoh.value) return
        _testingDoh.value = true
        viewModelScope.launch {
            val s = settings.value
            _message.value = withContext(Dispatchers.IO) {
                runCatching { SwitchableDns { s }.lookup(DOH_TEST_HOST) }.fold(
                    onSuccess = { "$DOH_TEST_HOST resolved to ${it.first().hostAddress}" },
                    onFailure = { it.message ?: it::class.simpleName },
                )
            }
            _testingDoh.value = false
        }
    }

    fun setThemeMode(mode: ThemeMode) = container.settings.update { it.copy(themeMode = mode) }
    fun setThemeColor(color: ThemeColor) = container.settings.update { it.copy(themeColor = color) }
    fun setStandardFont(enabled: Boolean) = container.settings.update { it.copy(standardFont = enabled) }

    fun updateDefinitions() {
        if (_updating.value) return
        _updating.value = true
        viewModelScope.launch {
            try {
                val count = container.definitions.update()
                container.settings.update { it.copy(lastDefinitionsUpdate = System.currentTimeMillis()) }
                _message.value = "Downloaded $count definitions"
            } catch (e: Exception) {
                _message.value = "Definition update failed: ${e.message}"
            } finally {
                _updating.value = false
            }
        }
    }

    suspend fun loadDefinition(id: String): CardigannDefinition? = withContext(Dispatchers.IO) {
        runCatching { container.definitions.get(id) }.onFailure { _message.value = it.message }.getOrNull()
    }

    fun saveIndexer(config: IndexerConfig, test: Boolean) {
        val saved = container.indexers.save(config)
        container.searchService.forget(saved.id)
        if (test) testIndexer(saved) else _message.value = "Saved ${saved.name}"
    }

    fun setEnabled(config: IndexerConfig, enabled: Boolean) {
        container.indexers.save(config.copy(enabled = enabled))
    }

    fun deleteIndexer(config: IndexerConfig) {
        container.indexers.delete(config.id)
        container.searchService.forget(config.id)
        _message.value = "Deleted ${config.name}"
    }

    fun testIndexer(config: IndexerConfig) {
        _testing.value = _testing.value + config.id
        viewModelScope.launch {
            try {
                val n = withContext(Dispatchers.IO) { container.searchService.test(config) }
                _challenges.value = _challenges.value - config.id
                _message.value = "${config.name}: OK, $n results"
            } catch (e: ChallengeException) {
                _challenges.value = _challenges.value + (config.id to e.url)
                _message.value = "${config.name}: ${e.message}"
            } catch (e: Exception) {
                _message.value = "${config.name}: ${e.message ?: e::class.simpleName}"
            } finally {
                _testing.value = _testing.value - config.id
            }
        }
    }

    fun runSearch(query: String, indexerId: String) {
        _search.value = _search.value.copy(running = true)
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    container.searchService.search(indexerId, SearchQuery(q = query.ifBlank { null }))
                }
                _search.value = SearchState(false, outcome.releases.sortedByDescending { it.seeders ?: 0 }, outcome.errors, true)
            } catch (e: Exception) {
                _search.value = SearchState(false, emptyList(), mapOf("Search" to (e.message ?: "error")), true)
            }
        }
    }

    /**
     * The release's magnet link: the one from the results, one built from its info hash, or the
     * one its download link leads to (some sites only show it on the details page).
     */
    fun setDetailedResults(enabled: Boolean) = container.settings.update { it.copy(detailedResults = enabled) }

    /** The result shown on the detail screen, or null for the result list. */
    private val _selectedRelease = MutableStateFlow<ReleaseInfo?>(null)
    val selectedRelease: StateFlow<ReleaseInfo?> = _selectedRelease.asStateFlow()

    fun openRelease(release: ReleaseInfo) { _selectedRelease.value = release }
    fun closeRelease() { _selectedRelease.value = null }

    /**
     * The magnet or the .torrent file for [release]. A known magnet or info hash needs no network;
     * otherwise, or with [preferMagnet] false, the download link is fetched with the indexer's cookies and login.
     */
    suspend fun fetchRelease(release: ReleaseInfo, preferMagnet: Boolean = true): ReleaseFile? = withContext(Dispatchers.IO) {
        if (preferMagnet) knownMagnet(release)?.let { return@withContext ReleaseFile.Magnet(it) }
        val link = release.downloadUrl ?: return@withContext null
        if (link.startsWith("magnet:", ignoreCase = true)) return@withContext ReleaseFile.Magnet(link)
        try {
            when (val r = container.searchService.download(release.indexerId, link)) {
                is DownloadResult.MagnetLink -> ReleaseFile.Magnet(r.uri)
                is DownloadResult.Torrent -> {
                    val name = torrentFileName(r.fileName, release.title)
                    val dir = container.torrentsDir.apply { mkdirs() }
                    val file = File(dir, name)
                    file.writeBytes(r.bytes)
                    ReleaseFile.Torrent(file, name)
                }
            }
        } catch (e: Exception) {
            _message.value = "Download failed: ${e.message}"
            null
        }
    }

    /** Copies a downloaded torrent to a place the user picked. */
    fun saveTorrent(file: File, target: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _message.value = try {
                getApplication<Application>().contentResolver.openOutputStream(target)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                "Saved"
            } catch (e: Exception) {
                "Save failed: ${e.message}"
            }
        }
    }

    suspend fun resolveMagnet(release: ReleaseInfo): String? {
        knownMagnet(release)?.let { return it }
        return resolveDownload(release)?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
    }

    suspend fun resolveDownload(release: ReleaseInfo): String? = withContext(Dispatchers.IO) {
        release.magnetUrl?.let { return@withContext it }
        val link = release.downloadUrl ?: return@withContext null
        try {
            when (val r = container.searchService.download(release.indexerId, link)) {
                is com.github.livingwithhippos.phonarr.core.cardigann.DownloadResult.MagnetLink -> r.uri
                is com.github.livingwithhippos.phonarr.core.cardigann.DownloadResult.Torrent -> link
            }
        } catch (e: Exception) {
            _message.value = "Download failed: ${e.message}"
            null
        }
    }
}

/** The magnet link a result already carries (magnet field, info hash, or a magnet download link), without any network. */
fun knownMagnet(release: ReleaseInfo): String? =
    release.magnetUrl
        ?: release.infoHash?.takeIf { it.isNotBlank() }?.let {
            com.github.livingwithhippos.phonarr.core.util.Magnet.buildPublicMagnetLink(it, release.title)
        }
        ?: release.downloadUrl?.takeIf { it.startsWith("magnet:", ignoreCase = true) }

/** What a search result downloads as: a magnet link or a cached .torrent file. */
sealed interface ReleaseFile {
    data class Magnet(val uri: String) : ReleaseFile
    data class Torrent(val file: File, val name: String) : ReleaseFile
}

/** Backups are a few KB; anything larger than this isn't one. */
private const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
