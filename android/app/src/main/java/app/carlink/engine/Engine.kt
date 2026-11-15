package app.carlink.engine

import android.app.ActivityOptions
import android.app.PendingIntent
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The engine: owns the network core, panes (virtual display + encoder each)
 * and input. Runs in the shell-privileged process. Every callback from the
 * native core is posted to one thread, so all state is touched by it only.
 * Messages only arrive after the car authenticated (see core/src/server.rs).
 */
class Engine(private val code: String, port: Int, allow: String) : NativeCore.Listener {
    companion object {
        const val MAX_PANES = 4
        private val SIZE = 320..4096
        private val DPI = 72..640
        private const val APPS_TTL_MS = 30_000L
        private const val PANE_GRACE_MS = 30_000L
        private const val GUARD_MS = 1_000L
        private const val MAX_ICON_REQUEST = 500
        private const val MAX_PKG_CHARS = 255
        private const val ICONS_PER_MESSAGE = 16
        private const val ICONS_SPACING_MS = 40L
        private const val REPLAY_MS = 5 * 60_000L
    }

    private val thread = HandlerThread("engine").apply { start() }
    private val handler = Handler(thread.looper)
    private val panes = HashMap<Int, Pane>()
    private val phonePower by lazy { PhonePower(handler) }
    /** Bumped per session: pending icon batches of an older one are dropped. */
    private var iconGeneration = 0
    /** The car page fetches icons by hash (hello `icons: "hash"`); else they go inline. */
    private var iconsByHash = false
    private val lastLaunch = HashMap<Int, Pair<String, String>>()
    /** Chosen per session from what the car decodes (HEVC preferred). */
    private var codec = "h264"
    private var level = 800
    private var apps: Apps.Listing? = null
    /** Notifications still on the phone, re-sent to a car that (re)connects. */
    private val activeNotifs = object : LinkedHashMap<String, Pair<JSONObject, Long>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<JSONObject, Long>>) = size > 10
    }
    /** Icons rendered by the carlink app (package -> data URL). */
    private var appIcons: Map<String, String> = emptyMap()
    /** Messages to the carlink app (EngineService.setClient); setting it re-sends the session state (the app may have restarted). */
    @Volatile var toApp: ((JSONObject) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(sessionMsg(sessionUp))
        }
    @Volatile private var sessionUp = false

    private fun sessionMsg(up: Boolean) = JSONObject().put("t", "session").put("up", up)

    /** The app shows "connected to car" on the phone (lock screen included). */
    private fun reportSession(up: Boolean) {
        sessionUp = up
        toApp?.invoke(sessionMsg(up))
    }

    /** Native handle; zeroed before nativeStop so nothing uses it afterwards. */
    private val core = AtomicLong(0)

    @Volatile var status: String = "waiting for car"
        private set

    init {
        core.set(NativeCore.nativeStart(port, code, allow, this))
        post { phonePower.restoreAfterCrash() }
    }

    /** While panes exist: close denied apps that reached the car display anyway. */
    private val guard = object : Runnable {
        override fun run() {
            if (panes.isEmpty() || core.get() == 0L) return
            try {
                for (pane in panes.values) {
                    val closed = Apps.closeDenied(pane.displayId)
                    if (closed.isNotEmpty()) {
                        Log.w(TAG, "closed on the car display (not allowed there): $closed")
                        error("That screen isn't available in the car")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "guard: $e") // never take the engine down
            }
            handler.postDelayed(this, GUARD_MS)
        }
    }

    /**
     * Open a notification on the car. Android 14+ silently drops background
     * starts of a notification's intent unless its app is visible (and won't
     * tell the sender), so: bring the app up on the car display first (shell
     * may always do that), then fire the intent for the exact screen
     * (conversation, route, ...). If Android still refuses, the app is open.
     */
    fun openNotification(intent: PendingIntent?, pkg: String, displayId: Int) = post {
        val pane = panes[0]?.takeIf { it.displayId == displayId } ?: return@post
        Apps.mainActivity(listing(), pkg)?.let { cls ->
            if (Apps.launch(pkg, cls, pane.displayId)) lastLaunch[0] = pkg to cls
        }
        if (intent == null) return@post
        handler.postDelayed({
            try {
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                if (Build.VERSION.SDK_INT >= 34) {
                    options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                intent.send(Shell.context, 0, null, null, null, null, options.toBundle())
            } catch (e: Exception) {
                Log.i(TAG, "notification intent for $pkg: $e")
            }
        }, 400)
    }

    /** First 6 hex of this week's cert hash: the car page's debug overlay shows the same. */
    fun fingerprint(): String = NativeCore.nativeCertHash(code).take(6)

    /**
     * The page re-measures on every (re)connect and while its layout settles,
     * landing a few pixels or a density step off. Recreating the display for
     * that would restart the encoder and move the apps; the page letterboxes
     * the small difference. Real changes (expanded browser) still recreate.
     */
    private fun sameSize(a: Int, b: Int) = kotlin.math.abs(a - b) <= 16
    private fun sameDensity(a: Int, b: Int) = kotlin.math.abs(a - b) <= maxOf(2, a / 30)

    /** Banner-worthy now: calls in progress, or posted in the last few minutes. */
    private fun showNow(n: JSONObject, postedAt: Long, now: Long) = n.optString("kind") == "call" || now - postedAt < REPLAY_MS

    /** Message from the carlink app process (see IEngine.fromApp). */
    fun fromApp(json: String) = post {
        val m = try { JSONObject(json) } catch (e: Exception) { return@post }
        handleApp(m)
    }

    private fun handleApp(m: JSONObject) {
        when (m.optString("t")) {
            "notif" -> Notif.sanitize(m)?.let {
                // "quiet": already on the phone when the app linked (re-sync after
                // a restart). Tracked, so calls etc. still come back to a car
                // that reconnects, but not popped up as new.
                val quiet = m.optBoolean("quiet")
                val at = if (quiet) 0L else System.currentTimeMillis()
                activeNotifs.remove(it.getString("id"))
                activeNotifs[it.getString("id")] = it to at
                if (!quiet) send(it)
            }
            "notif-gone" -> m.optString("id").takeIf { Notif.ID.matches(it) }?.let {
                activeNotifs.remove(it)
                send(JSONObject().put("t", "notif-gone").put("id", it))
            }
            // The app re-posts everything after this: drop what we had.
            "notif-reset" -> {
                activeNotifs.clear()
                send(JSONObject().put("t", "notif-reset")) // the car drops what it shows too
            }
            "icons" -> {
                val list = m.optJSONArray("list") ?: return
                val base = if (m.optBoolean("first", true)) emptyMap() else appIcons
                appIcons = base + (0 until list.length()).mapNotNull { i ->
                    val o = list.optJSONObject(i) ?: return@mapNotNull null
                    val icon = o.optString("icon")
                    if (icon.startsWith("data:image/png;base64,") && icon.length <= 64 * 1024) o.optString("pkg") to icon else null
                }.toMap()
                apps = null // next listing picks them up
            }
        }
    }

    // Native callbacks arrive on network threads: hop to the engine thread.
    override fun onSession(connected: Boolean, remote: String) = post {
        Log.i(TAG, "session ${if (connected) "up" else "down"}: $remote")
        // Removing a display closes its apps. A short Wi-Fi drop or the car
        // browser going to the background shouldn't do that: keep the panes a
        // while; a returning car reuses them (same layout).
        handler.removeCallbacks(releaseIdlePanes)
        if (!connected) handler.postDelayed(releaseIdlePanes, PANE_GRACE_MS)
        status = if (connected) "car connected ($remote)" else "waiting for car"
        iconGeneration++ // icon batches of an earlier session are dropped
        reportSession(connected)
    }

    private val releaseIdlePanes = Runnable { if (core.get() != 0L) releasePanes() }

    override fun onControl(json: String) = post { handle(JSONObject(json)) }

    override fun onKeyframeNeeded(pane: Int) = post { panes[pane]?.requestKeyframe() }

    override fun onRate(level: Int) = post {
        this.level = level
        panes.values.forEach { it.setLevel(level) }
        send(JSONObject().put("t", "rate").put("level", level).put("kbps", Pane.maxBitrate(codec) / 1000 * level / 1000))
    }

    private fun post(block: () -> Unit) {
        handler.post {
            if (core.get() == 0L) return@post
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "engine error", t)
                error("request failed")
            }
        }
    }

    private fun send(msg: JSONObject) {
        val h = core.get()
        if (h != 0L) NativeCore.nativeSendControl(h, msg.toString())
    }

    private fun error(msg: String) = send(JSONObject().put("t", "error").put("msg", msg))

    private fun handle(m: JSONObject) {
        when (m.getString("t")) {
            "hello" -> {
                val codecs = m.optJSONArray("codecs")
                val hevc = codecs != null && (0 until minOf(codecs.length(), 8)).any { codecs.optString(it) == "hevc" }
                codec = if (hevc && Codecs.hasEncoder("hevc")) "hevc" else "h264"
                iconsByHash = m.optString("icons") == "hash"
                Log.i(TAG, "car hello: ${m.optInt("w")}x${m.optInt("h")}, hevc=$hevc, using $codec")
                send(JSONObject().put("t", "hello").put("v", 2).put("device", Build.MODEL).put("android", Build.VERSION.SDK_INT))
                // The page clears banners on a new session: calls in progress and
                // recent ones come back, not old news.
                val now = System.currentTimeMillis()
                // Marked as replays: the page skips ones the driver already dismissed.
                activeNotifs.values.filter { (n, at) -> showNow(n, at, now) }
                    .forEach { send(JSONObject(it.first.toString()).put("replay", true)) }
            }
            "layout" -> layout(m)
            "apps" -> {
                val l = listing()
                // Pages from before icon caching need the icons inline.
                val list = if (iconsByHash) l.json else JSONArray().also { out ->
                    for (i in 0 until l.json.length()) {
                        val a = JSONObject(l.json.getJSONObject(i).toString())
                        l.icons[a.getString("pkg")]?.let { a.put("icon", it) }
                        out.put(a)
                    }
                }
                send(JSONObject().put("t", "apps").put("list", list))
            }
            // Icons the car doesn't have yet, a few per message so video keeps flowing.
            "app-icons" -> {
                val want = m.optJSONArray("pkgs") ?: return
                val icons = listing().icons
                val found = (0 until minOf(want.length(), MAX_ICON_REQUEST))
                    .map { want.optString(it) }
                    .filter { it.length <= MAX_PKG_CHARS }
                    .toSet() // a package once, however often it's asked for
                    .mapNotNull { pkg -> icons[pkg]?.let { pkg to it } }
                val gen = iconGeneration
                found.chunked(ICONS_PER_MESSAGE).forEachIndexed { n, chunk ->
                    handler.postDelayed({
                        // Stale (engine stopped, or a new session started): drop.
                        if (core.get() == 0L || gen != iconGeneration) return@postDelayed
                        val list = JSONArray()
                        // With its hash: the page keeps it only if its list has the same one.
                        chunk.forEach { (pkg, icon) -> list.put(JSONObject().put("pkg", pkg).put("ih", Apps.iconHash(icon)).put("icon", icon)) }
                        send(JSONObject().put("t", "app-icons").put("list", list))
                    }, n * ICONS_SPACING_MS)
                }
            }
            "launch" -> {
                val id = m.getInt("pane")
                val pane = panes[id] ?: return
                val pkg = m.getString("pkg")
                val cls = m.getString("cls")
                // Only apps from the launcher list: the engine has shell rights.
                if ((pkg to cls) !in listing().components) return error("unknown app")
                if (Apps.launch(pkg, cls, pane.displayId)) {
                    lastLaunch[id] = pkg to cls
                    send(JSONObject().put("t", "launched").put("pane", id).put("pkg", pkg))
                } else {
                    error("could not launch app")
                }
            }
            "touch" -> panes[m.getInt("pane")]?.touch?.handle(
                m.getString("a"), m.getInt("id"), m.getDouble("x").toFloat(), m.getDouble("y").toFloat(),
            )
            "close", "close-all" -> {
                val id = m.getInt("pane")
                val pane = panes[id] ?: return
                val pkg = if (m.getString("t") == "close") m.getString("pkg") else null
                val n = Apps.close(pane.displayId, pkg)
                Log.i(TAG, "closed $n task(s) on pane $id${pkg?.let { " ($it)" } ?: ""}")
                if (pkg == null || lastLaunch[id]?.first == pkg) lastLaunch.remove(id)
            }
            "key" -> {
                val pane = panes[m.getInt("pane")] ?: return
                if (m.getString("k") == "back") Input.key(pane.displayId, KeyEvent.KEYCODE_BACK)
            }
            "keyframe" -> panes[m.optInt("pane", 0)]?.requestKeyframe()
            "notif-open" -> {
                val id = m.optString("id")
                val pane = panes[0] ?: return
                activeNotifs.remove(id) // opened: not replayed on the next connect
                if (Notif.ID.matches(id)) toApp?.invoke(JSONObject().put("t", "open").put("id", id).put("display", pane.displayId))
            }
        }
    }

    private fun listing(): Apps.Listing {
        val cached = apps
        if (cached != null && System.currentTimeMillis() - cached.at < APPS_TTL_MS) return cached
        return Apps.list(appIcons).also { apps = it }
    }

    private fun layout(m: JSONObject) {
        val list = m.getJSONArray("panes")
        if (list.length() > MAX_PANES) return error("too many panes")
        // Validate everything before touching any pane: a bad entry must not
        // leave the layout half applied.
        val wanted = (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            val id = p.getInt("id")
            val w = p.getInt("w") and 1.inv()
            val h = p.getInt("h") and 1.inv()
            val dpi = p.optInt("dpi", 200)
            if (id !in 0 until MAX_PANES || w !in SIZE || h !in SIZE || dpi !in DPI) return error("bad layout")
            intArrayOf(id, w, h, dpi)
        }
        wanted.forEach { (id, w, h, dpi) -> layoutPane(id, w, h, dpi) }
        (panes.keys - wanted.map { it[0] }.toSet()).forEach { panes.remove(it)?.release() }
        if (panes.values.none { it.sharesPhonePower }) phonePower.stop()
    }

    private fun layoutPane(id: Int, w: Int, h: Int, dpi: Int) {
        val existing = panes[id]
        if (existing != null && sameSize(existing.requestedWidth, w) && sameSize(existing.requestedHeight, h) &&
            sameDensity(existing.dpi, dpi) && existing.codec == codec
        ) {
            existing.requestKeyframe()
        } else {
            if (existing != null) {
                Log.i(TAG, "pane $id re-created: ${existing.requestedWidth}x${existing.requestedHeight}@${existing.dpi} ${existing.codec} -> ${w}x$h@$dpi $codec")
            }
            panes.remove(id)?.release()
            val pane = try {
                Pane(id, w, h, dpi, codec, level, core::get)
            } catch (e: Exception) {
                Log.e(TAG, "pane $id ${w}x$h $codec failed", e)
                // Only codec trouble is worth an H.264 retry.
                val codecFailure = e is IllegalArgumentException || e is IOException || e is MediaCodec.CodecException
                if (codec == "h264" || !codecFailure) return error("video setup failed")
                // HEVC encoder refused: H.264 for the rest of the session.
                codec = "h264"
                return layoutPane(id, w, h, dpi)
            }
            panes[id] = pane
            if (pane.sharesPhonePower) phonePower.start()
            handler.removeCallbacks(guard)
            handler.postDelayed(guard, GUARD_MS)
            lastLaunch[id]?.let { (pkg, cls) -> Apps.launch(pkg, cls, pane.displayId) }
        }
        val pane = panes[id] ?: return
        send(JSONObject().put("t", "pane").put("id", id).put("w", pane.width).put("h", pane.height).put("codec", codec))
    }

    private fun releasePanes() {
        panes.values.forEach { it.release() }
        panes.clear()
        phonePower.stop()
        // A new session starts empty; the page re-sends what it was showing.
        lastLaunch.clear()
    }

    /** Tear down on the engine thread; returns once done or after [waitMs]. */
    fun stop(waitMs: Long = 0) {
        val done = CountDownLatch(1)
        handler.post {
            releasePanes()
            // Before the thread quits: a later onSession(false) would be dropped.
            reportSession(false)
            val h = core.getAndSet(0)
            if (h != 0L) NativeCore.nativeStop(h)
            status = "stopped"
            thread.quitSafely()
            done.countDown()
        }
        if (waitMs > 0) done.await(waitMs, TimeUnit.MILLISECONDS)
    }
}
