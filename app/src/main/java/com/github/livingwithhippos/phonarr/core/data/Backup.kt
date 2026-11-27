package com.github.livingwithhippos.phonarr.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant

/** A backup of the user's indexers and app settings. Credentials are stored in plain text. */
@Serializable
data class BackupFile(
    val app: String = "",
    val version: Int = 0,
    val created: String? = null,
    val settings: AppSettings,
    val indexers: List<IndexerConfig> = emptyList(),
)

object Backup {
    const val APP = "Phonarr"
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun encode(
        settings: AppSettings,
        indexers: List<IndexerConfig>,
        now: Instant = Instant.now()
    ): String =
        json.encodeToString(
            BackupFile.serializer(),
            BackupFile(APP, VERSION, now.toString(), settings, indexers)
        )

    /** Reads a backup, rejecting files that aren't Phonarr backups or come from a newer version. */
    fun decode(text: String): BackupFile {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Not a Phonarr backup", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Not a Phonarr backup", e)
        }
        require(file.app == APP) { "Not a Phonarr backup" }
        require(file.version <= VERSION) { "Backup made by a newer version of Phonarr" }
        return file
    }
}
