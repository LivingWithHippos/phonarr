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
    /** resolve site names with DNS over HTTPS instead of the network's DNS */
    val dohEnabled: Boolean = false,
    val dohProvider: DohProvider = DohProvider.Cloudflare,
    /** endpoint used when [dohProvider] is [DohProvider.Custom] */
    val dohCustomUrl: String = "",
)

/** DNS over HTTPS providers. [bootstrap] are the provider's IPs, so its own name needs no DNS lookup. */
@Serializable
enum class DohProvider(val label: String, val url: String?, val bootstrap: List<String>) {
    Cloudflare("Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111")),
    Google("Google", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888")),
    Quad9("Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe")),
    AdGuard("AdGuard", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff")),
    Mullvad("Mullvad", "https://dns.mullvad.net/dns-query", listOf("194.242.2.2", "2a07:e340::2")),

    /** the user's own endpoint; its host is looked up with the system DNS */
    Custom("Custom", null, emptyList()),
}

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
