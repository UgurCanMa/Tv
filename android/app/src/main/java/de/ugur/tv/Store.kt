package de.ugur.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Speichert Favoriten, zuletzt Gesehenes, Wiedergabepositionen, eigene Listen und die geladenen Listen. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("ugurtv", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "tv.m3u")
    private val sourceDir = File(context.filesDir, "sources")
    private var favoriteUrls: MutableSet<String>? = null

    // ------------------------------------------------------------------ Hauptliste

    fun cachedPlaylist(): String? = readFile(cacheFile)

    fun cacheTime(): Long = if (cacheFile.exists()) cacheFile.lastModified() else 0L

    fun savePlaylist(text: String) = writeFile(cacheFile, text)

    // ------------------------------------------------------------------ Eigene Listen

    fun sources(): List<Source> {
        val raw = prefs.getString("sources", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Source(o.optString("id"), o.optString("name"), o.optString("url"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addSource(name: String, url: String): Source {
        val source = Source("s" + System.currentTimeMillis(), name, url)
        saveSources(sources() + source)
        return source
    }

    fun removeSource(id: String) {
        saveSources(sources().filter { it.id != id })
        File(sourceDir, "$id.m3u").delete()
    }

    /** Ändert sich, sobald Listen hinzukommen oder entfernt werden. */
    fun sourcesVersion(): Int = prefs.getInt("sources_version", 0)

    fun readSourceCache(id: String): String? = readFile(File(sourceDir, "$id.m3u"))

    fun writeSourceCache(id: String, text: String) {
        sourceDir.mkdirs()
        writeFile(File(sourceDir, "$id.m3u"), text)
    }

    private fun saveSources(list: List<Source>) {
        val arr = JSONArray()
        for (s in list) arr.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url))
        prefs.edit()
            .putString("sources", arr.toString())
            .putInt("sources_version", sourcesVersion() + 1)
            .apply()
    }

    // ------------------------------------------------------------------ Favoriten & Zuletzt

    fun favorites(): List<Channel> = readList("favorites")

    fun recents(): List<Channel> = readList("recents")

    fun isFavorite(ch: Channel): Boolean {
        val set = favoriteUrls ?: favorites().map { it.url }.toMutableSet().also { favoriteUrls = it }
        return ch.url in set
    }

    /** Gibt true zurück, wenn der Eintrag jetzt ein Favorit ist. */
    fun toggleFavorite(ch: Channel): Boolean {
        val list = favorites().toMutableList()
        val existed = list.removeAll { it.url == ch.url }
        if (!existed) list.add(0, ch)
        writeList("favorites", list)
        favoriteUrls = list.map { it.url }.toMutableSet()
        return !existed
    }

    fun addRecent(ch: Channel) {
        val list = recents().filter { it.url != ch.url }.toMutableList()
        list.add(0, ch)
        writeList("recents", list.take(40))
    }

    // ------------------------------------------------------------------ Weiterschauen

    fun position(url: String): Long = prefs.getLong(posKey(url), 0L)

    fun savePosition(url: String, positionMs: Long, durationMs: Long) {
        val edit = prefs.edit()
        if (durationMs <= 5 * 60_000L || positionMs < 60_000L || positionMs > durationMs - 3 * 60_000L) {
            edit.remove(posKey(url))
        } else {
            edit.putLong(posKey(url), positionMs)
        }
        edit.apply()
    }

    private fun posKey(url: String) = "pos_" + url.hashCode()

    // ------------------------------------------------------------------ Hilfen

    private fun readList(key: String): List<Channel> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val h = o.optJSONObject("h")
                val headers = HashMap<String, String>()
                if (h != null) for (k in h.keys()) headers[k] = h.optString(k)
                Channel(o.optString("n"), o.optString("g"), o.optString("l"), o.optString("u"), headers)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeList(key: String, list: List<Channel>) {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject().put("n", c.name).put("g", c.group).put("l", c.logo).put("u", c.url)
            if (c.headers.isNotEmpty()) o.put("h", JSONObject(c.headers as Map<*, *>))
            arr.put(o)
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun readFile(file: File): String? = try {
        if (file.exists()) file.readText() else null
    } catch (e: Exception) {
        null
    }

    private fun writeFile(file: File, text: String) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}
