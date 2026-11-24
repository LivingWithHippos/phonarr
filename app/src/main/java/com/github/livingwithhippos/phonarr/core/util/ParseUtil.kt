package com.github.livingwithhippos.phonarr.core.util

import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Port of Prowlarr's NzbDrone.Core.Parser.ParseUtil. */
object ParseUtil {

    private val imdbIdRegex = Regex("^(?:tt)?(\\d{1,8})$")

    private fun digitsOnly(s: String): String = s.filter { it.isDigit() || it == '.' || it == ',' }.trim()

    fun coerceDouble(str: String?): Double {
        var v = digitsOnly(str ?: "")
        v = if (v.isEmpty()) "0" else v.replace(",", ".")
        if (v.count { it == '.' } > 1) {
            val last = v.lastIndexOf('.')
            v = v.substring(0, last).replace(".", "") + v.substring(last)
        }
        if (v == ".") return 0.0
        return v.toDouble()
    }

    fun coerceFloat(str: String?): Float = coerceDouble(str).toFloat()

    fun coerceLong(str: String?): Long {
        val v = digitsOnly(str ?: "")
        if (v.isEmpty()) return 0
        if (v.contains(',') && v.contains('.')) {
            // e.g. "1,234.5": drop thousand separators, truncate the fraction
            return v.replace(",", "").toDouble().toLong()
        }
        // .NET int.Parse with NumberStyles.Any treats both "," and "." here as thousand separators
        return v.replace(".", "").replace(",", "").ifEmpty { "0" }.toLong()
    }

    fun coerceInt(str: String?): Int = coerceLong(str).toInt()

    fun tryCoerceInt(str: String?): Int? = runCatching { coerceInt(str) }.getOrNull()

    fun getLongFromString(str: String?): Long? {
        if (str.isNullOrBlank()) return null
        val sb = StringBuilder()
        for (c in str) {
            if (c !in '0'..'9') {
                if (sb.isNotEmpty()) break
                continue
            }
            sb.append(c)
        }
        return coerceLong(sb.toString())
    }

    fun getImdbId(value: String?): Int? {
        if (value == null) return null
        val m = imdbIdRegex.find(value) ?: return null
        return m.groupValues[1].toInt()
    }

    fun getFullImdbId(value: String?): String? {
        val id = getImdbId(value)
        if (id == null || id == 0) return null
        return "tt" + id.toString().padStart(7, '0')
    }

    fun getArgumentFromQueryString(url: String?, argument: String?): String? {
        if (url == null || argument == null) return null
        val parts = url.split("?", limit = 2)
        if (parts.size < 2) return null
        val qs = parts[1].split("#", limit = 2)[0]
        return parseQuery(qs)[argument]?.firstOrNull()
    }

    fun parseQuery(query: String, charset: Charset = StandardCharsets.UTF_8): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        query.removePrefix("?").split("&").filter { it.isNotEmpty() }.forEach { pair ->
            val kv = pair.split("=", limit = 2)
            val key = URLDecoder.decode(kv[0], charset)
            val value = if (kv.size > 1) URLDecoder.decode(kv[1], charset) else ""
            result.getOrPut(key) { mutableListOf() }.add(value)
        }
        return result
    }

    fun getBytes(str: String): Long {
        val unit = str.filter { it.isLetter() }
        val value = coerceDouble(str)
        return getBytes(unit, value)
    }

    private fun getBytes(unit: String, value: Double): Long {
        val u = unit.replace("i", "").lowercase()
        return when {
            u.contains("kb") -> (value * 1024.0).toLong()
            u.contains("mb") -> (value * 1024.0 * 1024.0).toLong()
            u.contains("gb") -> (value * 1024.0 * 1024.0 * 1024.0).toLong()
            u.contains("tb") -> (value * 1024.0 * 1024.0 * 1024.0 * 1024.0).toLong()
            else -> value.toLong()
        }
    }
}
