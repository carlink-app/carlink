package app.carlink.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * Restart the Wi-Fi hotspot on a fixed public-looking subnet (phone 9.9.0.1,
 * car 9.9.0.2): the Tesla browser refuses private addresses. Uses the system
 * TetheringManager API (Android 11+), which needs shell rights.
 */
@SuppressLint("PrivateApi")
object Hotspot {
    const val PHONE = "9.9.0.1"
    private const val TETHERING_WIFI = 0

    private fun manager(): Any {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "tethering") as IBinder?
            ?: error("tethering service not found")
        val c = Class.forName("android.net.TetheringManager").getDeclaredConstructor(Context::class.java, Supplier::class.java)
        c.isAccessible = true
        return c.newInstance(Shell.context, Supplier { binder })
    }

    /** Restart the hotspot: on the fixed car subnet, or normal (car = false). */
    fun start(car: Boolean): String {
        val tm = manager()
        stop(tm)
        Thread.sleep(1500)
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val b = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
        if (car) {
            val la = Class.forName("android.net.LinkAddress")
            val lac = la.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
            builderClass.getMethod("setStaticIpv4Addresses", la, la).invoke(b, lac.newInstance("$PHONE/24"), lac.newInstance("9.9.0.2/24"))
        }
        builderClass.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType).invoke(b, true)
        builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType).invoke(b, false)
        val request = builderClass.getMethod("build").invoke(b)

        val cbClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val done = CountDownLatch(1)
        var result = "timeout"
        val cb = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { _, m, args ->
            when (m.name) {
                "onTetheringStarted" -> { result = "ok"; done.countDown() }
                "onTetheringFailed" -> { result = "failed: error ${args?.getOrNull(0)}"; done.countDown() }
            }
            null
        }
        tm.javaClass.getMethod("startTethering", request.javaClass, Executor::class.java, cbClass)
            .invoke(tm, request, Executor { it.run() }, cb)
        done.await(15, TimeUnit.SECONDS)
        return result
    }

    /** Hotspot off (car gone). */
    fun off(): String {
        stop(manager())
        return "off"
    }

    private fun stop(tm: Any) {
        tm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, TETHERING_WIFI)
    }
}
