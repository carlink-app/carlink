package app.carlink.notify

import android.app.PendingIntent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.carlink.Prefs
import app.carlink.engine.EngineArgs
import app.carlink.engine.startFor
import app.carlink.engine.IEngine
import app.carlink.engine.IEngineClient
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * App side of the engine link: binds the engine's Shizuku user service (the
 * same process MainActivity starts) and registers a client for callbacks.
 * Binder rather than a socket: SELinux lets apps call into a shell-domain
 * process over binder (that's how Shizuku works) but not connect to its sockets.
 *
 * Waits for Shizuku to be running and permitted; MainActivity asks for the
 * permission. Messages from the engine are delivered on the main thread.
 */
class EngineLink(
    private val ctx: android.content.Context,
    private val onConnected: () -> Unit,
    private val onMessage: (JSONObject) -> Unit,
) {
    companion object {
        private const val TAG = "carlink-link"
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var engine: IEngine? = null
    private var bound = false

    private val client = object : IEngineClient.Stub() {
        override fun toApp(json: String) {
            val m = try { JSONObject(json) } catch (e: Exception) { return }
            main.post { onMessage(m) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val e = IEngine.Stub.asInterface(binder)
            try {
                e.setClient(client)
                engine = e
                Log.i(TAG, "linked to engine")
                // The engine process came back (killed, Shizuku restart, update) but
                // nothing started it: do, if the user wants it running.
                if (Prefs.engineWanted(ctx)) {
                    Thread {
                        try {
                            if (e.status() == "stopped") Log.i(TAG, "restarted engine: ${e.startFor(ctx)}")
                        } catch (ex: Exception) {
                            Log.w(TAG, "restart: $ex")
                        }
                    }.start()
                }
                main.post(onConnected)
            } catch (ex: Exception) {
                Log.w(TAG, "setClient: $ex")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Engine process died or was removed: bind again (Shizuku restarts it on demand).
            engine = null
            bound = false
            main.postDelayed(::bind, 2_000)
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { main.post(::bind) }
    private val binderDead = Shizuku.OnBinderDeadListener { main.post { engine = null; bound = false } }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) main.post(::bind)
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permission)
    }

    private fun bind() {
        if (bound || !Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
        try {
            Shizuku.bindUserService(EngineArgs.args, connection)
            bound = true
        } catch (e: Exception) {
            Log.w(TAG, "bind: $e")
        }
    }

    /** Send from any thread; dropped when not linked (and a relink is attempted). */
    fun send(m: JSONObject) {
        val e = engine
        if (e == null) {
            main.post(::bind)
            return
        }
        try {
            e.fromApp(m.toString())
        } catch (ex: Exception) {
            Log.w(TAG, "send: $ex")
            engine = null
            main.post { bound = false; bind() }
        }
    }

    /** Have the engine open a notification on the car display (it may start activities; we may not). */
    fun openNotification(intent: PendingIntent?, pkg: String, display: Int) {
        try {
            engine?.openNotification(intent, pkg, display)
        } catch (e: Exception) {
            Log.w(TAG, "open: $e")
        }
    }

    fun close() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permission)
        try {
            engine?.setClient(null)
        } catch (_: Exception) {
        }
        engine = null
        // Unbind without killing the engine (daemon): the car may be streaming.
        if (bound && Shizuku.pingBinder()) Shizuku.unbindUserService(EngineArgs.args, connection, false)
        bound = false
    }
}
