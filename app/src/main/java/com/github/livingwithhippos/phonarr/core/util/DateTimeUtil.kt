package com.github.livingwithhippos.phonarr.core.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAccessor
import java.util.Locale

class InvalidDateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Port of Prowlarr's DateTimeUtil. The C# version relies on the DateTimeRoutines library for
 * "fuzzy" parsing; here that is replaced by a list of common formats plus a date search in the text.
 */
object DateTimeUtil {

    /** Format used to pass dates between filters and fields (RFC 1123 with numeric offset). */
    val RFC1123Z: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)

    var zone: ZoneId = ZoneId.systemDefault()
    var clock: () -> ZonedDateTime = { ZonedDateTime.now(zone) }

    private val timeAgoRegex = Regex("(?i)\\bago")
    private val todayRegex = Regex("(?i)\\btoday(?:[\\s,]+(?:at)?\\s*|[\\s,]*|$)")
    private val tomorrowRegex = Regex("(?i)\\btomorrow(?:[\\s,]+(?:at)?\\s*|[\\s,]*|$)")
    private val yesterdayRegex = Regex("(?i)\\byesterday(?:[\\s,]+(?:at)?\\s*|[\\s,]*|$)")
    private val daysOfWeekRegex = Regex("(?i)\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+at\\s+")
    private val missingYearRegex = Regex("^(\\d{1,2}-\\d{1,2})(\\s|$)")
    private val missingYearRegex2 = Regex("^(\\d{1,2}\\s+\\w{3})\\s+(\\d{1,2}:\\d{1,2}.*)$")
    private val timeAgoPartRegex = Regex("\\s*?([\\d.]+)\\s*?([^\\d\\s.]+)\\s*?")

    fun format(date: ZonedDateTime): String = RFC1123Z.format(date)

    fun unixToDate(seconds: Long): ZonedDateTime = Instant.ofEpochSecond(seconds).atZone(zone)

    fun fromTimeAgo(input: String): ZonedDateTime {
        var str = input.lowercase()
        val now = clock()
        if (str.contains("now")) return now
        str = str.replace(",", "").replace("ago", "").replace("and", "")
        var seconds = 0.0
        for (m in timeAgoPartRegex.findAll(str)) {
            val v = ParseUtil.coerceDouble(m.groupValues[1])
            val unit = m.groupValues[2]
            seconds += v * when {
                unit.contains("sec") || unit == "s" -> 1.0
                unit.contains("min") || unit == "m" -> 60.0
                unit.contains("hour") || unit.contains("hr") || unit == "h" -> 3600.0
                unit.contains("day") || unit == "d" -> 86400.0
                unit.contains("week") || unit.contains("wk") || unit == "w" -> 7 * 86400.0
                unit.contains("month") || unit == "mo" -> 30 * 86400.0
                unit.contains("year") || unit == "y" -> 365 * 86400.0
                else -> throw InvalidDateException("TimeAgo parsing failed, unknown unit: $unit")
            }
        }
        return now.minusNanos((seconds * 1_000_000_000L).toLong())
    }

    fun fromUnknown(input: String, format: String? = null): ZonedDateTime {
        var str = input.trim()
        try {
            runCatching { return ZonedDateTime.parse(str, RFC1123Z) }

            if (str.isNotEmpty() && str.all { it.isDigit() }) {
                str.toLongOrNull()?.let { return unixToDate(it) }
            }

            if (str.lowercase().contains("now")) return clock()

            if (timeAgoRegex.containsMatchIn(str)) return fromTimeAgo(str)

            val today = clock().toLocalDate()
            todayRegex.find(str)?.let { m ->
                return today.atTime(parseTime(str.replace(m.value, ""))).atZone(zone)
            }
            yesterdayRegex.find(str)?.let { m ->
                return today.minusDays(1).atTime(parseTime(str.replace(m.value, ""))).atZone(zone)
            }
            tomorrowRegex.find(str)?.let { m ->
                return today.plusDays(1).atTime(parseTime(str.replace(m.value, ""))).atZone(zone)
            }
            daysOfWeekRegex.find(str)?.let { m ->
                val time = parseTime(str.replace(m.value, ""))
                val dow = DayOfWeek.valueOf(m.groupValues[1].uppercase())
                var d = today
                while (d.dayOfWeek != dow) d = d.minusDays(1)
                return d.atTime(time).atZone(zone)
            }

            missingYearRegex.find(str)?.let { m ->
                val date = m.groupValues[1]
                str = str.replace(date, "${today.year}-$date")
            }
            missingYearRegex2.find(str)?.let { m ->
                str = "${m.groupValues[1]} ${today.year} ${m.groupValues[2]}"
            }

            return fromFuzzyTime(str, format)
        } catch (e: Exception) {
            throw InvalidDateException("DateTime parsing failed for \"$input\": ${e.message}", e)
        }
    }

    private val timeRegex = Regex("(?i)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(am|pm)?")

    private fun parseTime(time: String): LocalTime {
        if (time.isBlank()) return LocalTime.MIDNIGHT
        val m = timeRegex.find(time) ?: throw InvalidDateException("Unable to parse time \"$time\"")
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val s = m.groupValues[3].ifEmpty { "0" }.toInt()
        when (m.groupValues[4].lowercase()) {
            "pm" -> if (h < 12) h += 12
            "am" -> if (h == 12) h = 0
        }
        return LocalTime.of(h, min, s)
    }

    // --- fuzzy parsing ---

    private val ordinalRegex = Regex("(?i)(\\d)(st|nd|rd|th)\\b")
    private val commonPatterns = listOf(
        "yyyy-M-d'T'H:m:s[.SSSSSSSSS][.SSSSSS][.SSS]XXX",
        "yyyy-M-d'T'H:m:s[.SSSSSSSSS][.SSSSSS][.SSS]",
        "yyyy-M-d H:m:s XX", "yyyy-M-d H:m:s XXX", "yyyy-M-d H:m:s", "yyyy-M-d H:m", "yyyy-M-d h:m a", "yyyy-M-d",
        "yyyy/M/d H:m:s", "yyyy/M/d H:m", "yyyy/M/d",
        "yyyy.M.d H:m:s", "yyyy.M.d H:m", "yyyy.M.d",
        "d.M.yyyy H:m:s", "d.M.yyyy H:m", "d.M.yyyy",
        "EEE, d MMM yyyy H:m:s Z", "EEE, d MMM yyyy H:m:s z", "EEE, d MMM yyyy H:m:s",
        "EEE MMM d H:m:s z yyyy", "EEE MMM d yyyy H:m:s",
        "d MMM yyyy H:m:s", "d MMM yyyy H:m", "d MMM yyyy h:m a", "d MMM yyyy",
        "d MMMM yyyy H:m:s", "d MMMM yyyy H:m", "d MMMM yyyy",
        "d-MMM-yyyy H:m", "d-MMM-yyyy", "d-MMM-yy",
        "MMM d yyyy H:m:s", "MMM d yyyy H:m", "MMM d yyyy h:m a", "MMM d yyyy",
        "MMMM d yyyy H:m", "MMMM d yyyy",
        "EEE d MMM yyyy", "EEEE d MMMM yyyy", "MMM yyyy",
    )
    private val usPatterns = listOf(
        "M/d/yyyy H:m:s", "M/d/yyyy h:m:s a", "M/d/yyyy H:m", "M/d/yyyy h:m a", "M/d/yyyy", "M/d/yy",
        "M-d-yyyy H:m", "M-d-yyyy",
    )
    private val ukPatterns = listOf(
        "d/M/yyyy H:m:s", "d/M/yyyy h:m:s a", "d/M/yyyy H:m", "d/M/yyyy h:m a", "d/M/yyyy", "d/M/yy",
        "d-M-yyyy H:m", "d-M-yyyy",
    )
    private val searchRegexes = listOf(
        Regex("\\d{4}-\\d{1,2}-\\d{1,2}(?:[ T]\\d{1,2}:\\d{2}(?::\\d{2})?)?"),
        Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{4}(?: \\d{1,2}:\\d{2}(?::\\d{2})?)?"),
        Regex("\\d{1,2}/\\d{1,2}/\\d{4}(?: \\d{1,2}:\\d{2}(?::\\d{2})?(?: ?[AaPp][Mm])?)?"),
        Regex("(?i)\\d{1,2} [a-z]{3,9} \\d{4}(?: \\d{1,2}:\\d{2}(?::\\d{2})?)?"),
        Regex("(?i)[a-z]{3,9} \\d{1,2} \\d{4}(?: \\d{1,2}:\\d{2}(?::\\d{2})?)?"),
    )

    private val formatterCache = HashMap<String, DateTimeFormatter>()

    private fun formatter(pattern: String): DateTimeFormatter = synchronized(formatterCache) {
        formatterCache.getOrPut(pattern) {
            DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH)
        }
    }

    fun fromFuzzyTime(input: String, format: String? = null): ZonedDateTime {
        var str = input.trim().replace(ordinalRegex, "$1").replace(",", " ").replace(Regex("\\s+"), " ")
        var forcedZone: ZoneId? = null
        Regex("(?i)\\s(utc|gmt)$").find(str)?.let {
            forcedZone = ZoneOffset.UTC
            str = str.substring(0, it.range.first)
        }
        val patterns = commonPatterns + if (format == "UK") ukPatterns else usPatterns
        fun attempt(s: String): ZonedDateTime? {
            for (p in patterns) {
                val parsed = runCatching { formatter(p).parse(s) }.getOrNull() ?: continue
                return toZoned(parsed, forcedZone)
            }
            return null
        }
        attempt(str)?.let { return it }
        // look for a known date shape inside a longer text
        for (r in searchRegexes) {
            val m = r.find(str) ?: continue
            attempt(m.value)?.let { return it }
        }
        throw InvalidDateException("FromFuzzyTime parsing failed for string $input")
    }

    private fun toZoned(t: TemporalAccessor, forcedZone: ZoneId?): ZonedDateTime {
        val now = clock()
        val year = when {
            t.isSupported(ChronoField.YEAR) -> t.get(ChronoField.YEAR)
            else -> now.year
        }
        val month = if (t.isSupported(ChronoField.MONTH_OF_YEAR)) t.get(ChronoField.MONTH_OF_YEAR) else 1
        val day = if (t.isSupported(ChronoField.DAY_OF_MONTH)) t.get(ChronoField.DAY_OF_MONTH) else 1
        val time = if (t.isSupported(ChronoField.HOUR_OF_DAY)) {
            LocalTime.of(
                t.get(ChronoField.HOUR_OF_DAY),
                if (t.isSupported(ChronoField.MINUTE_OF_HOUR)) t.get(ChronoField.MINUTE_OF_HOUR) else 0,
                if (t.isSupported(ChronoField.SECOND_OF_MINUTE)) t.get(ChronoField.SECOND_OF_MINUTE) else 0,
                if (t.isSupported(ChronoField.NANO_OF_SECOND)) t.get(ChronoField.NANO_OF_SECOND) else 0,
            )
        } else LocalTime.MIDNIGHT
        val ldt = LocalDateTime.of(LocalDate.of(year, month, day), time)
        val zoneId: ZoneId = forcedZone ?: runCatching { ZoneId.from(t) }.getOrNull() ?: zone
        return ldt.atZone(zoneId)
    }

    // --- Go layouts ---

    private val goTokens = listOf(
        "January" to "MMMM", "Jan" to "MMM", "Monday" to "EEEE", "Mon" to "EEE", "MST" to "z",
        "2006" to "yyyy", "-07:00:00" to "XXXXX", "-0700" to "xx", "-07:00" to "xxx", "-07" to "x",
        "Z07:00" to "XXX", "Z0700" to "XX", "Z07" to "X",
        ".000000000" to ".SSSSSSSSS", ".000000" to ".SSSSSS", ".000" to ".SSS", ".00" to ".SS", ".0" to ".S",
        ".999999999" to "[.SSSSSSSSS]", ".999999" to "[.SSSSSS]", ".999" to "[.SSS]", ".99" to "[.SS]", ".9" to "[.S]",
        "002" to "DDD", "_2" to "d", "06" to "yy", "01" to "MM", "02" to "dd", "15" to "HH", "03" to "hh",
        "04" to "mm", "05" to "ss", "PM" to "a", "pm" to "a",
        "1" to "M", "2" to "d", "3" to "h", "4" to "m", "5" to "s",
    )

    /** Converts a Go time layout (e.g. "2006-01-02 15:04") into a java.time pattern. */
    fun goLayoutToPattern(layout: String): String {
        val out = StringBuilder()
        val literal = StringBuilder()
        fun flushLiteral() {
            if (literal.isEmpty()) return
            val s = literal.toString()
            if (s.any { it.isLetter() || it == '\'' || it == '[' || it == ']' || it == '#' || it == '{' || it == '}' }) {
                out.append('\'').append(s.replace("'", "''")).append('\'')
            } else {
                out.append(s)
            }
            literal.clear()
        }
        var i = 0
        while (i < layout.length) {
            val token = goTokens.firstOrNull { layout.startsWith(it.first, i) }
            if (token != null) {
                flushLiteral()
                out.append(token.second)
                i += token.first.length
            } else {
                literal.append(layout[i])
                i++
            }
        }
        flushLiteral()
        return out.toString()
    }

    fun parseDateTimeGoLang(input: String, layout: String): ZonedDateTime {
        val date = input.trim()
        val lower = layout.lowercase()
        // definitions sometimes use .NET style formats instead of Go layouts
        if (listOf("y", "h", "d").any { lower.contains(it) }) {
            val pattern = layout.replace("tt", "a").replace("K", "XXX")
            runCatching { return toZoned(formatter(pattern).parse(date), null) }
        }
        val pattern = goLayoutToPattern(layout.replace("_2", "2"))
        try {
            return toZoned(formatter(pattern).parse(date.replace(Regex(" {2,}"), " ")), null)
        } catch (e: Exception) {
            throw InvalidDateException(
                "Error while parsing DateTime \"$date\", using layout \"$layout\" ($pattern): ${e.message}", e,
            )
        }
    }
}
