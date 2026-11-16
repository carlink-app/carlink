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
    private val appUid by lazy { Shell.context.packageManager.getPackageUid(APP_PACKAGE, 0) }

    @Synchronized
    override fun start(code: String, port: Int, libPath: String, devAllow: String): String {
        if (!fromTSLink()) return "denied"
        if (engine != null) return "already running"
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
            engine = Engine(code, port, allow).also { it.toApp = toApp() }
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
        val current = engine ?: return "start the engine first"
        val s = secret.filter { it.isLetterOrDigit() }.uppercase()
        if (s.length !in 16..64) return "not a TSLink pairing code"
        current.stop(waitMs = 2_000)
        val gen = ++pairingGen
        engine = Engine(s, port, allow, handover = code).also { e ->
            e.toApp = toApp()
            // A moment for the message to leave before the engine restarts.
            e.onPaired = { Thread { Thread.sleep(1_500); endPairing(gen) }.start() }
        }
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
        engine = Engine(code, port, allow).also { it.toApp = toApp() }
        Log.i(TAG, "pairing: done, normal engine back")
    }

    /** "stopped", or the engine's state plus the cert fingerprint the car page shows too. */
    override fun status(): String = engine?.let { "${it.status} · cert ${it.fingerprint()}" } ?: "stopped"

    @Synchronized
    override fun stop() {
        if (!fromTSLink()) return
        pairingGen++ // a pending pairing end must not restart a stopped engine
        engine?.stop()
        engine = null
    }

    override fun carHotspot(on: Boolean): String = if (!fromTSLink()) "denied" else try {
        Hotspot.start(car = on)
    } catch (t: Throwable) {
        Log.e(TAG, "hotspot failed", t)
        "error: $t"
    }

    override fun notificationAccess(on: Boolean): String {
        if (!fromTSLink()) return "denied"
        val component = "$APP_PACKAGE/$APP_PACKAGE.notify.Listener"
        val p = ProcessBuilder("cmd", "notification", if (on) "allow_listener" else "disallow_listener", component)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        return if (p.waitFor() == 0 && out.isEmpty()) (if (on) "car notifications on" else "car notifications off") else "error: $out"
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
    }
}
