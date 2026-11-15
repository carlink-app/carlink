// Shell-process environment: system context and identity for hidden APIs.
// Adapted from scrcpy (Workarounds.java, FakeContext.java), Apache-2.0,
// https://github.com/Genymobile/scrcpy
package app.carlink.engine

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import android.os.Process
import android.util.Log

const val TAG = "carlink"

/** The carlink app itself (the engine runs as shell, outside it). */
const val APP_PACKAGE = "app.carlink"

/** The car joins the hotspot on this subnet (see Hotspot); only it may connect. */
const val CAR_SUBNET = "9.9.0.0/24"

/** The core's allow list: the car subnet plus [devAllow] entries (see Prefs.devAllow); `any` = everyone. */
fun allowList(devAllow: String): String {
    val extra = devAllow.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return if ("any" in extra) "" else (listOf(CAR_SUBNET) + extra).joinToString(",")
}

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object Shell {
    const val PACKAGE = "com.android.shell"

    private val atClass: Class<*> by lazy { Class.forName("android.app.ActivityThread") }

    @Volatile
    private var systemContext: Context? = null

    /** Prepare an ActivityThread so framework managers work in app_process. */
    @Synchronized
    fun init() {
        if (systemContext != null) return
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
        var at = atClass.getMethod("currentActivityThread").invoke(null)
        if (at == null) {
            at = atClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            atClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, at)
            atClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(at, true)
            fillConfigurationController(at)
            fillAppInfo(at)
        }
        systemContext = atClass.getDeclaredMethod("getSystemContext").invoke(at) as Context
    }

    private fun fillConfigurationController(at: Any) {
        try {
            val ccClass = Class.forName("android.app.ConfigurationController")
            val atInternal = Class.forName("android.app.ActivityThreadInternal")
            val cc = ccClass.getDeclaredConstructor(atInternal).apply { isAccessible = true }.newInstance(at)
            atClass.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(at, cc)
        } catch (t: Throwable) {
            Log.d(TAG, "no ConfigurationController: $t")
        }
    }

    private fun fillAppInfo(at: Any) {
        try {
            val abdClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val abd = abdClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val ai = ApplicationInfo().apply { packageName = PACKAGE }
            abdClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(abd, ai)
            atClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(at, abd)
        } catch (t: Throwable) {
            Log.d(TAG, "could not fill app info: $t")
        }
    }

    val context: Context by lazy {
        init()
        ShellContext(systemContext!!)
    }
}

/** Context that identifies as the shell package (we run with the shell uid). */
class ShellContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = Shell.PACKAGE
    override fun getOpPackageName(): String = Shell.PACKAGE
    override fun getApplicationContext(): Context = this
    override fun getAttributionTag(): String? = null

    override fun getAttributionSource(): AttributionSource =
        if (Build.VERSION.SDK_INT >= 31) {
            AttributionSource.Builder(Process.SHELL_UID).setPackageName(Shell.PACKAGE).build()
        } else {
            super.getAttributionSource()
        }
}
