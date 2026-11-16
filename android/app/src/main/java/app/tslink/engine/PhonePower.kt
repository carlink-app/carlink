// Keeps the car display alive on phones that put it in the phone's own power
// group. The panel-off technique is adapted from scrcpy (--turn-screen-off,
// Device.java / DisplayControl), Apache-2.0, https://github.com/Genymobile/scrcpy
package app.tslink.engine

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Some phones (Pixel, Android 17) don't give our virtual display its own
 * power group: when the phone sleeps, the car display dozes with it (frozen
 * picture, touch and keyboard ignored). While a car session runs, this keeps
 * the phone awake but with its own panel switched off ("dark"):
 *
 * - phone would sleep (screen timeout or power button while lit), or has been
 *   lit for the screen timeout (car touches count as phone activity, so the
 *   system timeout alone may never fire): lock it, wake it, switch the panel
 *   off, hold a screen wake lock so it doesn't time out;
 * - power button while dark: the driver wants the phone, so the panel comes
 *   back on (locked) and goes dark again after the screen timeout;
 * - session over: panel back on, phone put to sleep if we kept it awake.
 *
 * Going dark always locks first: the system locks only some seconds after a
 * timeout, and waking before that would leave the phone unlocked all drive.
 * All calls happen on the engine thread ([handler]).
 */
