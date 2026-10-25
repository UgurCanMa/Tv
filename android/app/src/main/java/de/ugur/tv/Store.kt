package de.ugur.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Speichert Favoriten, zuletzt gesehene Einträge, Wiedergabepositionen und die zuletzt geladene Liste. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("ugurtv", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "tv.m3u")
    private var favoriteUrls: MutableSet<String>? = null

    fun cachedPlaylist(): String? = try {
        if (cacheFile.exists()) cacheFile.readText() else null
    } catch (e: Exception) {
        null
    }

    fun cacheTime(): Long = if (cacheFile.exists()) cacheFile.lastModified() else 0L

    fun savePlaylist(text: String) {
        try {
            val tmp = File(cacheFile.parentFile, "tv.m3u.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(text)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

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

    private fun readList(key: String): List<Channel> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Channel(o.optString("n"), o.optString("g"), o.optString("l"), o.optString("u"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeList(key: String, list: List<Channel>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(JSONObject().put("n", c.name).put("g", c.group).put("l", c.logo).put("u", c.url))
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
