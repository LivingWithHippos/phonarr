package com.github.livingwithhippos.phonarr.core.cardigann

import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.nodes.Tag

/**
 * Minimal YAML tree. Maps keep duplicate keys and their order, which Cardigann relies on
 * for search fields (Prowlarr's KeyValuePairList).
 */
sealed interface YNode {
    data class Scalar(val value: String?) : YNode
    data class YList(val items: List<YNode>) : YNode
    data class YMap(val entries: List<Pair<String, YNode>>) : YNode {
        /** Case-insensitive lookup (YamlDotNet maps camelCase names; definitions are not always consistent). */
        operator fun get(key: String): YNode? =
            entries.lastOrNull { it.first.equals(key, ignoreCase = true) }?.second
    }
}

object Yaml {
    private val settings = LoadSettings.builder()
        .setAllowDuplicateKeys(true)
        .setMaxAliasesForCollections(200)
        .setCodePointLimit(10 * 1024 * 1024)
        .build()

    fun parse(text: String): YNode? {
        val node = Compose(settings).composeString(text).orElse(null) ?: return null
        return convert(node, HashMap())
    }

    private fun convert(node: Node, seen: MutableMap<Node, YNode>): YNode {
        seen[node]?.let { return it }
        val result = when (node) {
            is ScalarNode -> YNode.Scalar(if (node.tag == Tag.NULL) null else node.value)
            is SequenceNode -> YNode.YList(node.value.map { convert(it, seen) })
            is MappingNode -> {
                val entries = mutableListOf<Pair<String, YNode>>()
                val merges = mutableListOf<YNode.YMap>()
                for (tuple in node.value) {
                    val key = (tuple.keyNode as? ScalarNode)?.value ?: continue
                    val value = convert(tuple.valueNode, seen)
                    if (key == "<<") {
                        when (value) {
                            is YNode.YMap -> merges += value
                            is YNode.YList -> value.items.filterIsInstance<YNode.YMap>()
                                .forEach { merges += it }

                            else -> {}
                        }
                    } else {
                        entries += key to value
                    }
                }
                for (m in merges) {
                    for (e in m.entries) if (entries.none { it.first == e.first }) entries += e
                }
                YNode.YMap(entries)
            }

            else -> YNode.Scalar(null)
        }
        seen[node] = result
        return result
    }
}

// ---- helpers used by the definition mapper ----

internal fun YNode?.str(): String? = (this as? YNode.Scalar)?.value

internal fun YNode?.bool(default: Boolean = false): Boolean =
    when (this.str()?.lowercase()) {
        "true", "yes", "on", "y" -> true
        "false", "no", "off", "n" -> false
        else -> default
    }

internal fun YNode?.strList(): List<String>? = when (this) {
    is YNode.YList -> items.mapNotNull { it.str() }
    is YNode.Scalar -> value?.let { listOf(it) }
    else -> null
}

internal fun YNode?.strMap(): Map<String, String?>? =
    (this as? YNode.YMap)?.entries?.associate { it.first to it.second.str() }

internal fun YNode?.pairs(): List<Pair<String, String?>>? =
    (this as? YNode.YMap)?.entries?.map { it.first to it.second.str() }

internal fun YNode?.listMap(): Map<String, List<String>>? =
    (this as? YNode.YMap)?.entries?.associate { it.first to (it.second.strList() ?: emptyList()) }

internal fun <T> YNode?.mapList(f: (YNode.YMap) -> T): List<T>? =
    (this as? YNode.YList)?.items?.filterIsInstance<YNode.YMap>()?.map(f)

internal val YNode?.map: YNode.YMap? get() = this as? YNode.YMap
