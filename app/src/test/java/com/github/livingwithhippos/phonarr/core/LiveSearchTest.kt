package com.github.livingwithhippos.phonarr.core

import com.github.livingwithhippos.phonarr.core.cardigann.CardigannIndexer
import com.github.livingwithhippos.phonarr.core.cardigann.DefinitionParser
import com.github.livingwithhippos.phonarr.core.torznab.SearchQuery
import com.github.livingwithhippos.phonarr.core.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Searches real sites. Manual check only:
 * ./gradlew :app:testDebugUnitTest --tests '*LiveSearchTest*' -Pphonarr.defsDir=... -Pphonarr.liveSites=nyaasi,eztv
 */
class LiveSearchTest {
    @Test
    fun liveSearch() = runBlocking {
        val dir = System.getProperty("phonarr.defsDir")?.let(::File)
        val sites = System.getProperty("phonarr.liveSites")?.split(',')?.filter { it.isNotBlank() }
        assumeTrue(dir != null && !sites.isNullOrEmpty())
        Log.sink = { level, msg, t -> if (level >= Log.Level.WARN) println("$level $msg ${t?.message ?: ""}") }
        val client = HttpClient(OkHttp) { followRedirects = false; expectSuccess = false }
        for (site in sites!!) {
            val file = File(dir, "$site.yml")
            if (!file.exists()) { println("LIVE $site: no definition"); continue }
            val def = DefinitionParser.parse(file.readText(), site)
            val idx = CardigannIndexer(def, emptyMap(), client)
            for (q in listOf(SearchQuery(), SearchQuery(q = "ubuntu"))) {
                try {
                    val r = idx.search(q)
                    println("LIVE $site q=${q.q}: ${r.size} results")
                    r.firstOrNull { it.magnetUrl == null && it.downloadUrl != null }?.let { rel ->
                        val d = runCatching { idx.download(rel.downloadUrl!!) }
                        println("   DOWNLOAD ${rel.downloadUrl?.take(60)} -> " + d.fold({ res ->
                            when (res) {
                                is com.github.livingwithhippos.phonarr.core.cardigann.DownloadResult.MagnetLink -> "magnet ${res.uri.take(60)}"
                                is com.github.livingwithhippos.phonarr.core.cardigann.DownloadResult.Torrent -> "torrent ${res.bytes.size} bytes"
                            }
                        }, { "ERROR ${it.message}" }))
                    }
                    r.take(3).forEach { println("   ${it.title} | ${it.size} | S${it.seeders} | ${it.publishDate} | ${it.categories.map { c -> c.id }} | ${(it.magnetUrl ?: it.downloadUrl)?.take(70)}") }
                } catch (e: Exception) {
                    println("LIVE $site q=${q.q}: ERROR ${e.message?.take(300)}")
                }
            }
        }
    }
}
