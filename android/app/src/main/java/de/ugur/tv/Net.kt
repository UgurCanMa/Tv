package de.ugur.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

const val PLAYLIST_URL = "https://raw.githubusercontent.com/UgurCanMa/Tv/main/tv.m3u"
const val USER_AGENT = "Mozilla/5.0 (Linux; Android) UgurTV/1.0"

object Net {
    fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.useCaches = false
        c.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** Lädt Sender-Logos im Hintergrund und merkt sie sich im Speicher. */
object Logos {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, url: String) {
        view.tag = url
        if (url.isEmpty() || url in failed) {
            view.setImageResource(R.drawable.ic_placeholder)
            return
        }
        val hit = cache.get(url)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageResource(R.drawable.ic_placeholder)
        pool.execute {
            val bmp = try {
                fetch(url)
            } catch (e: Exception) {
                null
            }
            if (bmp == null) failed.add(url) else cache.put(url, bmp)
            main.post { if (view.tag == url && bmp != null) view.setImageBitmap(bmp) }
        }
    }

    private fun fetch(url: String): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (c.responseCode !in 200..299) return null
            val bytes = c.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } finally {
            c.disconnect()
        }
    }
}
