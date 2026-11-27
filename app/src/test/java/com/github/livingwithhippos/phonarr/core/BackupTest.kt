package com.github.livingwithhippos.phonarr.core

import com.github.livingwithhippos.phonarr.core.data.AppSettings
import com.github.livingwithhippos.phonarr.core.data.Backup
import com.github.livingwithhippos.phonarr.core.data.IndexerConfig
import com.github.livingwithhippos.phonarr.core.data.IndexerRepository
import com.github.livingwithhippos.phonarr.core.data.SiteBrowser
import com.github.livingwithhippos.phonarr.core.data.ThemeColor
import com.github.livingwithhippos.phonarr.core.data.ThemeMode
import com.github.livingwithhippos.phonarr.core.data.listenHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class BackupTest {
    @Test
    fun listenOnNetworkDefaultsOffAndPicksHost() {
        val old = Backup.decode("""{"app":"Phonarr","version":1,"settings":{"apiKey":"abc","port":1234}}""")
        assertFalse(old.settings.listenOnNetwork)
        assertEquals(ThemeMode.SYSTEM, old.settings.themeMode)
        assertEquals(ThemeColor.SYSTEM, old.settings.themeColor)
        assertFalse(old.settings.standardFont)
        assertFalse(old.settings.detailedResults)
        assertEquals("127.0.0.1", old.settings.listenHost)
        assertEquals("0.0.0.0", old.settings.copy(listenOnNetwork = true).listenHost)

        val roundTrip = Backup.decode(Backup.encode(AppSettings("k", listenOnNetwork = true), emptyList()))
        assertTrue(roundTrip.settings.listenOnNetwork)
    }

    private val settings = AppSettings(apiKey = "abc123", port = 9898, lastDefinitionsUpdate = 42, openSiteIn = SiteBrowser.External)
    private val indexers = listOf(
        IndexerConfig(1, "nyaasi", "Nyaa"),
        IndexerConfig(2, "someprivate", "Private", enabled = false, baseUrl = "https://p.example/", settings = mapOf("username" to "me", "password" to "s3cr\"t")),
    )

    @Test
    fun roundTripsEverything() {
        val text = Backup.encode(settings, indexers, Instant.parse("2026-11-24T18:00:00Z"))
        assertTrue(text, text.contains("\"app\": \"Phonarr\"") && text.contains("\"created\": \"2026-11-24T18:00:00Z\""))
        val back = Backup.decode(text)
        assertEquals(settings, back.settings)
        assertEquals(indexers, back.indexers)
        assertEquals(1, back.version)
    }

    @Test
    fun ignoresUnknownFields() {
        val text = Backup.encode(settings, indexers).replaceFirst("{", "{\n  \"future\": [1, 2],")
        assertEquals(indexers, Backup.decode(text).indexers)
    }

    @Test
    fun rejectsOtherFiles() {
        fun message(text: String) = assertThrows(IllegalArgumentException::class.java) { Backup.decode(text) }.message
        assertEquals("Not a Phonarr backup", message("hello"))
        assertEquals("Not a Phonarr backup", message("[]"))
        assertEquals("Not a Phonarr backup", message("""{"app": "Other", "version": 1, "settings": {"apiKey": "x"}}"""))
        assertEquals("Not a Phonarr backup", message("""{"app": "Phonarr", "version": 1}"""))
        assertEquals(
            "Backup made by a newer version of Phonarr",
            message("""{"app": "Phonarr", "version": 2, "settings": {"apiKey": "x"}}"""),
        )
    }

    @Test
    fun replaceAllPersists() {
        val dir = Files.createTempDirectory("phonarr").toFile()
        try {
            IndexerRepository(dir).apply { save(IndexerConfig(0, "old", "Old")) }.replaceAll(indexers)
            assertEquals(indexers, IndexerRepository(dir).indexers.value)
        } finally {
            dir.deleteRecursively()
        }
    }
}
