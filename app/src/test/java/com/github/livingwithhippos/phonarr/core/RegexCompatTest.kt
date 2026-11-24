package com.github.livingwithhippos.phonarr.core

import com.github.livingwithhippos.phonarr.core.cardigann.Regexes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regexes with ICU, which rejects `{` / `}` that aren't part of a `{n,m}`
 * quantifier or a `\p{...}` property. Desktop Java accepts them, so plain JVM tests can't
 * catch this: [IcuBraces] applies that rule explicitly.
 */
object IcuBraces {
    private val quantifier = Regex("\\{\\d+(,\\d*)?\\}")

    /** Index of the first brace ICU would reject, or null. */
    fun firstBareBrace(pattern: String): Int? {
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    val next = pattern.getOrNull(i + 1)
                    if ((next == 'p' || next == 'P' || next == 'x' || next == 'N') && pattern.getOrNull(i + 2) == '{') {
                        val end = pattern.indexOf('}', i + 3)
                        if (end < 0) return i + 2
                        i = end + 1
                        continue
                    }
                    i += 2
                    continue
                }
                c == '[' && !inClass -> inClass = true
                c == ']' && inClass -> inClass = false
                c == '{' && !inClass -> {
                    val q = quantifier.matchAt(pattern, i) ?: return i
                    i += q.value.length
                    continue
                }
                c == '}' && !inClass -> return i
            }
            i++
        }
        return null
    }
}

class RegexCompatTest {
    @Test
    fun translateEscapesLiteralBraces() {
        assertEquals("a\\{b\\}", Regexes.translate("a{b}"))
        assertEquals("\\{\\{\\s*x\\s*\\}\\}", Regexes.translate("{{\\s*x\\s*}}"))
        assertEquals("\\d{2,3}x{4}y{1,}", Regexes.translate("\\d{2,3}x{4}y{1,}"))
        assertEquals("\\p{L}+", Regexes.translate("\\p{L}+"))
        assertEquals("[\\p{InCJKUnifiedIdeographs}\\W]+", Regexes.translate("[\\p{IsCJKUnifiedIdeographs}\\W]+"))
        assertEquals("[{|}~]", Regexes.translate("[{|}~]"))
        listOf("a{b}", "{{ x }}", "\\d{2,3}", "x{,2}").forEach {
            val t = Regexes.translate(it)
            assertEquals("translate($it) = $t", null, IcuBraces.firstBareBrace(t))
            Regex(t) // still valid on the JVM
        }
        // meaning is unchanged
        assertTrue(Regexes.compile("^id{(\\d+)}$").matches("id{42}"))
    }

    /** Every Regex("...") literal in the app's source must be accepted by Android's engine. */
    @Test
    fun sourceRegexesAreAndroidSafe() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java") }.firstOrNull { it.isDirectory }
            ?: File("src/main/java").takeIf { it.isDirectory }
            ?: error("source folder not found")
        val literal = Regex("Regex\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
        val problems = mutableListOf<String>()
        var count = 0
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            literal.findAll(file.readText()).forEach { m ->
                val pattern = unescapeKotlin(m.groupValues[1])
                count++
                IcuBraces.firstBareBrace(pattern)?.let { problems += "${file.name}: $pattern (at $it)" }
            }
        }
        assertTrue("no regex literals found", count > 10)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun unescapeKotlin(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'u' -> { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(s[i]); i++
            }
        }
        return sb.toString()
    }
}
