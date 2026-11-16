package app.tslink.engine

import android.annotation.SuppressLint
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.InputEvent
import org.json.JSONObject

/**
 * Which of the hidden Android APIs TSLink relies on exist on this phone.
 * Only looks the methods up, never calls them: safe to run any time. When an
 * Android update moves one, Device check shows which feature it costs.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
object Capabilities {
    private class Probe(val name: String, val feature: String, val check: () -> Unit)

    private fun method(cls: Class<*>, name: String, vararg params: Class<*>) { cls.getMethod(name, *params) }
    private fun hasMethod(cls: Class<*>, name: String) = check(cls.methods.any { it.name == name }) { "no $name" }
    private fun aidl(service: String, aidl: String): Class<*> = Shell.service(service, aidl)!!.javaClass
    private val int = Int::class.javaPrimitiveType!!
    private val long = Long::class.javaPrimitiveType!!
    private val bool = Boolean::class.javaPrimitiveType!!

    private val probes = listOf(
        Probe("injectInputEvent", "touch from the car") { method(InputManager::class.java, "injectInputEvent", InputEvent::class.java, int) },
        Probe("InputEvent.setDisplayId", "touch from the car") { method(InputEvent::class.java, "setDisplayId", int) },
        Probe("keyboard on display", "keyboard on the car screen") {
            val wm = aidl("window", "android.view.IWindowManager")
            if (Build.VERSION.SDK_INT >= 31) method(wm, "setDisplayImePolicy", int, int) else method(wm, "setShouldShowIme", int, bool)
        },
        Probe("isKeyguardLocked", "phone screen handling (lock state)") { method(aidl("window", "android.view.IWindowManager"), "isKeyguardLocked") },
        Probe("lockNow", "phone screen handling (lock before going dark)") { method(aidl("window", "android.view.IWindowManager"), "lockNow", android.os.Bundle::class.java) },
        Probe("PowerManager.wakeUp", "phone screen handling (wake)") { method(PowerManager::class.java, "wakeUp", long, int, String::class.java) },
        Probe("PowerManager.goToSleep", "phone screen handling (sleep after the drive)") { method(PowerManager::class.java, "goToSleep", long) },
        Probe("display wake lock", "car screen awake while idle (Android 12+; older ones give it its own power)") { method(PowerManager::class.java, "newWakeLock", int, String::class.java, int) },
        Probe("panel power", "phone panel off while the car runs (locked phone)") {
            if (Build.VERSION.SDK_INT >= 34) Class.forName("com.android.internal.os.ClassLoaderFactory") // DisplayControl lives in services.jar
            else method(Class.forName("android.view.SurfaceControl"), "setDisplayPowerMode", IBinder::class.java, int)
        },
        Probe("getAllRootTaskInfos", "start apps fresh on the car screen (Android 12+)") { method(aidl("activity_task", "android.app.IActivityTaskManager"), "getAllRootTaskInfos") },
        Probe("getAllRootTaskInfosOnDisplay", "close apps (falls back to `am`)") { method(aidl("activity_task", "android.app.IActivityTaskManager"), "getAllRootTaskInfosOnDisplay", int) },
        Probe("removeTask", "close apps (falls back to `am`)") { method(aidl("activity_task", "android.app.IActivityTaskManager"), "removeTask", int) },
        Probe("TetheringRequest static address", "car hotspot (fixed 9.9.0.1)") {
            val la = Class.forName("android.net.LinkAddress")
            method(Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder"), "setStaticIpv4Addresses", la, la)
        },
        Probe("startTethering", "car hotspot on/off") { hasMethod(Class.forName("android.net.TetheringManager"), "startTethering") },
        Probe("DisplayInfo", "detect a car screen sharing the phone's power") {
            method(android.view.Display::class.java, "getDisplayInfo", Class.forName("android.view.DisplayInfo"))
        },
    )

    /** name -> "ok" or what's missing, with the feature it serves. */
    fun probe(): JSONObject {
        val out = JSONObject()
        for (p in probes) {
            val r = try {
                p.check()
                "ok"
            } catch (t: Throwable) {
                "MISSING (${p.feature}): ${(Shell.cause(t).message ?: Shell.cause(t).javaClass.simpleName).take(80)}"
            }
            out.put(p.name, r)
        }
        return out
    }
}
