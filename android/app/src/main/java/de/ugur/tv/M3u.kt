package de.ugur.tv

import java.net.URLDecoder

data class Channel(
    val name: String,
    val group: String,
    val logo: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

data class Group(val key: String, val title: String, val items: List<Channel>)

enum class Section(val label: String, val prefixes: List<String>) {
    LIVE("Live-TV", listOf("DE |", "EN |")),
    TURKISH("Türkçe", listOf("TR |")),
    FILME("Filme", listOf("FILME |")),
    SERIEN("Serien", listOf("SERIEN |")),
    KRIMIS("Krimis", listOf("KRIMIS |")),
    DOKUS("Dokus", listOf("DOKUS |")),
    KINDER("Kinder", listOf("KINDER |")),
    FAVORITES("★ Favoriten", emptyList()),
    RECENT("Zuletzt", emptyList()),
}

object M3u {
    private val attrRegex = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val jsonPair = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")

    /**
     * Liest eine M3U-Liste. Versteht group-title, tvg-logo, #EXTGRP sowie Header-Angaben
     * (#EXTVLCOPT:http-user-agent / http-referrer, #EXTHTTP:{…} und „URL|User-Agent=…“).
     */
    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name = ""
        var group = ""
        var logo = ""
        var extGroup = ""
        var pending = false
        val headers = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val (head, title) = splitExtinf(line)
                    val attrs = HashMap<String, String>()
                    for (m in attrRegex.findAll(head)) attrs[m.groupValues[1]] = m.groupValues[2]
                    name = title.trim()
                    group = attrs["group-title"].orEmpty().trim()
                    logo = attrs["tvg-logo"].orEmpty().trim()
                    pending = true
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val value = option.substringAfter('=', "").trim()
                    when (option.substringBefore('=').trim().lowercase()) {
                        "http-user-agent" -> headers["User-Agent"] = value
                        "http-referrer", "http-referer" -> headers["Referer"] = value
                        "http-origin" -> headers["Origin"] = value
                    }
                }
                line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                    for (m in jsonPair.findAll(line.substringAfter(':'))) headers[m.groupValues[1]] = m.groupValues[2]
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                pending -> {
                    var url = line
                    val pipe = url.indexOf('|')
                    if (pipe > 0) {
                        for (part in url.substring(pipe + 1).split('&')) {
                            val key = part.substringBefore('=').trim()
                            val value = decode(part.substringAfter('=', "").trim())
                            if (key.isNotEmpty() && value.isNotEmpty()) headers[key] = value
                        }
                        url = url.substring(0, pipe).trim()
                    }
                    val g = group.ifEmpty { extGroup }.ifEmpty { "Sonstiges" }
                    out.add(Channel(name.ifEmpty { url }, g, logo, url, if (headers.isEmpty()) emptyMap() else HashMap(headers)))
                    pending = false
                    headers.clear()
                    extGroup = ""
                }
            }
        }
        return out
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        value
    }

    fun splitExtinf(line: String): Pair<String, String> {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') {
                inQuotes = !inQuotes
            } else if (c == ',' && !inQuotes) {
                return Pair(line.substring(0, i), line.substring(i + 1))
            }
        }
        return Pair(line, "")
    }

    fun sectionOf(group: String): Section =
        Section.values().firstOrNull { s -> s.prefixes.any { group.startsWith(it) } } ?: Section.LIVE

    fun groupTitle(group: String): String {
        val idx = group.indexOf(" | ")
        return if (idx >= 0) group.substring(idx + 3) else group
    }

    /** Gruppen der eingebauten Liste, aufgeteilt auf die Reiter. */
    fun bySection(channels: List<Channel>): Map<Section, List<Group>> {
        val result = LinkedHashMap<Section, MutableList<Group>>()
        for (g in groups(channels)) result.getOrPut(sectionOf(g.key)) { ArrayList() }.add(Group(g.key, groupTitle(g.key), g.items))
        return result
    }

    /** Gruppen einer eigenen Liste in der Reihenfolge der Datei. */
    fun groups(channels: List<Channel>): List<Group> {
        val grouped = LinkedHashMap<String, MutableList<Channel>>()
        for (c in channels) grouped.getOrPut(c.group) { ArrayList() }.add(c)
        return grouped.map { (key, items) -> Group(key, key, items) }
    }
}