// The engine runs in the shell process (app_process via Shizuku), where hidden
// API restrictions don't apply; lint judges it by the app's target SDK.
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
class PhonePower(private val handler: Handler) {
    private val pm = Shell.context.getSystemService(PowerManager::class.java)
    @Suppress("DEPRECATION")
    private val lock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "tslink:dark").apply { setReferenceCounted(false) }
    private var active = false
    private var dark = false
    private var ticks = 0
    private var litSince = 0L
    private var wakeFailures = 0

    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            try {
                step()
            } catch (e: Exception) {
                Log.w(TAG, "phone power poll: ${Shell.cause(e)}") // never take the engine down
            }
            if (active) handler.postDelayed(this, POLL_MS)
        }

        private fun step() {
            val interactive = pm.isInteractive
            if (interactive && dark) ticks++
            val litFor = if (interactive && !dark) SystemClock.uptimeMillis() - litSince else 0L
            when (pollStep(interactive, dark, ticks, litFor, screenTimeoutMs())) {
                Step.LIGHTEN -> lighten()
                Step.DARKEN -> darken()
                Step.REAPPLY -> panel(OFF) // the system may switch the panel back on (e.g. a wake-up event)
                Step.NONE -> {}
            }
        }
    }

    /** A pane on a display that shares the phone's power state exists. */
    fun start() {
        if (active) return
        active = true
        wakeFailures = 0
        litSince = SystemClock.uptimeMillis()
        Log.i(TAG, "car display shares the phone's power: keeping the phone awake with its panel off while the car is connected")
        if (!pm.isInteractive) darken()
        handler.postDelayed(poll, POLL_MS)
    }

    /** No panes left: give the phone back its normal behaviour. */
    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(poll)
        handler.removeCallbacks(reapply)
        if (dark) {
            dark = false
            panel(NORMAL)
            lock.release()
            sleep() // it was off for the user: leave it off
        }
    }

    private fun darken() {
        lockNow()
        if (!wake()) {
            // Without waking, dark/lit would flap every poll and the car stays dozing.
            if (++wakeFailures >= MAX_WAKE_FAILURES) {
                Log.w(TAG, "can't keep the phone awake; the car display sleeps with the phone")
                active = false
            }
            return
        }
        wakeFailures = 0
        dark = true
        ticks = 0
        lock.acquire()
        panel(OFF)
        // The wake-up animation can switch the panel on again just after: repeat.
        handler.removeCallbacks(reapply)
        REAPPLY_AFTER_MS.forEach { handler.postDelayed(reapply, it) }
        Log.i(TAG, "phone panel off, locked=${keyguardLocked()} (car keeps running)")
    }

    private fun lighten() {
        wake()
        dark = false
        litSince = SystemClock.uptimeMillis()
        handler.removeCallbacks(reapply)
        panel(NORMAL)
        lock.release()
        Log.i(TAG, "phone panel on (power button)")
    }

    private val reapply = Runnable { if (dark) panel(OFF) }

    /** The phone's screen timeout (Settings > Display), at least [MIN_LIT_MS]. */
    private fun screenTimeoutMs(): Long = try {
        Settings.System.getLong(Shell.context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
    } catch (e: Exception) {
        DEFAULT_LIT_MS
    }.coerceAtLeast(MIN_LIT_MS)

    /** Through the window service: KeyguardManager doesn't work in the shell process. */
    private fun keyguardLocked(): Boolean? = try {
        val wm = Shell.service("window", "android.view.IWindowManager")!!
        wm.javaClass.getMethod("isKeyguardLocked").invoke(wm) as Boolean
    } catch (e: Exception) {
        null
    }

    /** Show the keyguard now (as the power button would); no-op without a lock screen. */
    private fun lockNow() {
        try {
            val wm = Shell.service("window", "android.view.IWindowManager")!!
            wm.javaClass.getMethod("lockNow", android.os.Bundle::class.java).invoke(wm, null)
        } catch (e: Exception) {
            Log.w(TAG, "lock failed: ${Shell.cause(e)}")
        }
    }

    /** True if the phone is awake afterwards. */
    private fun wake(): Boolean {
        try {
            pm.javaClass.getMethod("wakeUp", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                .invoke(pm, SystemClock.uptimeMillis(), WAKE_REASON_APPLICATION, "tslink:car")
        } catch (e: Exception) {
            Log.w(TAG, "wake failed: ${Shell.cause(e)}")
        }
        return pm.isInteractive
    }

    private fun sleep() {
        try {
            pm.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType).invoke(pm, SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "sleep failed: ${Shell.cause(e)}")
        }
    }

    private fun panel(mode: Int) {
        val tokens = displayTokens ?: return
        try {
            val set = Class.forName("android.view.SurfaceControl")
                .getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
            tokens.forEach { set.invoke(null, it, mode) }
            // If the engine dies while the panel is off, the next engine start restores it.
            if (mode == OFF) MARKER.writeText("dark") else MARKER.delete()
        } catch (e: Exception) {
            Log.w(TAG, "panel power: ${Shell.cause(e)}")
        }
    }

    /**
     * Token of the panel currently showing the phone screen (display 0). On a
     * foldable only that one: switching "all" back on would power the closed
     * panel too. Falls back to every panel if the address is unreadable.
     */
    private val displayTokens: List<IBinder>? by lazy {
        try {
            val control: Class<*> = if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ moved this out of SurfaceControl into services.jar.
                val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
                val loader = factory.getDeclaredMethod(
                    "createClassLoader", String::class.java, String::class.java, String::class.java,
                    ClassLoader::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
                ).invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
                val cls = loader.loadClass("com.android.server.display.DisplayControl")
                val load = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
                load.isAccessible = true
                load.invoke(Runtime.getRuntime(), cls, "android_servers")
                cls
            } else {
                Class.forName("android.view.SurfaceControl")
            }
            val ids = (control.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray).toList()
            val active = activePhysicalId()?.takeIf { it in ids }
            val token = control.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            (if (active != null) listOf(active) else ids).mapNotNull { token.invoke(null, it) as IBinder? }
        } catch (e: Throwable) {
            Log.w(TAG, "can't switch the phone panel off; it stays on while the car is connected: ${Shell.cause(e)}")
            null
        }
    }

    /** Physical id behind display 0 (hidden Display.getAddress()), or null. */
    private fun activePhysicalId(): Long? = try {
        val dm = Shell.context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val address = android.view.Display::class.java.getMethod("getAddress").invoke(dm.getDisplay(0))
        address?.javaClass?.getMethod("getPhysicalDisplayId")?.invoke(address) as Long?
    } catch (e: Exception) {
        null
    }

    /** Engine start: if a previous engine died with the panel off, switch it back on. */
    fun restoreAfterCrash() {
        if (!MARKER.exists()) return
        // Asleep: the system powers the panel on at the next wake by itself;
        // forcing it on now would light the screen of a sleeping phone.
        if (pm.isInteractive) {
            Log.w(TAG, "previous engine left the phone panel off: restoring")
            panel(NORMAL)
        } else {
            MARKER.delete()
        }
    }

    internal enum class Step { NONE, DARKEN, LIGHTEN, REAPPLY }

    internal companion object {
        /**
         * What one poll does.
         * - Asleep: a timeout or the power button, so lit -> dark and dark -> lit.
         * - Awake and lit for the screen timeout ([litFor] >= [timeout]): dark.
         *   Car touches keep the phone's own timeout from firing.
         * - Awake and dark: re-assert "off" every [REAPPLY_TICKS]th poll
         *   ([tick] counts awake-and-dark polls).
         */
        fun pollStep(interactive: Boolean, dark: Boolean, tick: Int, litFor: Long = 0, timeout: Long = Long.MAX_VALUE): Step = when {
            !interactive -> if (dark) Step.LIGHTEN else Step.DARKEN
            dark -> if (tick % REAPPLY_TICKS == 0) Step.REAPPLY else Step.NONE
            litFor >= timeout -> Step.DARKEN
            else -> Step.NONE
        }

        const val POLL_MS = 500L
        /** Re-assert "off" every few seconds while dark (cheap, no flicker). */
        const val REAPPLY_TICKS = 10
        val REAPPLY_AFTER_MS = listOf(150L, 600L, 1500L)
        const val MAX_WAKE_FAILURES = 3
        const val MIN_LIT_MS = 15_000L
        const val DEFAULT_LIT_MS = 30_000L
        const val OFF = 0 // SurfaceControl.POWER_MODE_OFF
        const val NORMAL = 2 // SurfaceControl.POWER_MODE_NORMAL
        const val WAKE_REASON_APPLICATION = 2 // PowerManager.WAKE_REASON_APPLICATION
        /** Shell-writable; present while the panel is forced off. */
        val MARKER = File("/data/local/tmp/tslink-panel-off")
    }
}
