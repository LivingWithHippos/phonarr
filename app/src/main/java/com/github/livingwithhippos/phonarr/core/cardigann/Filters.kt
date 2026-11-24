package com.github.livingwithhippos.phonarr.core.cardigann

import com.github.livingwithhippos.phonarr.core.util.DateTimeUtil
import com.github.livingwithhippos.phonarr.core.util.InvalidDateException
import com.github.livingwithhippos.phonarr.core.util.Log
import com.github.livingwithhippos.phonarr.core.util.ParseUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Base64

/** Port of CardigannBase.ApplyFilters. */
class Filters(
    private val definitionId: String,
    private val encoding: Charset,
    private val applyTemplate: (String?, Map<String, Any?>) -> String?,
) {
    fun apply(input: String?, filters: List<FilterBlock>?, variables: Map<String, Any?>): String? {
        if (filters == null) return input
        var data = input
        for (filter in filters) {
            val d = data ?: ""
            data = when (filter.name) {
                "querystring" -> ParseUtil.getArgumentFromQueryString(d, filter.argString)
                "timeparse", "dateparse" -> try {
                    DateTimeUtil.format(DateTimeUtil.parseDateTimeGoLang(d, filter.argString ?: ""))
                } catch (e: InvalidDateException) {
                    Log.d("$definitionId: ${e.message}")
                    d
                }
                "regexp" -> Regexes.compileOrNull(filter.argString ?: "")?.find(d)?.groups?.let { g -> if (g.size > 1) g[1]?.value ?: "" else "" } ?: ""
                "re_replace" -> {
                    val replacement = applyTemplate(filter.arg(1) ?: "", variables) ?: ""
                    Regexes.compileOrNull(filter.arg(0) ?: "")?.replace(d, Regexes.dotNetReplacement(replacement)) ?: d
                }
                "split" -> {
                    val sep = filter.arg(0) ?: " "
                    val parts = d.split(sep[0])
                    var pos = (filter.arg(1) ?: "0").trim().toInt()
                    if (pos < 0) pos += parts.size
                    parts[pos]
                }
                "replace" -> d.replace(filter.arg(0) ?: "", applyTemplate(filter.arg(1) ?: "", variables) ?: "")
                "trim" -> filter.argString?.takeIf { it.isNotEmpty() }?.let { d.trim(it[0]) } ?: d.trim()
                "prepend" -> (applyTemplate(filter.argString ?: "", variables) ?: "") + d
                "append" -> d + (applyTemplate(filter.argString ?: "", variables) ?: "")
                "tolower" -> d.lowercase()
                "toupper" -> d.uppercase()
                "base64decode" -> String(base64UrlDecode(d), Charsets.UTF_8)
                "base64encode" -> Base64.getUrlEncoder().withoutPadding().encodeToString(d.toByteArray(Charsets.UTF_8))
                "urldecode" -> URLDecoder.decode(d, encoding)
                "urlencode" -> urlEncode(d, encoding)
                "htmldecode" -> Parser.unescapeEntities(d, false)
                "htmlencode" -> htmlEncode(d)
                "timeago", "reltime" -> DateTimeUtil.format(DateTimeUtil.fromTimeAgo(d))
                "fuzzytime" -> DateTimeUtil.format(DateTimeUtil.fromUnknown(d))
                "validfilename" -> d.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
                "diacritics" -> {
                    if (filter.argString != "replace") throw IllegalArgumentException("unsupported diacritics filter argument")
                    val decomposed = Normalizer.normalize(d, Normalizer.Form.NFD)
                    Normalizer.normalize(decomposed.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }, Normalizer.Form.NFC)
                }
                "jsonjoinarray" -> {
                    val token = JsonPath.select(Json.parseToJsonElement(d), filter.arg(0) ?: "")
                    (token as? JsonArray)?.joinToString(filter.arg(1) ?: "") { JsonPath.asString(it) ?: "" } ?: ""
                }
                "hexdump" -> {
                    Log.d("CardigannIndexer ($definitionId): strdump: " + d.map { "$it(${"%02X".format(it.code)})" }.joinToString(""))
                    d
                }
                "strdump" -> {
                    val tag = filter.argString?.let { "($it):" } ?: ":"
                    Log.d("CardigannIndexer ($definitionId): strdump$tag ${d.replace("\r", "\\r").replace("\n", "\\n").replace("\u00A0", "\\xA0")}")
                    d
                }
                "validate" -> {
                    val delimiters = Regex("[, /)(.;\\[\\]\"|:]")
                    val valid = (filter.argString ?: "").lowercase().split(delimiters).map { it.trim() }.filter { it.isNotEmpty() }
                    val have = d.lowercase().split(delimiters).map { it.trim() }.filter { it.isNotEmpty() }
                    valid.filter { it in have }.distinct().joinToString(", ")
                }
                else -> {
                    Log.e("CardigannIndexer ($definitionId): Unsupported field filter: ${filter.name}")
                    d
                }
            }
        }
        return data
    }

    companion object {
        /** Same output as .NET WebUtility.UrlEncode (spaces become '+'). */
        fun urlEncode(s: String?, charset: Charset = Charsets.UTF_8): String {
            if (s.isNullOrEmpty()) return ""
            val sb = StringBuilder()
            for (b in s.toByteArray(charset)) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                when {
                    ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-_.!*()" -> sb.append(ch)
                    ch == ' ' -> sb.append('+')
                    else -> sb.append('%').append("%02X".format(c))
                }
            }
            return sb.toString()
        }

        fun base64UrlDecode(s: String): ByteArray {
            var t = s.trim().replace('-', '+').replace('_', '/')
            while (t.length % 4 != 0) t += "="
            return Base64.getDecoder().decode(t)
        }

        fun htmlEncode(s: String): String {
            val sb = StringBuilder()
            for (ch in s) {
                when {
                    ch == '<' -> sb.append("&lt;")
                    ch == '>' -> sb.append("&gt;")
                    ch == '"' -> sb.append("&quot;")
                    ch == '\'' -> sb.append("&#39;")
                    ch == '&' -> sb.append("&amp;")
                    ch.code in 160..255 -> sb.append("&#").append(ch.code).append(';')
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
