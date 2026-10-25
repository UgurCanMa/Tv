package de.ugur.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class M3uTest {
    private val sample = """
        #EXTM3U
        #EXTINF:-1 tvg-id="DasErste.de" tvg-name="Das Erste HD" tvg-logo="https://x/logo.png" group-title="DE | ARD & ZDF",Das Erste HD
        https://daserste-live.ard-mcdn.de/daserste/live/hls/de/master.m3u8
        #EXTINF:-1 tvg-id="" tvg-logo="" group-title="FILME | Neu",Ekel Alfred, das Ekel
        https://example.org/film.mp4
        #EXTINF:-1 tvg-id="" tvg-name="Folge 1" tvg-logo="" group-title="SERIEN | Der Bergdoktor",S18 E01 – Neubeginn
        #EXTVLCOPT:http-user-agent=Test
        https://example.org/b1.mp4
        #EXTINF:-1 tvg-id="TRT1.tr@SD" tvg-logo="" group-title="TR | Hauptsender",TRT 1
        https://tv-trt1.medya.trt.com.tr/master.m3u8
    """.trimIndent()

    @Test
    fun parsesEntries() {
        val list = M3u.parse(sample)
        assertEquals(4, list.size)
        assertEquals("Das Erste HD", list[0].name)
        assertEquals("DE | ARD & ZDF", list[0].group)
        assertEquals("https://x/logo.png", list[0].logo)
        assertEquals("Ekel Alfred, das Ekel", list[1].name)
        assertEquals("https://example.org/b1.mp4", list[2].url)
    }

    @Test
    fun groupsBySection() {
        val sections = M3u.bySection(M3u.parse(sample))
        assertEquals("ARD & ZDF", sections[Section.LIVE]!![0].title)
        assertEquals("Neu", sections[Section.FILME]!![0].title)
        assertEquals("Der Bergdoktor", sections[Section.SERIEN]!![0].title)
        assertEquals("Hauptsender", sections[Section.TURKISH]!![0].title)
    }

    /** Prüft die echte Liste aus dem Repo, sofern das Netz erreichbar ist. */
    @Test
    fun parsesRealPlaylist() {
        val text = try {
            Net.download(PLAYLIST_URL)
        } catch (e: Exception) {
            null
        }
        assumeTrue(text != null)
        val list = M3u.parse(text!!)
        assertTrue("zu wenige Einträge: ${list.size}", list.size > 1000)
        val sections = M3u.bySection(list)
        for (s in listOf(Section.LIVE, Section.TURKISH, Section.FILME, Section.KRIMIS, Section.SERIEN, Section.DOKUS)) {
            assertTrue("Bereich fehlt: $s", !sections[s].isNullOrEmpty())
        }
        assertTrue(list.all { it.url.startsWith("http") })
        println("Echte Liste: ${list.size} Einträge, ${sections.values.sumOf { it.size }} Gruppen")
    }
}
