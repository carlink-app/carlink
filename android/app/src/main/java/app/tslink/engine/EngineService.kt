package app.tslink.engine

import android.app.PendingIntent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import kotlin.system.exitProcess

/** Shizuku user service: hosts the engine in a process with shell rights. */
class EngineService : IEngine.Stub() {
    private var engine: Engine? = null
    private var client: IEngineClient? = null
    // What the running engine was started with.
    private var code = ""
    private var port = 0
    private var allow = ""
    private val pairing = PairingStatus()
    private val appUid = AppUid(::installedUid, ::installedSigner)

    init {
        // Shizuku keeps this process when TSLink is uninstalled: it would go on
        // holding the car display, the port and the phone's power state with
        // shell rights. Check now and then; once TSLink is gone, stop and exit.
        Thread({
            while (true) {
                Thread.sleep(UNINSTALL_CHECK_MS)
                val gone = try {
                    appUid.gone()
                } catch (t: Throwable) {
                    Log.w(TAG, "uninstall check: $t")
                    false // a hiccup is not an uninstall; check again later
                }
                if (gone) {
                    Log.i(TAG, "TSLink was uninstalled (or replaced by another signer): stopping")
                    destroy()
                }
            }
        }, "uninstall-watch").apply { isDaemon = true }.start()
    }

    @Synchronized
    override fun start(code: String, port: Int, libPath: String, devAllow: String): String {
        if (!fromTSLink()) return "denied"
        engine?.let { running ->
            if (code == this.code) return "already running"
            // A new code (Unpair all cars while another link started TSLink):
            // the old one must stop working now, not at the next restart.
            running.stop(waitMs = 2_000)
            engine = null
        }
        // Shizuku started with root: Android refuses the car display to uid 0
        // with the shell's identity ("packageName must match the calling uid").
        if (android.os.Process.myUid() == 0) return ROOT_ERROR
        return try {
            // Our own installed library, never a caller-supplied path (this runs as shell).
            val dir = Shell.context.packageManager.getApplicationInfo(APP_PACKAGE, 0).nativeLibraryDir
            System.load("$dir/libtslink_core.so")
            Shell.init()
            // Defense in depth: release builds never widen who may connect.
            val dev = if (app.tslink.BuildConfig.DEBUG) devAllow else ""
            this.code = code
            this.port = port
            allow = allowList(dev)
            engine = retryWhileInUse { Engine(code, port, allow) }.also { it.toApp = toApp() }
            resyncApp()
            if (dev.isNotEmpty()) Log.w(TAG, "DEV: also accepting the car page from $dev")
            "running on :$port"
        } catch (t: Throwable) {
            Log.e(TAG, "engine start failed", t)
            "error: $t"
        }
    }

    /**
     * Pair a car from the one-time secret in its QR code: the core seals the
     * pairing code with it for the car page waiting on the pairing port (or
     * arriving within [PAIRING_MS]). The running engine keeps serving cars.
     */
    @Synchronized
    override fun startPairing(secret: String): String {
        if (!fromTSLink()) return "denied"
        val current = engine ?: return "error: TSLink is off, start it first"
        val s = pairingSecret(secret) ?: return "not a TSLink pairing code"
        // Waiting first: with the page already there, the core reports the hand-over during pair().
        val gen = pairing.begin()
        current.onPaired = { remote -> pairing.paired(gen, remote) }
        current.pair(s)
        Thread { Thread.sleep(PAIRING_MS); pairing.expire(gen) }.start()
        Log.i(TAG, "pairing: waiting for the car page (up to ${PAIRING_MS / 1000} s)")
        return "pairing"
    }

    override fun pairingResult(): String = if (fromTSLink()) pairing.value else ""

    // Synchronized: one at a time (a double tap), and not alongside start/stop.
    @Synchronized
    override fun selfTest(): String {
        if (!fromTSLink()) return "denied"
        // Encoders are a limited resource: not while a car's panes hold them
        // (connected, or within the grace period after a disconnect).
        if (engine?.hasPanes == true) return "error: the car is using the phone's encoder; disconnect it first (or wait a minute)"
        if (android.os.Process.myUid() == 0) return ROOT_ERROR
        return try {
            Shell.init()
            SelfTest.run().toString(2)
        } catch (t: Throwable) {
            Log.e(TAG, "self-test failed", t)
            "error: $t"
        }
    }

    /** A new engine starts empty: the app sends notifications, icons and what plays again. */
    private fun resyncApp() = toApp()?.invoke(JSONObject().put("t", "resync"))

    /** "stopped", or the engine's state plus the cert fingerprint the car page shows too. */
    override fun status(): String = engine?.let { "${it.status} · cert ${it.fingerprint()}" } ?: "stopped"

    @Synchronized
    override fun stop() {
        if (!fromTSLink()) return
        pairing.reset()
        // Wait for the port to be free: a start right after (Unpair all cars) binds it again.
        engine?.stop(waitMs = 2_000)
        engine = null
    }

    override fun carHotspot(on: Boolean): String = if (!fromTSLink()) "denied" else try {
        Hotspot.start(car = on)
    } catch (t: Throwable) {
        Log.e(TAG, "hotspot failed", t)
        "error: $t"
    }

    override fun openNotification(intent: PendingIntent?, pkg: String, displayId: Int) {
        if (fromTSLink()) engine?.openNotification(intent, pkg, displayId)
    }

