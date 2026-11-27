package com.github.livingwithhippos.phonarr.core.net

import com.github.livingwithhippos.phonarr.core.data.AppSettings
import com.github.livingwithhippos.phonarr.core.data.DohProvider
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Resolves names with DNS over HTTPS when the settings ask for it, otherwise with the system DNS.
 * Settings are read on every lookup, so changes apply without rebuilding the HTTP client.
 * When DNS over HTTPS is on, a failed lookup fails: it never falls back to the system DNS.
 */
class SwitchableDns(
    private val system: Dns = Dns.SYSTEM,
    private val settings: () -> AppSettings,
) : Dns {
    private data class Resolver(val provider: DohProvider, val url: HttpUrl, val dns: Dns)

    @Volatile
    private var cached: Resolver? = null

    private val bootstrapClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val s = settings()
        if (!s.dohEnabled || isLocal(hostname)) return system.lookup(hostname)
        val resolver = resolver(s)
        return try {
            resolver.dns.lookup(hostname)
        } catch (e: Exception) {
            throw UnknownHostException(
                "DNS over HTTPS (${resolver.provider.label}) couldn't resolve $hostname: ${e.message ?: e::class.simpleName}",
            ).apply { initCause(e) }
        }
    }

    private fun resolver(s: AppSettings): Resolver {
        val url = endpoint(s.dohProvider, s.dohCustomUrl)
            ?: throw UnknownHostException("Custom DNS over HTTPS URL is invalid")
        cached?.takeIf { it.provider == s.dohProvider && it.url == url }?.let { return it }
        synchronized(this) {
            cached?.takeIf { it.provider == s.dohProvider && it.url == url }?.let { return it }
            val builder = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(url)
                .includeIPv6(true)
                // false would make OkHttp load its public suffix database, which isn't needed for indexer sites
                .resolvePrivateAddresses(true)
            if (s.dohProvider.bootstrap.isNotEmpty()) {
                builder.bootstrapDnsHosts(s.dohProvider.bootstrap.map(InetAddress::getByName))
            }
            return Resolver(s.dohProvider, url, builder.build()).also { cached = it }
        }
    }

    companion object {
        /** The provider's endpoint, or null when a custom URL isn't a valid https URL. */
        fun endpoint(provider: DohProvider, customUrl: String): HttpUrl? {
            val raw = provider.url ?: customUrl.trim()
            return raw.toHttpUrlOrNull()?.takeIf { it.isHttps }
        }

        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /** Names DNS over HTTPS can't or shouldn't resolve: IP literals and localhost. */
        private fun isLocal(hostname: String): Boolean =
            hostname.equals("localhost", ignoreCase = true) ||
                hostname.endsWith(".localhost", ignoreCase = true) ||
                IPV4.matches(hostname) ||
                ':' in hostname
    }
}
