package com.github.livingwithhippos.phonarr.core.data

import kotlinx.serialization.Serializable

/** An indexer the user added, based on a Cardigann definition. */
@Serializable
data class IndexerConfig(
    val id: Int,
    val definitionId: String,
    val name: String,
    val enabled: Boolean = true,
    val baseUrl: String? = null,
    val settings: Map<String, String> = emptyMap(),
)

@Serializable
data class AppSettings(
    val apiKey: String,
    val port: Int = 9797,
    val lastDefinitionsUpdate: Long = 0,
    /** where the indexer screen's "Open site" button opens the site */
    val openSiteIn: SiteBrowser = SiteBrowser.InApp,
)

@Serializable
enum class SiteBrowser {
    /** Phonarr's WebView: cookies it gets (e.g. a passed Cloudflare check) are reused by searches */
    InApp,

    /** the browser installed on the phone */
    External,
}

/** Short description of a definition, used to list available indexers without parsing every file. */
@Serializable
data class DefinitionSummary(
    val id: String,
    val name: String,
    val description: String? = null,
    val type: String? = null,
    val language: String? = null,
    val links: List<String> = emptyList(),
    val unsupportedReason: String? = null,
    /** null in indexes saved by older versions, until the next definitions update */
    val needsLogin: Boolean? = null,
) {
    /** Whether the site needs an account; falls back to the site type for old indexes. */
    val requiresLogin: Boolean get() = needsLogin ?: (type != "public")
}
