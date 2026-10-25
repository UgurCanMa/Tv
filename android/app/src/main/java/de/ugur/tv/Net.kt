package de.ugur.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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

    private val bright = ConcurrentHashMap<String, Boolean>()

    fun load(view: ImageView, url: String) {
        view.tag = url
        view.setBackgroundResource(R.drawable.logo_bg)
        if (url.isEmpty() || url in failed) {
            view.setImageResource(R.drawable.ic_placeholder)
            return
        }
        val hit = cache.get(url)
        if (hit != null) {
            show(view, url, hit)
            return
        }
        view.setImageResource(R.drawable.ic_placeholder)
        pool.execute {
            val bmp = try {
                fetch(url)
            } catch (e: Exception) {
                null
            }
            if (bmp == null) {
                failed.add(url)
            } else {
                bright[url] = isBright(bmp)
                cache.put(url, bmp)
            }
            main.post { if (view.tag == url && bmp != null) show(view, url, bmp) }
        }
    }

    private fun show(view: ImageView, url: String, bmp: Bitmap) {
        view.setImageBitmap(bmp)
        view.setBackgroundResource(if (bright[url] == true) R.drawable.logo_bg_dark else R.drawable.logo_bg)
    }

    /** Fast weiße Logos bekommen einen dunklen Hintergrund, damit man sie sieht. */
    private fun isBright(bmp: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        var sum = 0.0
        var n = 0
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val c = small.getPixel(x, y)
                if (Color.alpha(c) < 140) continue
                sum += (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
                n++
            }
        }
        if (small !== bmp) small.recycle()
        return n >= 12 && sum / n > 0.82
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
