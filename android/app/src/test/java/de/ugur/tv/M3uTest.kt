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

    @Test
    fun readsHeadersAndExtGrp() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Sender A
            #EXTGRP:Nachrichten
            #EXTVLCOPT:http-user-agent=Mozilla/5.0 Test
            #EXTVLCOPT:http-referrer=https://example.org/
            https://a.example.org/live.m3u8
            #EXTINF:-1 group-title="Sport",Sender B
            https://b.example.org/live.m3u8|User-Agent=Foo%2F1.0&Referer=https://ref.example/
            #EXTINF:-1 group-title="Sport",Sender C
            #EXTHTTP:{"User-Agent":"Bar/2.0","Origin":"https://o.example"}
            https://c.example.org/live.m3u8
            #EXTINF:-1,Sender D
            https://d.example.org/live.m3u8
        """.trimIndent()
        val list = M3u.parse(text)
        assertEquals(4, list.size)
        assertEquals("Nachrichten", list[0].group)
        assertEquals("Mozilla/5.0 Test", list[0].headers["User-Agent"])
        assertEquals("https://example.org/", list[0].headers["Referer"])
        assertEquals("https://b.example.org/live.m3u8", list[1].url)
        assertEquals("Foo/1.0", list[1].headers["User-Agent"])
        assertEquals("https://ref.example/", list[1].headers["Referer"])
        assertEquals("Bar/2.0", list[2].headers["User-Agent"])
        assertEquals("https://o.example", list[2].headers["Origin"])
        assertTrue(list[3].headers.isEmpty())
        assertEquals("Sonstiges", list[3].group)
        assertEquals(listOf("Nachrichten", "Sport", "Sonstiges"), M3u.groups(list).map { it.title })
    }

    @Test
    fun interpretsSources() {
        val source = Source("s1", "Mein Stream", "https://x.example/index.m3u8")
        val hls = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n"
        assertEquals(listOf("https://x.example/index.m3u8"), Lists.interpret(source, hls).map { it.url })
        assertEquals(1, Lists.interpret(source, Lists.STREAM_MARKER).size)
        val plain = "https://a.example/1.m3u8\n\nhttps://a.example/2.m3u8\nkein Link"
        val items = Lists.interpret(Source("s2", "Text", ""), plain)
        assertEquals(listOf("Text 1", "Text 2"), items.map { it.name })
        assertTrue(Lists.interpret(Source("s3", "Leer", ""), "nichts hier").isEmpty())
        assertTrue(Lists.interpret(source, null).isEmpty())
    }
}
