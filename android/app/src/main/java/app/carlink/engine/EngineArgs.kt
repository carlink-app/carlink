package app.carlink.engine

import android.content.ComponentName
import android.content.Context
import app.carlink.BuildConfig
import app.carlink.Prefs
import rikka.shizuku.Shizuku

/** One definition of the Shizuku user service, so every binder in the app reaches the same process. */
object EngineArgs {
    val args: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(APP_PACKAGE, EngineService::class.java.name))
            .daemon(true)
            .processNameSuffix("engine")
            .version(BuildConfig.ENGINE_VERSION)
    }
}

/** Start the engine with the app's settings ([code] defaults to the saved pairing code). */
fun IEngine.startFor(ctx: Context, code: String = Prefs.code(ctx)): String =
    start(code, Prefs.PORT, Prefs.libPath(ctx), Prefs.devAllow(ctx))
