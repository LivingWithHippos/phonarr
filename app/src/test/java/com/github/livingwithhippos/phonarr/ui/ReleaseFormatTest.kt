package com.github.livingwithhippos.phonarr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ReleaseFormatTest {
    private val now = ZonedDateTime.of(2026, 11, 27, 12, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun sizes() {
        assertEquals("512 B", humanSize(512))
        assertEquals("1.5 KB", humanSize(1536))
        assertEquals("7.9 GB", humanSize(8_500_000_000))
    }

    @Test
    fun ages() {
        assertEquals("just now", relativeAge(now.minusSeconds(20), now))
        assertEquals("just now", relativeAge(now.plusHours(1), now))
        assertEquals("1 minute ago", relativeAge(now.minusMinutes(1), now))
        assertEquals("5 hours ago", relativeAge(now.minusHours(5), now))
        assertEquals("3 days ago", relativeAge(now.minusDays(3), now))
        assertEquals("2 months ago", relativeAge(now.minusDays(65), now))
        assertEquals("1 year ago", relativeAge(now.minusDays(400), now))
    }

    @Test
    fun seedHealthThresholds() {
        assertEquals(SeedHealth.Dead, seedHealth(null))
        assertEquals(SeedHealth.Dead, seedHealth(0))
        assertEquals(SeedHealth.Low, seedHealth(9))
        assertEquals(SeedHealth.Good, seedHealth(10))
    }

    @Test
    fun links() {
        assertEquals("https://www.imdb.com/title/tt0133093/", imdbLink(133093))
    }

    @Test
    fun volumeFactors() {
        assertNull(downloadFactorLabel(null))
        assertNull(downloadFactorLabel(1.0))
        assertEquals("Freeleech", downloadFactorLabel(0.0))
        assertEquals("50% download", downloadFactorLabel(0.5))
        assertNull(uploadFactorLabel(1.0))
        assertEquals("2× upload", uploadFactorLabel(2.0))
        assertEquals("1.5× upload", uploadFactorLabel(1.5))
    }

    @Test
    fun durations() {
        assertEquals("2 days", humanDuration(172_800))
        assertEquals("36 hours", humanDuration(129_600))
        assertEquals("30 minutes", humanDuration(1_800))
    }

    @Test
    fun torrentFileNames() {
        assertEquals("Some.Show.S01E01.torrent", torrentFileName("Some.Show.S01E01.torrent", null))
        assertEquals("evil.torrent", torrentFileName("../../evil.torrent", null))
        assertEquals("The Matrix_ 1999 [1080p].torrent", torrentFileName(null, "The Matrix: 1999 [1080p]"))
        assertEquals("release.torrent", torrentFileName("", "  "))
        assertEquals("release.torrent", torrentFileName("...", null))
    }
}
