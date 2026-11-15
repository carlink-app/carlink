package app.carlink.engine

import android.content.Intent
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
                out.put(JSONObject().put("pkg", pkg).put("cls", cls).put("label", ri.loadLabel(pm).toString()).put("icon", icons[pkg] ?: icon(pm, ri) ?: ""))
                components += pkg to cls
            } catch (e: Exception) {
                Log.w(TAG, "skip $pkg: $e")
            }
        }
        return Listing(out, components, System.currentTimeMillis())
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

    /** Start an activity on a display. Runs `am` with our shell rights. */
    fun launch(pkg: String, cls: String, displayId: Int): Boolean {
        val valid = Regex("^[A-Za-z0-9_.$]+$")
        if (!valid.matches(pkg) || !valid.matches(cls) || pkg in HIDDEN) return false
        val p = ProcessBuilder(
            "am", "start", "--display", displayId.toString(), "--windowingMode", "1",
            "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER,
            "-n", "$pkg/$cls",
        ).redirectErrorStream(true).start()
        // `am` normally returns at once; never let a hung one stall the engine thread.
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "launch $pkg: am timed out")
            return false
        }
        val output = p.inputStream.bufferedReader().readText()
        val ok = p.exitValue() == 0 && !output.contains("Error")
        if (!ok) Log.w(TAG, "launch $pkg failed: $output")
        return ok
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
