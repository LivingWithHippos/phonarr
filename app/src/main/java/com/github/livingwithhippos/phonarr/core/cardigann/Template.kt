package com.github.livingwithhippos.phonarr.core.cardigann

import com.github.livingwithhippos.phonarr.core.util.Log

/**
 * Port of CardigannBase.ApplyGoTemplateText: a regex based implementation of the small
 * Go template subset that Cardigann definitions use. It deliberately follows Prowlarr's
 * behaviour (including its quirks) so definitions behave the same way.
 */
object Template {
    private val logicFunctions = listOf("and", "or", "eq", "ne")
    private val stringLiteralFunctions = listOf("eq", "ne")

    private val logicRegex = Regex("\\b(and|or|eq|ne)(?:\\s+(\\(?\\.[^)\\s]+\\)?|\"[^\"]+\")){2,}")
    private val logicParamRegex = Regex("\\s+(\\(?\\.[^)\\s]+\\)?|\"[^\"]+\")")
    private val reReplaceRegex = Regex("\\{\\{\\s*re_replace\\s+(\\..+?)\\s+\"(.*?)\"\\s+\"(.*?)\"\\s*\\}\\}")
    private val joinRegex = Regex("\\{\\{\\s*join\\s+(\\..+?)\\s+\"(.*?)\"\\s*\\}\\}")
    private val ifElseRegex = Regex("\\{\\{\\s*if\\s*(.+?)\\s*\\}\\}(.*?)\\{\\{\\s*else\\s*\\}\\}(.*?)\\{\\{\\s*end\\s*\\}\\}")
    private val rangeRegex = Regex(
        "\\{\\{\\s*range\\s*(((?<index>\\$.+?),)((\\s*(?<element>.+?)\\s*(:=)\\s*)))?(?<variable>.+?)\\s*\\}\\}(?<prefix>.*?)\\{\\{\\.\\}\\}(?<postfix>.*?)\\{\\{end\\}\\}",
    )
    private val variableRegex = Regex("\\{\\{\\s*(\\..+?)\\s*\\}\\}")

    fun apply(template: String?, variables: Map<String, Any?>, modifier: ((String?) -> String?)? = null): String? {
        if (template.isNullOrBlank() || !template.contains("{{")) return template
        var t: String = template

        // {{ re_replace .Query.Keywords "[^a-zA-Z0-9]+" "%" }}
        for (m in reReplaceRegex.findAll(t).toList()) {
            val input = variables[m.groupValues[1]]?.toString() ?: ""
            var expanded: String? = Regexes.compileOrNull(m.groupValues[2])?.replace(input, Regexes.dotNetReplacement(m.groupValues[3])) ?: input
            if (modifier != null) expanded = modifier(expanded)
            t = t.replace(m.value, expanded ?: "")
        }

        // {{ join .Categories "," }}
        for (m in joinRegex.findAll(t).toList()) {
            val input = asList(variables[m.groupValues[1]])
            var expanded: String? = input.joinToString(m.groupValues[2])
            if (modifier != null) expanded = modifier(expanded)
            t = t.replace(m.value, expanded ?: "")
        }

        // logic functions, re-matched after each replacement to support nesting
        var logic = logicRegex.find(t)
        while (logic != null) {
            val start = logic.range.first
            var length = logic.value.length
            val function = logic.groupValues[1]

            // collect each parameter (Java has no access to repeated group captures)
            val params = mutableListOf<Pair<String, Int>>() // value, absolute index
            var pos = start + function.length
            while (true) {
                val pm = logicParamRegex.find(t, pos) ?: break
                if (pm.range.first != pos || pm.range.last >= logic.range.last + 1) break
                val g = pm.groups[1]!!
                params += g.value to g.range.first
                pos = pm.range.last + 1
            }
            var values = params.map { it.first.trim('(', ')') }
            if (function !in stringLiteralFunctions) values = values.filter { !it.startsWith("\"") }

            var result = ""
            when (function) {
                "and", "or" -> {
                    val isAnd = function == "and"
                    for (p in values) {
                        result = p
                        if ((variables[p] as? String).isNullOrBlank() == isAnd) break
                    }
                }
                "eq", "ne" -> {
                    val wantEqual = function == "eq"
                    if (params.size > 2) length = params[2].second - start
                    val isEqual = values.take(2).map {
                        if (it.startsWith("\"")) it.trim('"') else variables[it] as? String
                    }.distinct().size == 1
                    result = if (isEqual == wantEqual) ".True" else ".False"
                }
            }
            t = t.substring(0, start) + result + t.substring(start + length)
            logic = logicRegex.find(t)
        }

        // {{ if .X }}a{{ else }}b{{ end }}
        for (m in ifElseRegex.findAll(t).toList()) {
            val condition = m.groupValues[1]
            if (!condition.startsWith(".")) {
                throw UnsupportedOperationException("CardigannIndexer: Condition operation '$condition' not implemented")
            }
            val state = when (val v = variables[condition]) {
                null -> false
                is String -> v.isNotBlank()
                is Collection<*> -> v.isNotEmpty()
                else -> throw IllegalStateException("Unexpected type for variable $condition: ${v::class.java}")
            }
            t = t.replace(m.value, if (state) m.groupValues[2] else m.groupValues[3])
        }

        // {{ range .Categories }}cat[]={{.}}&{{end}}
        for (m in rangeRegex.findAll(t).toList()) {
            val index = m.groups["index"]?.value ?: ""
            val variable = m.groups["variable"]!!.value
            val prefix = m.groups["prefix"]!!.value
            val postfix = m.groups["postfix"]!!.value
            val indexReplace = "{{$index}}"
            val sb = StringBuilder()
            asList(variables[variable]).forEachIndexed { i, value ->
                val newValue = if (modifier != null) modifier(value) else value
                if (index.isNotBlank()) {
                    sb.append(prefix.replace(indexReplace, i.toString())).append(newValue)
                        .append(postfix.replace(indexReplace, i.toString()))
                } else {
                    sb.append(prefix).append(newValue).append(postfix)
                }
            }
            t = t.replace(m.value, sb.toString())
        }

        // {{ .Variable }}
        for (m in variableRegex.findAll(t).toList()) {
            var value = variables[m.groupValues[1]]?.let { if (it is Collection<*>) it.joinToString(" ") else it.toString() }
            if (modifier != null) value = modifier(value)
            t = t.replace(m.value, value ?: "")
        }

        return t
    }

