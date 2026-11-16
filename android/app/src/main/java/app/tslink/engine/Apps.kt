package app.tslink.engine

import android.content.ComponentName
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

    /** [json] has no icons, only their hashes ("ih"); [icons] holds them (package -> data URL). */
    class Listing(val json: JSONArray, val components: Set<Pair<String, String>>, val icons: Map<String, String>, val at: Long)

    /** Short, stable id of an icon: the car keeps icons and only asks for changed ones. */
    fun iconHash(icon: String): String = Integer.toHexString(icon.hashCode()) + "-" + icon.length

    /** A copy of [json] with each app's icon inline (for pages from before icon caching). */
    fun withIcons(json: JSONArray, icons: Map<String, String>): JSONArray = JSONArray().also { out ->
        for (i in 0 until json.length()) {
            val a = JSONObject(json.getJSONObject(i).toString())
            icons[a.getString("pkg")]?.let { a.put("icon", it) }
            out.put(a)
        }
    }

    /**
     * Launchable apps with small PNG icons, for the car launcher grid (the
     * page sorts). [icons] (package -> data URL) come from the tslink app
     * process, which can render adaptive icons; the shell often can't.
     */
    fun list(icons: Map<String, String> = emptyMap()): Listing {
        val pm = Shell.context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = JSONArray()
        val components = HashSet<Pair<String, String>>()
        val iconsOut = HashMap<String, String>()
        findCameraApps(pm).let { if (it != cameraApps) Log.i(TAG, "camera apps (blocked in the car): $it"); cameraApps = it }
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            val cls = ri.activityInfo.name
            if (blocked(pkg)) continue
            try {
                val icon = icons[pkg] ?: icon(pm, ri) ?: ""
                val entry = JSONObject().put("pkg", pkg).put("cls", cls).put("label", ri.loadLabel(pm).toString())
                    .put("cat", category(pkg, ri.activityInfo.applicationInfo.category))
                if (icon.isNotEmpty()) {
                    entry.put("ih", iconHash(icon))
                    iconsOut[pkg] = icon
                }
                out.put(entry)
                components += pkg to cls
            } catch (e: Exception) {
                Log.w(TAG, "skip $pkg: $e")
            }
        }
        return Listing(out, components, iconsOut, System.currentTimeMillis())
    }

    /**
     * Launcher group for the car: `nav`, `energy`, `music`, `video`, `social`, `work` or `other`.
     * Known apps first (many don't declare a category), then the app's own
     * `android:appCategory`.
     */
    fun category(pkg: String, declared: Int): String = KNOWN[pkg] ?: when (declared) {
        ApplicationInfo.CATEGORY_MAPS -> "nav"
        ApplicationInfo.CATEGORY_AUDIO -> "music"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"
        ApplicationInfo.CATEGORY_VIDEO -> "video"
        // Not PRODUCTIVITY -> work: password managers, browsers, AI chat and
        // utilities declare it too. Work is the known list only.
        else -> "other"
    }

    private val KNOWN: Map<String, String> = buildMap {
        listOf(
            "com.google.android.apps.maps", "com.waze", "net.osmand", "net.osmand.plus", "com.here.app.maps",
            "com.sygic.aura", "ru.yandex.yandexnavi", "com.google.android.apps.mapslite",
        ).forEach { put(it, "nav") }
        // EV charging, route planning for EVs, the car's own app, fuel stations.
        listOf(
            "com.iternio.abrpapp", "com.teslamotors.tesla", "com.xatori.Plugshare", "com.chargemap.android",
            "com.chargepoint.network", "com.energy.eva.chargers", "com.tokamobile.TokaApp", "dev.abodnya.evua",
            "eu.ecofactor", "com.go.tou", "d2.android.apps.wog", "ua.upg",
        ).forEach { put(it, "energy") }
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
        // Taxi and delivery apps declare appCategory="maps" but aren't navigation.
        listOf(
            "com.ubercab", "com.ubercab.eats", "ee.mtakso.client", "com.bolt.deliveryclient",
            "com.lyft.android", "com.glovoapp", "com.glovo", "ua.com.uklontaxi", "com.wolt.android", "com.ubercab.driver", "ee.mtakso.driver",
            "com.google.android.apps.chromecast.app", // Google Home declares "video"
        ).forEach { put(it, "other") }
        listOf(
            "com.Slack", "com.microsoft.teams", "com.microsoft.office.outlook", "com.google.android.gm",
            "com.google.android.calendar", "me.proton.android.calendar", "com.google.android.apps.meetings",
            "com.google.android.apps.tachyon", // Meet (formerly Duo)
            "us.zoom.videomeetings", "com.atlassian.android.jira.core", "com.atlassian.confluence.server",
            "com.linkedin.android", "com.github.android", "com.figma.mirror", "notion.id", "com.asana.app",
            "com.trello", "com.clickup.app",
        ).forEach { put(it, "work") }
        // Video streaming: its own group (watching is for parked/charging).
        listOf(
            "com.google.android.youtube", "com.google.android.apps.youtube.kids", "com.netflix.mediaclient",
            "com.megogo.application", "tv.twitch.android.app", "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus", "com.hbo.hbonow", "com.wbd.stream", "com.kyivstar.tv.mobile",
            "ua.sweet.tv", "com.plexapp.android", "org.videolan.vlc", "com.google.android.videos",
            "com.zhiliaoapp.musically", "com.apple.atve.androidtv.appletv", "com.formulaone.production",
        ).forEach { put(it, "video") }
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

    /** Not offered or allowed on the car: [HIDDEN] and camera apps. */
    fun blocked(pkg: String) = pkg in HIDDEN || pkg in cameraApps

    /**
     * Apps that take photos or video, asked from Android (whatever camera app
     * the phone has): the car screen must not become a way to look through the
     * phone's camera. Refreshed with each app list.
     */
    @Volatile var cameraApps: Set<String> = emptySet()
        private set

    private val CAMERA_INTENTS = listOf(
        android.provider.MediaStore.ACTION_IMAGE_CAPTURE, android.provider.MediaStore.ACTION_VIDEO_CAPTURE,
        android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA,
    )

    private fun findCameraApps(pm: PackageManager): Set<String> = CAMERA_INTENTS.flatMap { action ->
        try {
            pm.queryIntentActivities(Intent(action), 0).map { it.activityInfo.packageName }
        } catch (e: Exception) {
            emptyList()
        }
    }.toSet()

    /** Start an activity on a display. Runs `am` with our shell rights. */
    fun launch(pkg: String, cls: String, displayId: Int): Boolean {
        if (!VALID_NAME.matches(pkg) || !VALID_NAME.matches(cls) || blocked(pkg)) return false
        // Open on another display (the phone screen)? Close it there first so it
        // starts fresh on the car instead of being moved across displays (see
        // the DESTROY_CONTENT_ON_REMOVAL note in Pane.createDisplay).
        closeElsewhere(pkg, displayId)
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
        // Fast path: the task manager directly (no `am` process per task, so
        // the engine thread isn't blocked for seconds on a close-all).
        tasksOn(displayId)?.let { tasks ->
            val ids = tasks.filter { (_, pkgs) -> pkg == null || pkg in pkgs }.map { it.first }
            ids.forEach { removeTask(it) }
            return ids.size
        }
        val list = am("stack", "list") ?: return 0
        val ids = rootTasks(list, displayId, pkg)
        ids.forEach { am("stack", "remove", it.toString()) }
        return ids.size
    }

    /**
     * Packages that must never run on the car display, even when an allowed
     * app opens them itself (an in-app "open settings" or "rate us" link): the
     * launcher can't start them ([HIDDEN]), and this closes them if they
     * appear anyway. The keyboard's own settings open as Gboard activities.
     */
    val DENIED_ON_CAR = HIDDEN + setOf("com.google.android.inputmethod.latin")

    /** Close tasks of [DENIED_ON_CAR] packages on a display; returns the packages closed. */
    fun closeDenied(displayId: Int): Set<String> {
        val tasks = tasksOn(displayId) ?: return emptySet()
        val closed = HashSet<String>()
        for ((id, pkgs) in tasks) {
            val bad = pkgs.filter { it in DENIED_ON_CAR || it in cameraApps }
            if (bad.isNotEmpty() && removeTask(id)) closed += bad
        }
        return closed
    }

    private val taskManager: Any? by lazy {
        try {
            Shell.service("activity_task", "android.app.IActivityTaskManager")
        } catch (e: Exception) {
            Log.w(TAG, "task manager unavailable, using am: $e")
            null
        }
    }

    /** Remove [pkg]'s tasks on displays other than [displayId]. */
    private fun closeElsewhere(pkg: String, displayId: Int) {
        val atm = taskManager ?: return
        try {
            val all = atm.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) as List<*>
            taskInfos(all).filter { (id, pkgs, display) -> display >= 0 && display != displayId && pkg in pkgs && id > 0 }
                .forEach { (id, _, display) ->
                    Log.i(TAG, "$pkg: closing its task on display $display before starting it on the car")
                    removeTask(id)
                }
        } catch (e: Exception) {
            Log.w(TAG, "task list: $e")
        }
    }

    /** Whether [pkg] has a task on [displayId]; null if that can't be read. */
    fun isOn(pkg: String, displayId: Int): Boolean? = tasksOn(displayId)?.any { (_, pkgs) -> pkg in pkgs }

    /** Root tasks on a display with the packages in them (hidden API); null if unavailable. */
    private fun tasksOn(displayId: Int): List<Pair<Int, Set<String>>>? {
        val atm = taskManager ?: return null
        return try {
            val list = atm.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
                .invoke(atm, displayId) as List<*>
            taskInfos(list).map { (id, pkgs, _) -> id to pkgs }
        } catch (e: Exception) {
            Log.w(TAG, "task list: $e")
            null
        }
    }

    /** (task id, packages in it, display id) from RootTaskInfo objects. */
    private fun taskInfos(list: List<*>): List<Triple<Int, Set<String>, Int>> =
            list.filterNotNull().map { info ->
                val c = info.javaClass
                val pkgs = HashSet<String>()
                for (f in listOf("baseActivity", "topActivity", "realActivity", "origActivity")) {
                    (runCatching { c.getField(f).get(info) }.getOrNull() as? ComponentName)?.let { pkgs += it.packageName }
                }
                (runCatching { c.getField("childTaskNames").get(info) }.getOrNull() as? Array<*>)?.forEach { name ->
                    (name as? String)?.let { ComponentName.unflattenFromString(it)?.packageName }?.let { pkgs += it }
                }
                val display = runCatching { c.getField("displayId").getInt(info) }.getOrDefault(-1)
                Triple(c.getField("taskId").getInt(info), pkgs, display)
            }

    private fun removeTask(id: Int): Boolean = try {
        val atm = taskManager!!
        atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, id) as Boolean
    } catch (e: Exception) {
        am("stack", "remove", id.toString()) != null
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