    override fun logs(): String {
        if (!fromTSLink()) return ""
        val p = ProcessBuilder("logcat", "-d", "-t", "400", "-v", "time", "-s",
            "tslink", "tslink-core", "tslink-auto", "tslink-link", "tslink-notif")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    override fun hotspotOff(): String = if (!fromTSLink()) "denied" else try {
        Hotspot.off()
    } catch (t: Throwable) {
        Log.e(TAG, "hotspot off failed", t)
        "error: $t"
    }

    /** Only the tslink app may link (Shizuku gives this binder to it alone; belt and braces). */
    private fun fromTSLink(): Boolean = appUid.isApp(Binder.getCallingUid())

    @Synchronized
    override fun setClient(c: IEngineClient?) {
        if (!fromTSLink()) return
        client = c
        c?.asBinder()?.linkToDeath(object : IBinder.DeathRecipient {
            override fun binderDied() = synchronized(this@EngineService) {
                if (client === c) {
                    client = null
                    engine?.toApp = null
                }
            }
        }, 0)
        engine?.toApp = toApp()
    }

    private fun toApp(): ((JSONObject) -> Unit)? {
        val c = client ?: return null
        return { m ->
            try {
                c.toApp(m.toString())
            } catch (e: Exception) {
                Log.w(TAG, "app link: $e")
            }
        }
    }

    override fun fromApp(json: String) {
        if (fromTSLink()) engine?.fromApp(json)
    }

    override fun destroy() {
        // Let the car get a clean close and the encoders be released before the process goes.
        synchronized(this) {
            engine?.stop(waitMs = 2_000)
            engine = null
        }
        exitProcess(0)
    }

    private companion object {
        const val PAIRING_MS = 120_000L
        const val UNINSTALL_CHECK_MS = 10_000L
        const val ROOT_ERROR = "error: Shizuku is running with root. TSLink needs it started without root: " +
            "stop Shizuku, then start it with Wireless debugging (or USB) instead of root."
    }
}

/** [raw] as the engine's stand-in code while pairing (upper case), or null unless it is exactly a page secret. */
internal fun pairingSecret(raw: String): String? = raw.uppercase().takeIf(app.tslink.ui.Pairing::isSecret)

/**
 * Run [bind] again while the port is still held ("Address already in use"):
 * a just-stopped engine (pairing hand-back) or a dying old engine process
 * releases it a moment later. Other errors, and the last try's, are thrown.
 */
internal fun <T> retryWhileInUse(tries: Int = 10, waitMs: Long = 200, sleep: (Long) -> Unit = Thread::sleep, bind: () -> T): T {
    repeat(tries - 1) {
        try {
            return bind()
        } catch (e: java.io.IOException) {
            if (e.message?.contains("in use") != true) throw e
            sleep(waitMs)
        }
    }
    return bind()
}

/**
 * TSLink's uid, as callers are checked against it. Looked up again when a
 * caller doesn't match: this process can outlive an uninstall + install
 * (Shizuku keeps it), which gives the app a new uid; a cached one would
 * deny the new app everything ("denied"). The signer seen first is pinned:
 * another app installed under the name, with another key, is not TSLink.
 * The signer is only read when the uid changed: the checks stay cheap.
 */
internal class AppUid(private val uidOf: () -> Int?, private val signerOf: () -> String?) {
    @Volatile private var signer: String? = null
    /** The last uid that passed the signer check. */
    @Volatile private var trusted: Int? = null

    /** TSLink's uid now, or null if it isn't installed (or another signer is). */
    @Synchronized
    private fun current(): Int? {
        val uid = uidOf() ?: return null.also { trusted = null }
        if (uid == trusted) return uid
        val now = signerOf()
        val pinned = signer ?: now.also { signer = it }
        trusted = uid.takeIf { now != null && now == pinned }
        return trusted
    }

    /** TSLink was uninstalled (or replaced by an app with another key). */
    fun gone(): Boolean = current() == null

    fun isApp(caller: Int): Boolean = caller == trusted || caller == current()
}

/**
 * The phone app's view of the last QR pairing: "", "waiting", "paired <car
 * address>", "expired". Each scan is a generation, so a late report of an
 * older scan, or one from before Stop, changes nothing.
 */
internal class PairingStatus {
    private var gen = 0

    @Volatile var value = ""
        private set

    /** A scan: wait for its car page. */
    @Synchronized
    fun begin(): Int {
        value = "waiting"
        return ++gen
    }

    @Synchronized
    fun paired(g: Int, remote: String) {
        if (g == gen) value = "paired $remote"
    }

    @Synchronized
    fun expire(g: Int) {
        if (g == gen && value == "waiting") value = "expired"
    }

    @Synchronized
    fun reset() {
        gen++
        value = ""
    }
}

/** app.tslink's uid now, or null if it isn't installed (cheap: no certificates). */
private fun installedUid(): Int? = try {
    Shell.context.packageManager.getPackageUid(APP_PACKAGE, 0)
} catch (_: android.content.pm.PackageManager.NameNotFoundException) {
    null
}

/** app.tslink's signing certificates (SHA-256, sorted, joined), or null if it isn't installed. */
private fun installedSigner(): String? = try {
    val info = Shell.context.packageManager.getPackageInfo(APP_PACKAGE, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
    info.signingInfo?.apkContentsSigners.orEmpty()
        .map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
        .sorted().joinToString(",")
} catch (_: android.content.pm.PackageManager.NameNotFoundException) {
    null
}