    private fun asList(v: Any?): List<String> = when (v) {
        null -> emptyList()
        is Collection<*> -> v.map { it?.toString() ?: "" }
        else -> listOf(v.toString())
    }
}

object Regexes {
    private val cache = HashMap<String, Regex>()
    private val QUANTIFIER = Regex("\\{\\d+(,\\d*)?\\}")

    /** Converts a .NET regex replacement string to a Java one (backslashes are literal in .NET). */
    fun dotNetReplacement(s: String): String = s.replace("\\", "\\\\")

    /** Compiles a regex written for .NET, translating the syntax differences definitions use. */
    fun compile(pattern: String): Regex = synchronized(cache) {
        cache.getOrPut(pattern) { Regex(translate(pattern)) }
    }

    private val unsupported = HashSet<String>()

    /**
     * Like [compile], but returns null for patterns Java can't run (e.g. .NET balancing groups),
     * logging each one once, so one bad filter doesn't break a whole indexer.
     */
    fun compileOrNull(pattern: String): Regex? = try {
        compile(pattern)
    } catch (e: IllegalArgumentException) { // PatternSyntaxException
        if (synchronized(unsupported) { unsupported.add(pattern) }) {
            Log.w("Skipping unsupported regex \"$pattern\": ${e.message?.lineSequence()?.firstOrNull()}")
        }
        null
    }

    /**
     * - `\p{IsBlockName}` (.NET named blocks) becomes `\p{InBlockName}`
     * - braces that aren't a `{n,m}` quantifier are escaped (literal in .NET and desktop Java, an error on Android)
     * - an unescaped `[` inside a character class is literal in .NET but starts a nested class in Java
     */
    fun translate(pattern: String): String {
        val sb = StringBuilder()
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    val next = pattern[i + 1]
                    if ((next == 'p' || next == 'P') && i + 2 < pattern.length && pattern[i + 2] == '{') {
                        // \p{Name}: copy the whole property, renaming .NET's IsBlock to Java's InBlock
                        val end = pattern.indexOf('}', i + 3)
                        if (end > 0) {
                            val name = pattern.substring(i + 3, end)
                            sb.append('\\').append(next).append('{')
                                .append(if (name.startsWith("Is") && name.length > 2 && name[2].isUpperCase()) "In" + name.substring(2) else name)
                                .append('}')
                            i = end + 1
                            continue
                        }
                    }
                    sb.append(c).append(next)
                    i += 2
                    continue
                }
                // Android's ICU engine rejects braces that aren't part of a {n,m} quantifier
                c == '{' && !inClass -> {
                    val q = QUANTIFIER.matchAt(pattern, i)
                    if (q != null) {
                        sb.append(q.value)
                        i += q.value.length
                        continue
                    }
                    sb.append("\\{")
                }
                c == '}' && !inClass -> sb.append("\\}")
                c == '[' && inClass -> sb.append("\\[")
                c == '[' -> {
                    inClass = true
                    sb.append(c)
                    // a ']' right after '[' or '[^' is a literal
                    if (i + 1 < pattern.length && pattern[i + 1] == '^') { sb.append('^'); i++ }
                    if (i + 1 < pattern.length && pattern[i + 1] == ']') { sb.append("\\]"); i++ }
                }
                c == ']' && inClass -> { inClass = false; sb.append(c) }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
