package de.ugur.tv

data class Channel(val name: String, val group: String, val logo: String, val url: String)

data class Group(val key: String, val title: String, val items: List<Channel>)

enum class Section(val label: String, val prefixes: List<String>) {
    FAVORITES("★ Favoriten", emptyList()),
    RECENT("Zuletzt", emptyList()),
    LIVE("Live-TV", listOf("DE |", "EN |")),
    TURKISH("Türkçe", listOf("TR |")),
    FILME("Filme", listOf("FILME |")),
    KRIMIS("Krimis", listOf("KRIMIS |")),
    SERIEN("Serien", listOf("SERIEN |")),
    DOKUS("Dokus", listOf("DOKUS |")),
    KINDER("Kinder", listOf("KINDER |")),
}

object M3u {
    private val attrRegex = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name = ""
        var group = ""
        var logo = ""
        var pending = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                val (head, title) = splitExtinf(line)
                val attrs = HashMap<String, String>()
                for (m in attrRegex.findAll(head)) attrs[m.groupValues[1]] = m.groupValues[2]
                name = title.trim()
                group = attrs["group-title"].orEmpty().trim()
                logo = attrs["tvg-logo"].orEmpty().trim()
                pending = true
            } else if (line.isEmpty() || line.startsWith("#")) {
                continue
            } else if (pending) {
                out.add(Channel(name.ifEmpty { line }, group.ifEmpty { "Sonstiges" }, logo, line))
                pending = false
            }
        }
        return out
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

    fun bySection(channels: List<Channel>): Map<Section, List<Group>> {
        val grouped = LinkedHashMap<String, MutableList<Channel>>()
        for (c in channels) grouped.getOrPut(c.group) { ArrayList() }.add(c)
        val result = LinkedHashMap<Section, MutableList<Group>>()
        for ((key, items) in grouped) {
            result.getOrPut(sectionOf(key)) { ArrayList() }.add(Group(key, groupTitle(key), items))
        }
        return result
    }
}
