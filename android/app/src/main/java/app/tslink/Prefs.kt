package app.tslink

import android.content.Context
import java.io.File
import java.security.SecureRandom

/** App settings shared by the UI and the car auto-start receiver. */
/** Shizuku's package: setup checks for it, and the car never shows it. */
const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

object Prefs {
    const val PORT = 4433
    private const val FILE = "tslink"
    /** Pairing codes and the car page's QR secrets (no 0/O, 1/I). */
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun code(ctx: Context): String = prefs(ctx).getString("code", null) ?: newCode(ctx)

    /** 12 random characters (60 bits), shown as XXXX-XXXX-XXXX. */
    fun newCode(ctx: Context): String {
        val rnd = SecureRandom()
        val code = (1..12).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("").chunked(4).joinToString("-")
        prefs(ctx).edit().putString("code", code).apply()
        return code
    }

    /** Bluetooth address of the car; null = no auto-start. */
    fun carAddress(ctx: Context): String? = prefs(ctx).getString("car_bt", null)

    fun setCar(ctx: Context, address: String?, name: String?) =
        prefs(ctx).edit().putString("car_bt", address).putString("car_bt_name", name).apply()

    fun carName(ctx: Context): String? = prefs(ctx).getString("car_bt_name", null)

    /**
     * The user (or auto-start) wants the engine running: whoever binds it
     * next (app, notification link) restarts it if it died (process killed,
     * Shizuku restarted, app updated). On by default, so TSLink starts as
     * soon as Shizuku allows it; Stop (or the car leaving) turns it off.
     */
    fun engineWanted(ctx: Context): Boolean = prefs(ctx).getBoolean("engine_wanted", true)
    fun setEngineWanted(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("engine_wanted", on).apply()

    /** Whether the running engine/hotspot were started by the car connecting (so it may stop them). */
    fun autoStarted(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_started", false)
    fun setAutoStarted(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_started", on).apply()

    /** Message text of notifications in the car: like the lock screen (default), always, or never. */
    enum class CarText { LOCK_SCREEN, ALWAYS, NEVER }

    const val CAR_TEXT_KEY = "car_text"

    fun carText(ctx: Context): CarText = parseCarText(prefs(ctx).getString(CAR_TEXT_KEY, null))

    /** A stored [CarText] name; missing or unknown (e.g. from a newer version) means the default. */
    fun parseCarText(stored: String?): CarText =
        runCatching { CarText.valueOf(stored ?: "") }.getOrDefault(CarText.LOCK_SCREEN)

    fun setCarText(ctx: Context, v: CarText) = prefs(ctx).edit().putString(CAR_TEXT_KEY, v.name).apply()

    /** For listening to changes (the notification listener re-sends on a change). */
    fun shared(ctx: Context): android.content.SharedPreferences = prefs(ctx)

    /** The setup wizard was shown once (it opens by itself on first launch only). */
    fun setupSeen(ctx: Context): Boolean = prefs(ctx).getBoolean("setup_seen", false)
    fun setSetupSeen(ctx: Context) = prefs(ctx).edit().putBoolean("setup_seen", true).apply()

    /** Setup steps the user marked done (settings TSLink can't read), by name. */
    fun setupTicked(ctx: Context): Set<String> = prefs(ctx).getStringSet("setup_ticked", null).orEmpty().toSet()
    fun setSetupTicked(ctx: Context, names: Set<String>) = prefs(ctx).edit().putStringSet("setup_ticked", names).apply()

    /** Last time the car connected (ms), to cancel a pending stop after a short dropout. */
    fun lastCarConnect(ctx: Context): Long = prefs(ctx).getLong("car_connect_at", 0)
    fun setLastCarConnect(ctx: Context, at: Long) = prefs(ctx).edit().putLong("car_connect_at", at).apply()

    /**
     * Developer switch: extra addresses the car page may connect from besides
     * the car hotspot, e.g. a desktop for testing. `files/dev_allow` holds
     * IPv4 addresses or subnets (`192.0.2.10`, `10.0.2.0/24`) or `any`,
     * separated by commas or whitespace. Only debug builds read it, and only
     * `adb shell run-as app.tslink` can write it (other apps can't write our
     * files). Returns the valid entries, comma-separated, or "" if none.
     */
    fun devAllow(ctx: Context): String {
        if (!BuildConfig.DEBUG) return ""
        val f = File(ctx.filesDir, "dev_allow")
        if (!f.isFile || f.length() > 1024) return ""
        return parseDevAllow(f.readText())
    }

    internal fun parseDevAllow(text: String): String =
        text.split(Regex("[,\\s]+")).filter { it == "any" || DEV_ALLOW_ENTRY.matches(it) }.distinct().joinToString(",")

    private val DEV_ALLOW_ENTRY = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(/([0-9]|[12]\d|3[0-2]))?$""")

    fun libPath(ctx: Context): String = File(ctx.applicationInfo.nativeLibraryDir, "libtslink_core.so").path
}
