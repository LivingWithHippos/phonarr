package com.github.livingwithhippos.phonarr.core.data

import com.github.livingwithhippos.phonarr.core.cardigann.CardigannIndexer
import com.github.livingwithhippos.phonarr.core.cardigann.DownloadResult
import com.github.livingwithhippos.phonarr.core.torznab.IndexerCapabilities
import com.github.livingwithhippos.phonarr.core.torznab.ReleaseInfo
import com.github.livingwithhippos.phonarr.core.torznab.SearchQuery
import com.github.livingwithhippos.phonarr.core.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

class IndexerNotFoundException(id: String) : Exception("Indexer $id not found or disabled")

/**
 * Turns class-loading errors (e.g. a pattern failing in a static initializer) into normal
 * exceptions, so one broken indexer shows an error instead of crashing the app.
 */
internal inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (e: LinkageError) {
    throw IllegalStateException("Internal error: ${e.cause?.message ?: e.message ?: e::class.simpleName}", e)
}

data class SearchOutcome(val releases: List<ReleaseInfo>, val errors: Map<String, String>, val searched: Int = 0) {
    /** Every searched indexer failed (as opposed to finding nothing). */
    val allFailed: Boolean get() = searched > 0 && errors.size >= searched
}

/** Creates indexer instances from saved configs and runs searches/downloads against them. */
class SearchService(
    private val indexers: IndexerRepository,
    private val definitions: DefinitionRepository,
    private val client: HttpClient,
    private val timeoutMs: Long = 90_000,
    private val userAgent: String = com.github.livingwithhippos.phonarr.core.cardigann.IndexerHttp.DEFAULT_USER_AGENT,
    private val solver: com.github.livingwithhippos.phonarr.core.cardigann.ChallengeSolver? = null,
) {
    private val instances = HashMap<Int, Pair<IndexerConfig, CardigannIndexer>>()

    @Synchronized
    fun instance(config: IndexerConfig): CardigannIndexer {
        instances[config.id]?.let { (cfg, idx) -> if (cfg == config) return idx }
        val idx = guarded { CardigannIndexer(definitions.get(config.definitionId), config.settings, client, config.baseUrl, userAgent, solver) }
        instances[config.id] = config to idx
        return idx
    }

    @Synchronized
    fun forget(id: Int) {
        instances.remove(id)
    }

    @Synchronized
    fun forgetAll() {
        instances.clear()
    }

    /** "all" means every enabled indexer, otherwise a numeric indexer id. */
    fun resolve(id: String): List<IndexerConfig> {
        val enabled = indexers.indexers.value.filter { it.enabled }
        if (id.equals("all", true)) return enabled
        val n = id.toIntOrNull() ?: throw IndexerNotFoundException(id)
        return listOf(enabled.firstOrNull { it.id == n } ?: throw IndexerNotFoundException(id))
    }

    fun capabilities(id: String): IndexerCapabilities {
        val configs = resolve(id)
        if (!id.equals("all", true)) return instance(configs.single()).capabilities
        return IndexerCapabilities.merge(configs.mapNotNull { runCatching { instance(it).capabilities }.getOrNull() })
    }

    suspend fun search(id: String, query: SearchQuery): SearchOutcome = searchConfigs(resolve(id), query)

    /**
     * Searches the given enabled indexers, or all of them when [ids] is null or empty
     * (Prowlarr's `indexerIds`). Throws [IndexerNotFoundException] for an id that isn't enabled.
     */
    suspend fun search(ids: List<Int>?, query: SearchQuery): SearchOutcome {
        val enabled = indexers.indexers.value.filter { it.enabled }
        val configs = if (ids.isNullOrEmpty()) {
            enabled
        } else {
            ids.distinct().map { n -> enabled.firstOrNull { it.id == n } ?: throw IndexerNotFoundException(n.toString()) }
        }
        return searchConfigs(configs, query)
    }

    private suspend fun searchConfigs(configs: List<IndexerConfig>, query: SearchQuery): SearchOutcome = coroutineScope {
        val results = configs.map { config ->
            async {
                try {
                    val idx = instance(config)
                    val releases = withTimeout(timeoutMs) { guarded { idx.search(query) } }
                    releases.forEach {
                        it.indexerId = config.id
                        it.indexerName = config.name
                        it.indexerPrivacy = idx.definition.privacy
                    }
                    config.name to Result.success(releases)
                } catch (e: Exception) {
                    Log.e("Search failed for ${config.name}: ${e.message}", e)
                    config.name to Result.failure(e)
                }
            }
        }.awaitAll()

        var releases = results.flatMap { it.second.getOrNull().orEmpty() }
        // filter by requested categories when the indexer returned category info
        if (query.categories.isNotEmpty()) {
            val wanted = query.categories.flatMap { c -> listOf(c) + if (c % 1000 == 0) (c + 1..c + 999).toList() else emptyList() }.toSet()
            releases = releases.filter { r -> r.categories.isEmpty() || r.categories.any { it.id in wanted } }
        }
        releases = releases.sortedByDescending { it.publishDate?.toEpochSecond() ?: 0 }
        val offset = query.offset ?: 0
        val limit = query.limit ?: Int.MAX_VALUE
        SearchOutcome(
            releases.drop(offset).take(limit),
            results.filter { it.second.isFailure }.associate { it.first to (it.second.exceptionOrNull()?.message ?: "error") },
            searched = configs.size,
        )
    }

    suspend fun download(indexerId: Int, link: String): DownloadResult {
        val config = resolve(indexerId.toString()).single()
        return guarded { instance(config).download(link) }
    }

    /** Runs an empty search to check the indexer works. Returns the number of results. */
    suspend fun test(config: IndexerConfig): Int {
        forget(config.id)
        val idx = guarded { CardigannIndexer(definitions.get(config.definitionId), config.settings, client, config.baseUrl, userAgent, solver) }
        return withTimeout(timeoutMs) { guarded { idx.search(SearchQuery()) } }.size
    }
}
