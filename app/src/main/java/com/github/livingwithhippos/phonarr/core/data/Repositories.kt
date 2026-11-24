package com.github.livingwithhippos.phonarr.core.data

import com.github.livingwithhippos.phonarr.core.cardigann.CardigannDefinition
import com.github.livingwithhippos.phonarr.core.cardigann.CardigannIndexer
import com.github.livingwithhippos.phonarr.core.cardigann.DefinitionParser
import com.github.livingwithhippos.phonarr.core.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipInputStream

class SettingsRepository(dir: File) {
    private val store = JsonFileStore(File(dir, "settings.json"), AppSettings.serializer()) { AppSettings(newApiKey()) }
    private val _settings = MutableStateFlow(store.read().also { store.write(it) })
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(f: (AppSettings) -> AppSettings) {
        synchronized(this) {
            val next = f(_settings.value)
            store.write(next)
            _settings.value = next
        }
    }

    fun regenerateApiKey() = update { it.copy(apiKey = newApiKey()) }

    companion object {
        fun newApiKey(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

class IndexerRepository(dir: File) {
    private val store = JsonFileStore(File(dir, "indexers.json"), ListSerializer(IndexerConfig.serializer())) { emptyList() }
    private val _indexers = MutableStateFlow(store.read())
    val indexers: StateFlow<List<IndexerConfig>> = _indexers.asStateFlow()

    fun get(id: Int): IndexerConfig? = _indexers.value.firstOrNull { it.id == id }

    @Synchronized
    fun save(config: IndexerConfig): IndexerConfig {
        val list = _indexers.value.toMutableList()
        val saved = if (config.id <= 0) config.copy(id = (list.maxOfOrNull { it.id } ?: 0) + 1) else config
        val idx = list.indexOfFirst { it.id == saved.id }
        if (idx >= 0) list[idx] = saved else list += saved
        store.write(list)
        _indexers.value = list
        return saved
    }

    /** Replaces every indexer, e.g. when restoring a backup. */
    @Synchronized
    fun replaceAll(list: List<IndexerConfig>) {
        store.write(list)
        _indexers.value = list
    }

    @Synchronized
    fun delete(id: Int) {
        val list = _indexers.value.filter { it.id != id }
        store.write(list)
        _indexers.value = list
    }
}

/**
 * Downloads Prowlarr's definition package and keeps the YAML files on disk,
 * like Prowlarr's IndexerDefinitionUpdateService.
 */
class DefinitionRepository(private val dir: File, private val client: HttpClient) {
    private val summariesStore = JsonFileStore(File(dir, "index.json"), ListSerializer(DefinitionSummary.serializer())) { emptyList() }
    private val _summaries = MutableStateFlow(summariesStore.read())
    val summaries: StateFlow<List<DefinitionSummary>> = _summaries.asStateFlow()
    private val cache = HashMap<String, CardigannDefinition>()

    private val customDir get() = File(dir, "Custom")

    suspend fun update(): Int = withContext(Dispatchers.IO) {
        val resp = client.get(PACKAGE_URL)
        if (!resp.status.isSuccess()) throw IllegalStateException("Definition download failed: HTTP ${resp.status.value}")
        val bytes = resp.bodyAsBytes()
        dir.mkdirs()
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                // flat .yml files only: protects against path traversal in the archive
                if (entry.isDirectory || !name.endsWith(".yml") || name.startsWith(".") || name.contains("..")) continue
                File(dir, name).writeBytes(zip.readBytes())
                count++
            }
        }
        synchronized(cache) { cache.clear() }
        rebuildIndex()
        count
    }

    /** Parses every definition on disk and stores a lightweight index for the UI. */
    suspend fun rebuildIndex(): List<DefinitionSummary> = withContext(Dispatchers.IO) {
        val files = (dir.listFiles()?.toList().orEmpty() + customDir.listFiles()?.toList().orEmpty()).filter { it.name.endsWith(".yml") }
        val list = files.mapNotNull { f ->
            val id = f.nameWithoutExtension
            if (id in BLOCKLIST) return@mapNotNull null
            try {
                val def = DefinitionParser.parse(f.readText(), id)
                DefinitionSummary(
                    id = id, name = def.name, description = def.description, type = def.type,
                    language = def.language, links = def.links,
                    unsupportedReason = CardigannIndexer.unsupportedReason(def),
                    needsLogin = def.login != null,
                )
            } catch (e: Exception) {
                Log.w("Unable to parse definition $id: ${e.message}")
                null
            }
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        summariesStore.write(list)
        _summaries.value = list
        list
    }

    fun get(id: String): CardigannDefinition {
        synchronized(cache) { cache[id]?.let { return it } }
        val file = File(customDir, "$id.yml").takeIf { it.exists() } ?: File(dir, "$id.yml")
        if (!file.exists()) throw IllegalArgumentException("Indexer definition for '$id' does not exist. Update the definitions first.")
        val def = DefinitionParser.parse(file.readText(), id)
        synchronized(cache) { cache[id] = def }
        return def
    }

    companion object {
        const val DEFINITION_VERSION = 12
        const val PACKAGE_URL = "https://indexers.prowlarr.com/master/$DEFINITION_VERSION/package.zip"

        // definitions Prowlarr replaced with C# implementations
        val BLOCKLIST = setOf(
            "aither", "animeworld", "audiobookbay", "beyond-hd-oneurl", "beyond-hd", "blutopia", "brsociety", "danishbytes",
            "datascene", "desitorrents", "hdbits", "lat-team", "mteamtp", "mteamtp2fa", "reelflix", "shareisland",
            "skipthecommercials", "tellytorrent",
        )
    }
}
