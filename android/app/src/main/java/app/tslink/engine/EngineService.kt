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
    private val appUid by lazy { Shell.context.packageManager.getPackageUid(APP_PACKAGE, 0) }

    @Synchronized
    override fun start(code: String, port: Int, libPath: String, devAllow: String): String {
        if (!fromTSLink()) return "denied"
        if (engine != null) return "already running"
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
     * Pair a car from the one-time secret in its QR code: for up to
     * [PAIRING_MS] the engine accepts that secret instead of the pairing code
     * and sends the real code to the first car that proves it has the secret.
     * Then back to normal.
     */
    @Synchronized
    override fun startPairing(secret: String): String {
        if (!fromTSLink()) return "denied"
        val current = engine ?: return "error: TSLink is off, start it first"
        val s = pairingSecret(secret) ?: return "not a TSLink pairing code"
        current.stop(waitMs = 2_000)
        val gen = ++pairingGen
        engine = try {
            retryWhileInUse { Engine(s, port, allow, handover = code) }
        } catch (t: Throwable) {
            Log.e(TAG, "pairing: engine start failed", t)
            engine = runCatching { retryWhileInUse { Engine(code, port, allow) }.also { it.toApp = toApp() } }.getOrNull()
            return "error: $t"
        }.also { e ->
            e.toApp = toApp()
            // The car closes the session once it has the code: switch back right
            // then. A car that doesn't is switched after a moment for the message to leave.
            e.onPaired = { remote ->
                pairing = "paired $remote"
                Thread { Thread.sleep(1_500); endPairing(gen) }.start()
            }
            e.onCarLeft = { Thread { endPairing(gen) }.start() }
        }
        pairing = "waiting"
        Thread { Thread.sleep(PAIRING_MS); endPairing(gen) }.start()
        Log.i(TAG, "pairing: waiting for the car (up to ${PAIRING_MS / 1000} s)")
        return "pairing"
    }

    /** Back to the normal engine, if pairing attempt [gen] is still the current one. */
    @Synchronized
    private fun endPairing(gen: Int) {
        if (gen != pairingGen) return
        pairingGen++
        val e = engine ?: return
        e.stop(waitMs = 2_000)
        if (pairing == "waiting") pairing = "expired"
        // Runs on its own thread: a failure must not take the process down, and
        // must leave "stopped" (the app shows Off, Pair starts it again).
        engine = try {
            retryWhileInUse { Engine(code, port, allow) }.also { it.toApp = toApp() }
        } catch (t: Throwable) {
            Log.e(TAG, "pairing: normal engine didn't come back", t)
            null
        }
        resyncApp()
        Log.i(TAG, "pairing: done, normal engine back")
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
    private fun fromTSLink(): Boolean = Binder.getCallingUid() == appUid

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
