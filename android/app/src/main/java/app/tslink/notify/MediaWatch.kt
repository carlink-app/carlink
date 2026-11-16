package app.tslink.notify

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.annotation.WorkerThread
import app.tslink.engine.Media
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Follows the phone's current media session (what plays on the car's
 * Bluetooth) and reports it as `media` messages; runs `media-cmd` controls.
 * Needs notification access, which the listener has.
 */
class MediaWatch(
    private val ctx: Context,
    private val listener: ComponentName,
    private val label: (String) -> String,
    private val send: (JSONObject) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private var current: MediaController? = null
    private var last: String? = null
    /** Track the cached art belongs to: each metadata read gives a new Bitmap object. */
    private var artKey: String? = null
    private var artUrl: String? = null
    private val encoder = Executors.newSingleThreadExecutor()

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = report()
        override fun onMetadataChanged(metadata: MediaMetadata?) = report()
        override fun onSessionDestroyed() = pick(controllers())
    }

    fun watch() {
        try {
            msm.addOnActiveSessionsChangedListener(sessions, listener, main)
        } catch (e: SecurityException) {
            Log.w(TAG, "media sessions: $e")
            return
        }
        pick(controllers())
    }

    fun stop() {
        encoder.shutdown()
        try { msm.removeOnActiveSessionsChangedListener(sessions) } catch (_: Exception) {}
        current?.unregisterCallback(callback)
        current = null
    }

    /** Sends the current state again (the engine relinked). */
    fun resend() {
        last = null
        report()
    }

    fun command(a: String) {
        val c = current ?: return
        val t = c.transportControls
        when (a) {
            "play" -> t.play()
            "pause" -> t.pause()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
        }
    }

    private fun controllers(): List<MediaController> =
        try { msm.getActiveSessions(listener) } catch (e: SecurityException) { emptyList() }

    /** The playing session, else the most recent one (the list is newest first). */
    private fun pick(list: List<MediaController>) {
        val c = pickSession(list) { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (c?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(callback)
            current = c
            c?.registerCallback(callback, main)
        }
        report()
    }

    private fun report() {
        val c = current
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val m = if (c == null || title.isEmpty()) {
            JSONObject().put("t", "media").put("none", true)
        } else {
            val st = c.playbackState
            val actions = st?.actions ?: 0L
            JSONObject().put("t", "media")
                .put("pkg", c.packageName)
                .put("app", label(c.packageName))
                .put("title", title)
                .put("artist", meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty())
                .put("playing", playingState(st?.state))
                .put("prev", actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
                .put("next", actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
                .put("art", art(meta).orEmpty())
        }
        val s = m.toString()
        if (s == last) return // metadata callbacks repeat a lot
        last = s
        send(m)
    }

    /**
     * Small JPEG of the cover art, cached per track. Encoded
     * off the main thread: the first report goes without it, a second follows.
     */
    private fun art(meta: MediaMetadata?): String? {
        val bmp = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        val key = listOf(
            meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
            meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            "${bmp.width}x${bmp.height}",
        ).joinToString("|")
        if (key == artKey) return artUrl
        artKey = key
        artUrl = null
        if (encoder.isShutdown) return null // stopped
        encoder.execute {
            val url = encode(bmp)
            main.post {
                if (artKey != key || encoder.isShutdown) return@post // track changed, or stopped
                artUrl = url
                report()
            }
        }
        return null
    }

    @WorkerThread
    private fun encode(bmp: Bitmap): String? = try {
        val small = Bitmap.createScaledBitmap(bmp, ART_PX, ART_PX, true)
        val bytes = ByteArrayOutputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it); it.toByteArray() }
        if (small !== bmp) small.recycle()
        ("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)).takeIf { it.length <= Media.MAX_ART }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "tslink-notif"
        private const val ART_PX = 160
    }
}

/** The session to follow: the first playing one, else the first (lists are newest first). */
internal fun <T> pickSession(list: List<T>, playing: (T) -> Boolean): T? = list.firstOrNull(playing) ?: list.firstOrNull()

/** "Playing" for the car's play/pause button: buffering counts (the track is about to play). */
internal fun playingState(state: Int?): Boolean = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
