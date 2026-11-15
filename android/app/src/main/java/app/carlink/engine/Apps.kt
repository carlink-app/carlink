package app.carlink.engine

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object Apps {
    private const val ICON_PX = 96

    class Listing(val json: JSONArray, val components: Set<Pair<String, String>>, val at: Long)

    /**
     * Launchable apps with small PNG icons, for the car launcher grid (the
     * page sorts). [icons] (package -> data URL) come from the carlink app
     * process, which can render adaptive icons; the shell often can't.
     */
    fun list(icons: Map<String, String> = emptyMap()): Listing {
        val pm = Shell.context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = JSONArray()
        val components = HashSet<Pair<String, String>>()
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            val cls = ri.activityInfo.name
            if (pkg in HIDDEN) continue
            try {
                out.put(JSONObject().put("pkg", pkg).put("cls", cls).put("label", ri.loadLabel(pm).toString()).put("cat", category(pkg, ri.activityInfo.applicationInfo.category)).put("icon", icons[pkg] ?: icon(pm, ri) ?: ""))
                components += pkg to cls
            } catch (e: Exception) {
                Log.w(TAG, "skip $pkg: $e")
            }
        }
        return Listing(out, components, System.currentTimeMillis())
    }

    /**
     * Launcher group for the car: `nav`, `music`, `social`, `work` or `other`.
     * Known apps first (many don't declare a category), then the app's own
     * `android:appCategory`.
     */
    fun category(pkg: String, declared: Int): String = KNOWN[pkg] ?: when (declared) {
        ApplicationInfo.CATEGORY_MAPS -> "nav"
        ApplicationInfo.CATEGORY_AUDIO -> "music"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "work"
        else -> "other"
    }

    private val KNOWN: Map<String, String> = buildMap {
        listOf(
            "com.google.android.apps.maps", "com.waze", "com.iternio.abrpapp", "com.teslamotors.tesla",
            "net.osmand", "net.osmand.plus", "com.here.app.maps", "com.sygic.aura", "ru.yandex.yandexnavi",
            "com.google.android.apps.mapslite", "com.plugshare.plugshare", "com.chargemap.android",
        ).forEach { put(it, "nav") }
        listOf(
            "com.spotify.music", "com.google.android.apps.youtube.music", "com.apple.android.music",
            "deezer.android.app", "com.amazon.mp3", "com.soundcloud.android", "com.aspiro.tidal",
            "com.google.android.apps.podcasts", "au.com.shiftyjelly.pocketcasts", "com.audible.application",
            "fm.castbox.audiobook.radio.podcast", "com.bambuna.podcastaddict", "tunein.player",
        ).forEach { put(it, "music") }
        listOf(
            "com.google.android.apps.messaging", "com.google.android.dialer", "com.android.dialer",
            "com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.viber.voip",
            "com.facebook.orca", "com.facebook.katana", "com.instagram.android", "com.discord",
            "com.twitter.android", "com.google.android.contacts",
        ).forEach { put(it, "social") }
        listOf(
            "com.google.android.gm", "com.google.android.calendar", "com.Slack", "com.microsoft.teams",
            "com.microsoft.office.outlook", "com.google.android.apps.meetings", "us.zoom.videomeetings",
            "com.google.android.keep", "com.google.android.apps.tasks",
        ).forEach { put(it, "work") }
    }

    /** Shell's system context lacks some framework drawables; try the app's own resources. */
    private fun icon(pm: PackageManager, ri: ResolveInfo): String? {
        val d = try {
            ri.loadIcon(pm)
        } catch (e: Exception) {
            try {
                val ai = ri.activityInfo.applicationInfo
                val res = pm.getResourcesForApplication(ai)
                val id = ri.activityInfo.iconResource.takeIf { it != 0 } ?: ai.icon
                if (id == 0) null else res.getDrawableForDensity(id, 320, null)
            } catch (e2: Exception) {
                null
            }
        } ?: return null
        return try { pngDataUrl(d, ICON_PX) } catch (e: Exception) { null }
    }

    /** The launcher activity of [pkg] from [listing], if it has one. */
    fun mainActivity(listing: Listing, pkg: String): String? = listing.components.firstOrNull { it.first == pkg }?.second

    /**
     * Never on the car: the car display works while the phone is locked, so
     * these would hand anyone at the car screen an unlocked phone (settings,
     * Shizuku = shell rights, installing apps, browsing files).
     */
    val HIDDEN = setOf(
        APP_PACKAGE,
        "com.android.settings",
        "moe.shizuku.privileged.api",
        "com.android.vending",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.google.android.apps.nbu.files",
    )

    private val VALID_NAME = Regex("^[A-Za-z0-9_.$]+$")

    /** Start an activity on a display. Runs `am` with our shell rights. */
    fun launch(pkg: String, cls: String, displayId: Int): Boolean {
        if (!VALID_NAME.matches(pkg) || !VALID_NAME.matches(cls) || pkg in HIDDEN) return false
        val output = am(
            "start", "--display", displayId.toString(), "--windowingMode", "1",
            "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER,
            "-n", "$pkg/$cls",
        ) ?: return false
        val ok = !output.contains("Error")
        if (!ok) Log.w(TAG, "launch $pkg failed: $output")
        return ok
    }

    /**
     * Close [pkg]'s tasks on a display, or every task there if [pkg] is null:
     * like swiping it away in recents. The app is not force-stopped, so its
     * notifications, alarms and background work keep running.
     */
    fun close(displayId: Int, pkg: String?): Int {
        if (pkg != null && !VALID_NAME.matches(pkg)) return 0
        val list = am("stack", "list") ?: return 0
        val ids = rootTasks(list, displayId, pkg)
        ids.forEach { am("stack", "remove", it.toString()) }
        return ids.size
    }

    /**
     * Root task ids on [displayId] from `am stack list` output, only those
     * with a task of [pkg] if given. Format per root task:
     * `RootTask id=N ... displayId=D ...` then `  taskId=T: pkg/cls ...` lines.
     */
    fun rootTasks(stackList: String, displayId: Int, pkg: String?): List<Int> {
        val out = ArrayList<Int>()
        var root: Int? = null
        var matched = false
        fun flush() {
            root?.let { if (pkg == null || matched) out += it }
            root = null
            matched = false
        }
        val header = Regex("""^RootTask id=(\d+) .*\bdisplayId=(\d+)\b""")
        val task = Regex("""^\s+taskId=\d+: ([A-Za-z0-9_.$]+)/""")
        for (line in stackList.lineSequence()) {
            val h = header.find(line)
            if (h != null) {
                flush()
                if (h.groupValues[2].toInt() == displayId) root = h.groupValues[1].toInt()
                continue
            }
            if (root != null && pkg != null && task.find(line)?.groupValues?.get(1) == pkg) matched = true
        }
        flush()
        return out
    }

    /** Runs `am` with shell rights; output, or null on failure or timeout. */
    private fun am(vararg args: String): String? {
        val p = ProcessBuilder("am", *args).redirectErrorStream(true).start()
        // `am` normally returns at once; never let a hung one stall the engine thread.
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "am ${args.firstOrNull()}: timed out")
            return null
        }
        val output = p.inputStream.bufferedReader().readText()
        if (p.exitValue() != 0) {
            Log.w(TAG, "am ${args.joinToString(" ")} failed: $output")
            return null
        }
        return output
    }
}

/** [d] drawn at [px] x [px] as a PNG data URL (used by both the engine and the app process). */
fun pngDataUrl(d: Drawable, px: Int): String {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, px, px)
    d.draw(Canvas(bmp))
    val bytes = ByteArrayOutputStream().use {
        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        it.toByteArray()
    }
    bmp.recycle()
    return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}
