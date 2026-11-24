package com.github.livingwithhippos.phonarr.core

import com.github.livingwithhippos.phonarr.core.util.DateTimeUtil
import com.github.livingwithhippos.phonarr.core.util.Magnet
import com.github.livingwithhippos.phonarr.core.util.ParseUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UtilTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            DateTimeUtil.zone = ZoneId.of("UTC")
            DateTimeUtil.clock = { ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneId.of("UTC")) }
        }
    }

    @Test
    fun sizes() {
        assertEquals(1536L * 1024 * 1024, ParseUtil.getBytes("1.5 GB"))
        assertEquals(1536L * 1024 * 1024, ParseUtil.getBytes("1,5 GiB"))
        assertEquals(700L * 1024 * 1024, ParseUtil.getBytes("700 MB"))
        assertEquals(1234L, ParseUtil.getBytes("1234"))
    }

    @Test
    fun numbers() {
        assertEquals(1234, ParseUtil.coerceInt("1,234"))
        assertEquals(0, ParseUtil.coerceInt("-"))
        assertEquals(12, ParseUtil.coerceInt(" 12 seeders"))
        assertEquals(0.5, ParseUtil.coerceDouble("0.5"), 0.0)
        assertEquals(1234567L, ParseUtil.getLongFromString("tt1234567"))
        assertEquals("tt0111161", ParseUtil.getFullImdbId("111161"))
    }

    @Test
    fun goLayouts() {
        val d = DateTimeUtil.parseDateTimeGoLang("2026-03-04 15:06:07", "2006-01-02 15:04:05")
        assertEquals("Wed, 04 Mar 2026 15:06:07 +0000", DateTimeUtil.format(d))
        val d2 = DateTimeUtil.parseDateTimeGoLang("Mar 4, 2026", "Jan 2, 2006")
        assertEquals(4, d2.dayOfMonth)
        assertEquals(3, d2.monthValue)
        val d3 = DateTimeUtil.parseDateTimeGoLang("04/03/2026 3:05 PM", "02/01/2006 3:04 PM")
        assertEquals(15, d3.hour)
        assertEquals(3, d3.monthValue)
    }

    @Test
    fun fuzzyDates() {
        assertEquals(2025, DateTimeUtil.fromUnknown("2025-01-02 10:11").year)
        assertEquals(10, DateTimeUtil.fromUnknown("Uploaded 2025-01-02 10:11 by someone").hour)
        assertEquals(19, DateTimeUtil.fromUnknown("yesterday 08:00").dayOfMonth)
        assertEquals(11, DateTimeUtil.fromUnknown("1 hour ago").hour)
        assertEquals(18, DateTimeUtil.fromUnknown("2 days ago").dayOfMonth)
        assertEquals(1700000000L, DateTimeUtil.fromUnknown("1700000000").toEpochSecond())
        assertEquals(5, DateTimeUtil.fromUnknown("5 Mar 2024").dayOfMonth)
        val rfc = DateTimeUtil.format(DateTimeUtil.fromUnknown("2024-05-06"))
        assertEquals(DateTimeUtil.fromUnknown(rfc).toInstant(), DateTimeUtil.fromUnknown("2024-05-06").toInstant())
    }

    @Test
    fun magnet() {
        val m = Magnet.buildPublicMagnetLink("abcdef0123456789abcdef0123456789abcdef01", "My Title")
        assertTrue(m.startsWith("magnet:?xt=urn:btih:ABCDEF0123456789ABCDEF0123456789ABCDEF01&dn=My%20Title&tr="))
        assertEquals("ABCDEF0123456789ABCDEF0123456789ABCDEF01", Magnet.getInfoHashFromMagnet(m))
    }
}
