package com.github.livingwithhippos.phonarr.core.cardigann

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Small subset of Newtonsoft's SelectToken path syntax used by Cardigann definitions:
 * `a.b`, `a[0]`, `a['b c']`, `$.a`, `a[*]` is not supported.
 */
object JsonPath {
    private sealed interface Step {
        data class Prop(val name: String) : Step
        data class Index(val i: Int) : Step
    }

    private fun parse(path: String): List<Step> {
        val steps = mutableListOf<Step>()
        var i = 0
        val p = path.trim()
        if (p.startsWith("$")) i = 1
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                steps += Step.Prop(sb.toString())
                sb.clear()
            }
        }
        while (i < p.length) {
            when (val c = p[i]) {
                '.' -> { flush(); i++ }
                '[' -> {
                    flush()
                    val end = p.indexOf(']', i)
                    require(end > i) { "Unclosed [ in JSON path $path" }
                    val inner = p.substring(i + 1, end).trim()
                    if ((inner.startsWith("'") && inner.endsWith("'")) || (inner.startsWith("\"") && inner.endsWith("\""))) {
                        steps += Step.Prop(inner.substring(1, inner.length - 1))
                    } else {
                        steps += Step.Index(inner.toInt())
                    }
                    i = end + 1
                }
                else -> { sb.append(c); i++ }
            }
        }
        flush()
        return steps
    }

    fun select(root: JsonElement?, path: String): JsonElement? {
        if (root == null) return null
        if (path.isBlank()) return root
        var cur: JsonElement = root
        for (step in parse(path)) {
            cur = when (step) {
                is Step.Prop -> (cur as? JsonObject)?.get(step.name) ?: return null
                is Step.Index -> (cur as? JsonArray)?.getOrNull(step.i) ?: return null
            }
        }
        return cur
    }

    /** Equivalent of Newtonsoft's token.Value<string>(); booleans become "True"/"False" like .NET. */
    fun asString(e: JsonElement?): String? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull?.let { if (it) "True" else "False" } ?: e.content
        else -> e.toString()
    }
}
