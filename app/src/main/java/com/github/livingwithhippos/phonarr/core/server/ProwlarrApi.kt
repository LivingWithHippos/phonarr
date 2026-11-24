package com.github.livingwithhippos.phonarr.core.server

import com.github.livingwithhippos.phonarr.core.torznab.ReleaseInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Facts about the running app, for Prowlarr's system status. Built by the app so the server has no Android calls. */
data class ServerInfo(
    val appVersion: String = "0",
    val osVersion: String = "",
    val appDataPath: String = "",
    val buildTime: Instant = Instant.EPOCH,
)

/**
 * JSON shapes of Prowlarr's v1 REST API (Prowlarr.Api.V1), for apps that talk to Prowlarr
 * directly instead of through Torznab. Prowlarr serializes camelCase names and camelCase enum strings.
 */
object ProwlarrApi {
    /** Prowlarr major version whose API these endpoints follow; apps compare it to decide what to call. */
    const val API_VERSION = "2.0.0.0"

    val json = Json { encodeDefaults = true; explicitNulls = true }

    @Serializable
    data class Message(val message: String)

    /** Port of SystemResource. */
    @Serializable
    data class SystemStatus(
        val appName: String = "Prowlarr",
        val instanceName: String = "Phonarr",
        val version: String = API_VERSION,
        val phonarrVersion: String,
        val buildTime: String,
        val isDebug: Boolean = false,
        val isProduction: Boolean = true,
        val isAdmin: Boolean = false,
        val isUserInteractive: Boolean = false,
        val startupPath: String,
        val appData: String,
        val osName: String = "android",
        val osVersion: String,
        val isNetCore: Boolean = true,
        val isLinux: Boolean = true,
        val isOsx: Boolean = false,
        val isWindows: Boolean = false,
        val isDocker: Boolean = false,
        val isContainerized: Boolean = false,
        val mode: String = "console",
        val branch: String = "master",
        val databaseType: String = "sqLite",
        val databaseVersion: String = "0.0",
        val authentication: String = "external",
        val migrationVersion: Int = 0,
        val urlBase: String = "",
        val runtimeVersion: String = "0.0",
        val runtimeName: String = "phonarr",
        val startTime: String,
        val packageVersion: String,
        val packageAuthor: String = "Phonarr",
        val packageUpdateMechanism: String = "external",
        val packageUpdateMechanismMessage: String? = null,
    )

    @Serializable
    data class Category(val id: Int, val name: String?, val description: String? = null, val subCategories: List<Category> = emptyList())

    /** Port of ReleaseResource. */
    @Serializable
    data class Release(
        val id: Int = 0,
        val guid: String?,
        val age: Int,
        val ageHours: Double,
        val ageMinutes: Double,
        val size: Long,
        val files: Int?,
        val grabs: Int?,
        val indexerId: Int,
        val indexer: String?,
        val subGroup: String? = null,
        val releaseHash: String? = null,
        val title: String?,
        val sortTitle: String?,
        val imdbId: Int,
        val tmdbId: Int,
        val tvdbId: Int,
        val tvMazeId: Int,
        val publishDate: String,
        val commentUrl: String?,
        val downloadUrl: String?,
        val infoUrl: String?,
        val posterUrl: String?,
        val indexerFlags: List<String>,
        val categories: List<Category>,
        val magnetUrl: String?,
        val infoHash: String?,
        val seeders: Int?,
        val leechers: Int?,
        val protocol: String = "torrent",
        val fileName: String,
    )

    fun status(info: ServerInfo, startTime: Instant) = SystemStatus(
        phonarrVersion = info.appVersion,
        buildTime = iso(info.buildTime),
        startupPath = info.appDataPath,
        appData = info.appDataPath,
        osVersion = info.osVersion,
        startTime = iso(startTime),
        packageVersion = info.appVersion,
    )

    /** ReleaseResourceMapper.ToResource; [downloadUrl] is the proxied link, as Prowlarr returns. */
    fun release(r: ReleaseInfo, downloadUrl: String?, now: Instant = Instant.now()): Release {
        val published = r.publishDate?.toInstant() ?: now
        val age = Duration.between(published, now).let { if (it.isNegative) Duration.ZERO else it }
        val minutes = age.seconds / 60.0
        return Release(
            guid = r.guid,
            age = age.toDays().toInt(),
            ageHours = minutes / 60.0,
            ageMinutes = minutes,
            size = r.size ?: 0,
            files = r.files,
            grabs = r.grabs,
            indexerId = r.indexerId,
            indexer = r.indexerName,
            title = r.title,
            sortTitle = r.title?.let(::normalizeTitle),
            imdbId = r.imdbId,
            tmdbId = r.tmdbId,
            tvdbId = r.tvdbId,
            tvMazeId = r.tvMazeId,
            publishDate = iso(published),
            commentUrl = r.commentUrl,
            downloadUrl = downloadUrl,
            infoUrl = r.infoUrl,
            posterUrl = r.posterUrl,
            indexerFlags = r.flags.toList(),
            categories = r.categories.map { c -> Category(c.id, c.name, subCategories = c.subCategories.map { Category(it.id, it.name) }) },
            magnetUrl = r.magnetUrl,
            infoHash = r.infoHash,
            seeders = r.seeders,
            leechers = r.peers?.let { p -> r.seeders?.let { s -> p - s } },
            fileName = "${r.title}.torrent",
        )
    }

    private val wordDelimiter = Regex("(\\s|\\.|,|_|-|=|'|\\|)+")
    // .NET's \w is Unicode; spelled out so accented letters survive on the JVM too
    private val punctuation = Regex("[^\\p{L}\\p{N}\\p{Mn}_\\s]")
    private val commonWords = Regex("\\b(a|an|the|and|or|of)\\b\\s?", RegexOption.IGNORE_CASE)
    private val duplicateSpaces = Regex("\\s{2,}")
    private val specialChars = Regex("(\\&|\\:|\\\\|\\/)+")

    /** Prowlarr's StringUtil.NormalizeTitle, used for sortTitle. */
    fun normalizeTitle(title: String): String = title
        .replace(wordDelimiter, " ")
        .replace(punctuation, "")
        .replace(commonWords, "")
        .replace(duplicateSpaces, " ")
        .replace(specialChars, "")
        .trim()
        .lowercase()

    private fun iso(i: Instant) = i.truncatedTo(ChronoUnit.SECONDS).toString()
}
