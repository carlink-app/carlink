package app.tslink.auto

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.tslink.Prefs
import app.tslink.engine.EngineArgs
import app.tslink.engine.IEngine
import app.tslink.engine.startFor
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Car Bluetooth connects: start the engine and the car hotspot. Disconnects:
 * stop both. ACL broadcasts are exempt from the implicit-broadcast ban, so a
 * manifest receiver gets them even when the app isn't running.
 */
class CarReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "tslink-auto"
        /** Bluetooth drops briefly (call handoff, car screen reboot): wait before stopping. */
        const val STOP_DELAY_MS = 20_000L
    }

    enum class Action { START, STOP, NONE }

    override fun onReceive(context: Context, intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val action = decide(intent.action, device?.address, Prefs.carAddress(context))
        if (action == Action.NONE) return
        Log.i(TAG, "car ${if (action == Action.START) "connected" else "disconnected"}")
        val app = context.applicationContext
        if (action == Action.STOP) {
            // Only undo our own start, and not for a short dropout (CarStopJob checks again).
            if (Prefs.autoStarted(app)) CarStopJob.schedule(app, STOP_DELAY_MS)
            return
        }
        Prefs.setLastCarConnect(app, System.currentTimeMillis())
        val pending = goAsync()
        carEvents.execute {
            try {
                withEngine { e ->
                    // Already running (started by hand, or a dropout): leave it as it is.
                    if (e.status() != "stopped") return@withEngine
                    Log.i(TAG, "engine: ${e.startFor(app)}")
                    Log.i(TAG, "hotspot: ${e.carHotspot(true)}")
                    Prefs.setAutoStarted(app, true)
                    Prefs.setEngineWanted(app, true)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "auto start failed: $t")
            } finally {
                pending.finish()
            }
        }
    }
}

/** Car connect/disconnect handling, one at a time, in arrival order (no start/stop races). */
internal val carEvents: ExecutorService = Executors.newSingleThreadExecutor()

/** Broadcast receivers and jobs get ~10 s; leave some margin. */
private const val ENGINE_BUDGET_MS = 9_000L

/** Bind the engine service via Shizuku (waiting for its binder), run [block]. */
internal fun withEngine(block: (IEngine) -> Unit) {
    val deadline = System.currentTimeMillis() + ENGINE_BUDGET_MS
    val ready = CountDownLatch(1)
    val listener = Shizuku.OnBinderReceivedListener { ready.countDown() }
    Shizuku.addBinderReceivedListenerSticky(listener)
    try {
        ready.await(ENGINE_BUDGET_MS / 3, TimeUnit.MILLISECONDS)
    } finally {
        Shizuku.removeBinderReceivedListener(listener)
    }
    check(Shizuku.pingBinder()) { "Shizuku not running" }
    check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "no Shizuku permission" }
    var engine: IEngine? = null
    val connected = CountDownLatch(1)
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            engine = IEngine.Stub.asInterface(binder)
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {}
    }
    Shizuku.bindUserService(EngineArgs.args, connection)
    try {
        connected.await(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
        block(engine ?: error("engine service didn't start"))
    } finally {
        // The engine is a daemon: unbinding leaves it running.
        Shizuku.unbindUserService(EngineArgs.args, connection, false)
    }
}

/** Which way to go for an ACL event; pure for tests. */
fun decide(action: String?, device: String?, car: String?): CarReceiver.Action = when {
    car == null || device == null || !device.equals(car, ignoreCase = true) -> CarReceiver.Action.NONE
    action == BluetoothDevice.ACTION_ACL_CONNECTED -> CarReceiver.Action.START
    action == BluetoothDevice.ACTION_ACL_DISCONNECTED -> CarReceiver.Action.STOP
    else -> CarReceiver.Action.NONE
}
