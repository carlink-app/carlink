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
    // What the running engine was started with, to come back after pairing.
    private var code = ""
    private var port = 0
    private var allow = ""
    /** Bumped per pairing attempt: a stale timeout must not end a newer one. */
    private var pairingGen = 0
    /** Last pairing attempt for the app: "", "waiting", "paired <car address>", "expired". */
    @Volatile private var pairing = ""
    private val appUid = AppUid {
        try {
            Shell.context.packageManager.getPackageUid(APP_PACKAGE, 0)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            -1 // uninstalled: nobody is TSLink
        }
    }

    init {
        // Shizuku keeps this process when TSLink is uninstalled: it would go on
        // holding the car display, the port and the phone's power state with
        // shell rights. Check now and then; once TSLink is gone, stop and exit.
        Thread({
            while (true) {
                Thread.sleep(UNINSTALL_CHECK_MS)
                if (appUid.gone()) {
                    Log.i(TAG, "TSLink was uninstalled: stopping")
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
        val gen = ++pairingGen
        current.onPaired = { remote -> if (gen == pairingGen) pairing = "paired $remote" }
        current.pair(s)
        pairing = "waiting"
        Thread { Thread.sleep(PAIRING_MS); expirePairing(gen) }.start()
        Log.i(TAG, "pairing: waiting for the car page (up to ${PAIRING_MS / 1000} s)")
        return "pairing"
    }

    /** No car page took pairing attempt [gen] in time. */
    @Synchronized
    private fun expirePairing(gen: Int) {
        if (gen == pairingGen && pairing == "waiting") pairing = "expired"
    }

    override fun pairingResult(): String = if (fromTSLink()) pairing else ""

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
        pairingGen++ // a pending pairing end must not restart a stopped engine
        pairing = ""
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
 * deny the new app everything ("denied").
 */
internal class AppUid(private val lookup: () -> Int) {
    @Volatile private var cached: Int? = null

    /** TSLink was uninstalled (the lookup finds no package). */
    fun gone(): Boolean = lookup().also { cached = it } < 0

    fun isApp(caller: Int): Boolean {
        if (caller == cached) return true
        val now = lookup()
        cached = now
        return caller == now
    }
}
