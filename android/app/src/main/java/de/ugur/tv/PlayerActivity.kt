package de.ugur.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        var queue: List<Channel> = emptyList()
        var startIndex: Int = 0
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var title: TextView
    private lateinit var error: TextView
    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())
    private val hideTitle = Runnable {
        if (!playerView.isControllerFullyVisible) title.visibility = View.GONE
    }
    private var currentIndex = 0
    private var lastDuration = 0L

    @Volatile
    private var currentHeaders: Map<String, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = Store(this)
        playerView = findViewById(R.id.player_view)
        title = findViewById(R.id.title)
        error = findViewById(R.id.error)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) {
                handler.removeCallbacks(hideTitle)
                title.visibility = View.VISIBLE
            } else {
                title.visibility = View.GONE
            }
        })
        if (queue.isEmpty()) {
            finish()
            return
        }
        currentIndex = (savedInstanceState?.getInt("index", startIndex) ?: startIndex).coerceIn(0, queue.size - 1)
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        if (queue.isNotEmpty()) initPlayer()
    }

    override fun onStop() {
        savePosition()
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", currentIndex)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun initPlayer() {
        if (player != null) return
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("User-Agent" to USER_AGENT))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        // Manche Listen verlangen eigene Header (User-Agent, Referer) – die hängen wir an jede Anfrage.
        val withHeaders = ResolvingDataSource.Factory(http) { spec ->
            val extra = currentHeaders
            if (extra.isEmpty()) spec else spec.withAdditionalHeaders(extra)
        }
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, withHeaders)))
            .build()
        player = p
        playerView.player = p
        p.addListener(listener)
        val start = currentIndex.coerceIn(0, queue.size - 1)
        currentHeaders = queue[start].headers
        val resume = store.position(queue[start].url)
        p.setMediaItems(queue.map { mediaItem(it) }, start, if (resume > 0) resume else C.TIME_UNSET)
        p.playWhenReady = true
        p.prepare()
        onNewItem(start)
    }

    private fun releasePlayer() {
        val p = player ?: return
        p.removeListener(listener)
        p.release()
        player = null
        playerView.player = null
    }

    private fun mediaItem(ch: Channel): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(ch.url)
            .setMediaId(ch.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).build())
        val u = ch.url.lowercase()
        if (".m3u8" in u) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (".mpd" in u) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return builder.build()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            val idx = p.currentMediaItemIndex
            if (idx == currentIndex) return
            onNewItem(idx)
            val resume = store.position(queue[idx].url)
            if (resume > 0) p.seekTo(resume)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex && lastDuration > 0) {
                val old = queue.getOrNull(oldPosition.mediaItemIndex) ?: return
                store.savePosition(old.url, oldPosition.positionMs, lastDuration)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            if (playbackState == Player.STATE_READY) {
                error.visibility = View.GONE
                val d = p.duration
                lastDuration = if (d == C.TIME_UNSET || p.isCurrentMediaItemLive) 0L else d
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) error.visibility = View.GONE
        }

        override fun onPlayerError(e: PlaybackException) {
            val p = player ?: return
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            val name = queue.getOrNull(currentIndex)?.name.orEmpty()
            error.text = getString(R.string.play_error, name, e.errorCodeName)
            error.visibility = View.VISIBLE
        }
    }

    private fun onNewItem(idx: Int) {
        currentIndex = idx
        val ch = queue.getOrNull(idx) ?: return
        currentHeaders = ch.headers
        store.addRecent(ch)
        error.visibility = View.GONE
        title.text = if (queue.size > 1) "${ch.name}   (${idx + 1}/${queue.size})" else ch.name
        title.visibility = View.VISIBLE
        handler.removeCallbacks(hideTitle)
        handler.postDelayed(hideTitle, 4000)
    }

    private fun savePosition() {
        val p = player ?: return
        val ch = queue.getOrNull(p.currentMediaItemIndex) ?: return
        if (p.isCurrentMediaItemLive) return
        val d = p.duration
        if (d != C.TIME_UNSET && d > 0) store.savePosition(ch.url, p.currentPosition, d)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                    if (p.hasNextMediaItem()) {
                        savePosition()
                        p.seekToNextMediaItem()
                    }
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (p.hasPreviousMediaItem()) {
                        savePosition()
                        p.seekToPreviousMediaItem()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
