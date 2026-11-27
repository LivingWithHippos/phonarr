package com.github.livingwithhippos.phonarr.core

import com.github.livingwithhippos.phonarr.core.data.AppSettings
import com.github.livingwithhippos.phonarr.core.data.Backup
import com.github.livingwithhippos.phonarr.core.data.DohProvider
import com.github.livingwithhippos.phonarr.core.net.SwitchableDns
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class DohDnsTest {
    private val fakeAddress = InetAddress.getByAddress("fake", byteArrayOf(10, 0, 0, 1))

    /** Records the names the "system" resolver was asked for. */
    private class FakeSystem(private val answer: InetAddress) : Dns {
        val asked = mutableListOf<String>()
        override fun lookup(hostname: String): List<InetAddress> { asked += hostname; return listOf(answer) }
    }

    @Test
    fun usesSystemDnsWhenDisabled() {
        val system = FakeSystem(fakeAddress)
        val dns = SwitchableDns(system) { AppSettings("key", dohEnabled = false) }
        assertEquals(listOf(fakeAddress), dns.lookup("example.com"))
        assertEquals(listOf("example.com"), system.asked)
    }

    @Test
    fun localNamesSkipDoh() {
        val system = FakeSystem(fakeAddress)
        val dns = SwitchableDns(system) { AppSettings("key", dohEnabled = true, dohProvider = DohProvider.Custom) }
        dns.lookup("localhost")
        dns.lookup("127.0.0.1")
        dns.lookup("::1")
        assertEquals(listOf("localhost", "127.0.0.1", "::1"), system.asked)
    }

    @Test
    fun invalidCustomUrlFailsWithoutFallback() {
        for (url in listOf("", "not a url", "http://dns.example.com/dns-query")) {
            val system = FakeSystem(fakeAddress)
            val dns = SwitchableDns(system) {
                AppSettings("key", dohEnabled = true, dohProvider = DohProvider.Custom, dohCustomUrl = url)
            }
            val e = runCatching { dns.lookup("example.com") }.exceptionOrNull()
            assertTrue("'$url' should fail", e is UnknownHostException)
            assertTrue(system.asked.isEmpty())
        }
    }

    @Test
    fun endpoints() {
        DohProvider.entries.filter { it != DohProvider.Custom }.forEach {
            assertNotNull(it.name, SwitchableDns.endpoint(it, ""))
            assertTrue(it.name, it.bootstrap.isNotEmpty())
        }
        assertNotNull(SwitchableDns.endpoint(DohProvider.Custom, " https://dns.example.com/dns-query "))
        assertNull(SwitchableDns.endpoint(DohProvider.Custom, "ftp://x"))
    }

    @Test
    fun oldSettingsDecodeWithDohOff() {
        val backup = Backup.decode("""{"app":"Phonarr","version":1,"settings":{"apiKey":"abc","port":1234}}""")
        assertFalse(backup.settings.dohEnabled)
        assertEquals(DohProvider.Cloudflare, backup.settings.dohProvider)
    }

    /** ./gradlew :app:testDebugUnitTest --tests '*DohDnsTest*' -Pphonarr.liveSites=doh */
    @Test
    fun liveProviders() {
        assumeTrue(System.getProperty("phonarr.liveSites")?.split(',')?.contains("doh") == true)
        DohProvider.entries.filter { it != DohProvider.Custom }.forEach { provider ->
            val dns = SwitchableDns(FakeSystem(fakeAddress)) { AppSettings("key", dohEnabled = true, dohProvider = provider) }
            val result = dns.lookup("github.com")
            println("${provider.label}: $result")
            assertTrue(provider.label, result.isNotEmpty() && result.none { it == fakeAddress })
        }
    }
}
