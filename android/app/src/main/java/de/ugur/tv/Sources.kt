package de.ugur.tv

/** Eine zusätzliche Liste: per Link (url) oder als Datei vom Handy (url leer). */
data class Source(val id: String, val name: String, val url: String) {
    val isFile: Boolean get() = url.isEmpty()
}

object Lists {
    /** Merker im Zwischenspeicher: Der Link ist selbst ein Stream und keine Liste. */
    const val STREAM_MARKER = "#UGURTV-STREAM"

    /** Wandelt den Inhalt einer Quelle in Einträge um. */
    fun interpret(source: Source, text: String?): List<Channel> {
        if (text == null) return emptyList()
        if (text.startsWith(STREAM_MARKER) || isHlsStream(text)) {
            return if (source.url.isNotEmpty()) listOf(Channel(source.name, source.name, "", source.url)) else emptyList()
        }
        val parsed = M3u.parse(text)
        if (parsed.isNotEmpty()) return parsed
        // Einfache Textdatei mit einem Link pro Zeile
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .mapIndexed { i, u -> Channel("${source.name} ${i + 1}", source.name, "", u) }
            .toList()
    }

    /** Ein HLS-Stream (z. B. „…/index.m3u8“) sieht ähnlich aus wie eine Liste, ist aber ein einzelner Sender. */
    fun isHlsStream(text: String): Boolean =
        text.contains("#EXT-X-TARGETDURATION") || text.contains("#EXT-X-STREAM-INF") || text.contains("#EXT-X-MEDIA-SEQUENCE")
}
