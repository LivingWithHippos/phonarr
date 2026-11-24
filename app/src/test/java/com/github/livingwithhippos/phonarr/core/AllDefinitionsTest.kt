package com.github.livingwithhippos.phonarr.core

import com.github.livingwithhippos.phonarr.core.cardigann.CardigannIndexer
import com.github.livingwithhippos.phonarr.core.cardigann.CardigannDefinition
import com.github.livingwithhippos.phonarr.core.cardigann.DefinitionParser
import com.github.livingwithhippos.phonarr.core.cardigann.FilterBlock
import com.github.livingwithhippos.phonarr.core.cardigann.Regexes
import com.github.livingwithhippos.phonarr.core.torznab.SearchQuery
import com.github.livingwithhippos.phonarr.core.torznab.SearchType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Loads every real Prowlarr definition and builds search requests for each search mode.
 * Run with -Pphonarr.defsDir=/path/to/extracted/package (skipped otherwise).
 */
class AllDefinitionsTest {
    @Test
    fun loadAllDefinitions() {
        val dir = System.getProperty("phonarr.defsDir")?.let(::File)
        assumeTrue("phonarr.defsDir not set", dir != null && dir.isDirectory)
        val client = HttpClient(MockEngine { respondOk("") })
        val failures = mutableListOf<String>()
        val files = dir!!.listFiles { f -> f.name.endsWith(".yml") }!!.sortedBy { it.name }
        var unsupported = 0
        var patterns = 0
        val unsupportedRegexes = mutableListOf<String>()
        for (f in files) {
            try {
                val def = DefinitionParser.parse(f.readText(), f.nameWithoutExtension)
                val idx = CardigannIndexer(def, emptyMap(), client)
                if (idx.unsupportedReason() != null) unsupported++
                idx.capabilities.toXml()
                for (type in SearchType.entries) {
                    idx.buildRequests(SearchQuery(type = type, q = "test query", season = 1, ep = "2", imdbId = "tt0111161", categories = listOf(2000, 5000)))
                }
                idx.buildRequests(SearchQuery())
                // every regex the definition uses must survive Android's stricter engine after translation
                for (filter in allFilters(def)) {
                    val pattern = when (filter.name) {
                        "regexp" -> filter.argString
                        "re_replace" -> filter.arg(0)
                        else -> null
                    } ?: continue
                    patterns++
                    val translated = Regexes.translate(pattern)
                    if (runCatching { Regex(translated) }.isFailure) {
                        // .NET-only syntax: skipped at runtime, must not throw
                        unsupportedRegexes += "${f.name}: $pattern"
                        check(Regexes.compileOrNull(pattern) == null)
                        continue
                    }
                    IcuBraces.firstBareBrace(translated)?.let { throw IllegalStateException("pattern '$pattern' -> '$translated' has a bare brace at $it") }
                }
            } catch (e: Exception) {
                failures += "${f.name}: ${e::class.simpleName}: ${e.message?.take(200)}"
            }
        }
        println("Definitions: ${files.size}, failures: ${failures.size}, unsupported login: $unsupported, regexes checked: $patterns, unsupported regexes: ${unsupportedRegexes.size}")
        unsupportedRegexes.forEach { println("  unsupported regex in $it") }
        failures.forEach { println("  $it") }
        assertTrue("${failures.size} definitions failed", failures.size <= files.size / 50)
    }

    private fun allFilters(d: CardigannDefinition): List<FilterBlock> {
        val s = d.search
        return listOfNotNull(
            s.keywordsfilters, s.preprocessingfilters, s.rows.filters, s.rows.dateheaders?.filters, s.rows.count?.filters,
        ).flatten() +
            s.fields.flatMap { it.second.filters.orEmpty() } +
            d.download?.selectors.orEmpty().flatMap { it.filters.orEmpty() } +
            listOfNotNull(d.download?.infohash?.hash?.filters, d.download?.infohash?.title?.filters).flatten()
    }
}
