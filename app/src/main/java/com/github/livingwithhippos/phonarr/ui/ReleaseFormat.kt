package com.github.livingwithhippos.phonarr.ui

import java.time.Duration
import java.time.ZonedDateTime
import java.util.Locale

/** Formatting for search results, shared by the result list and the detail screen. Pure, for unit tests. */

fun humanSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", v, units[i])
}

/** "just now", "5 minutes ago", "3 days ago", "2 years ago"... */
fun relativeAge(date: ZonedDateTime, now: ZonedDateTime = ZonedDateTime.now()): String {
    val d = Duration.between(date, now)
    if (d.isNegative || d.toMinutes() < 1) return "just now"
    fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit ago" else "$n ${unit}s ago"
    return when {
        d.toHours() < 1 -> plural(d.toMinutes(), "minute")
        d.toDays() < 1 -> plural(d.toHours(), "hour")
        d.toDays() < 30 -> plural(d.toDays(), "day")
        d.toDays() < 365 -> plural(d.toDays() / 30, "month")
        else -> plural(d.toDays() / 365, "year")
    }
}

enum class SeedHealth { Dead, Low, Good }

fun seedHealth(seeders: Int?): SeedHealth = when {
    seeders == null || seeders <= 0 -> SeedHealth.Dead
    seeders < 10 -> SeedHealth.Low
    else -> SeedHealth.Good
}

fun imdbLink(id: Int): String = String.format(Locale.US, "https://www.imdb.com/title/tt%07d/", id)

/** "Freeleech", "50% download", or null when downloads count fully. */
fun downloadFactorLabel(factor: Double?): String? = when {
    factor == null || factor >= 1.0 -> null
    factor <= 0.0 -> "Freeleech"
    else -> "${(factor * 100).toInt()}% download"
}

/** "2× upload", or null for normal upload credit. */
fun uploadFactorLabel(factor: Double?): String? = when {
    factor == null || factor == 1.0 -> null
    factor % 1.0 == 0.0 -> "${factor.toInt()}× upload"
    else -> "${factor}× upload"
}

/** Seconds as "36 hours", "3 days"... */
fun humanDuration(seconds: Long): String {
    val d = Duration.ofSeconds(seconds)
    fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"
    return when {
        d.toDays() >= 2 -> plural(d.toDays(), "day")
        d.toHours() >= 1 -> plural(d.toHours(), "hour")
        else -> plural(d.toMinutes(), "minute")
    }
}

/**
 * A safe file name for a downloaded torrent: the server's name, else the title, else "release",
 * without path separators or odd characters, ending in .torrent.
 */
fun torrentFileName(fileName: String?, title: String?): String {
    val base = (fileName?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: "release")
        .substringAfterLast('/').substringAfterLast('\\')
        .removeSuffix(".torrent")
        .replace(Regex("""[^\p{L}\p{N} ._()\[\]-]"""), "_")
        .trim(' ', '.')
        .take(120)
        .ifEmpty { "release" }
    return "$base.torrent"
}
