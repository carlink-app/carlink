package app.carlink.engine

import android.os.Looper
import android.util.Log

/**
 * Standalone entry point for development (emulator, or a phone over adb):
 *
 *   adb shell CLASSPATH=<apk> app_process / app.carlink.engine.EngineMain \
 *       --code ABCD-EFGH-JKLM --port 4433 --lib <nativeLibDir>/libcarlink_core.so \
 *       [--allow 9.9.0.0/24 | --allow any] [--hotspot]
 *
 * --allow defaults to the car hotspot subnet; "any" is for the emulator/dev.
 *
 * In normal use the app starts the same engine through Shizuku (EngineService).
 */
object EngineMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.toList().windowed(2, 1, partialWindows = true)
            .filter { it[0].startsWith("--") }
            .associate { it[0].removePrefix("--") to it.getOrNull(1) }
        val code = opts["code"] ?: error("--code required")
        val port = opts["port"]?.toInt() ?: 4433
        System.load(opts["lib"] ?: error("--lib required"))
        Shell.init()
        if ("hotspot" in opts) println("car hotspot: ${Hotspot.start(car = true)}")
        val allow = when (val a = opts["allow"]) {
            null -> CAR_SUBNET
            "any" -> ""
            else -> a
        }
        val engine = Engine(code, port, allow)
        println("carlink engine on :$port, cert ${NativeCore.nativeCertHash(code)}")
        Log.i(TAG, "engine started (app_process)")
        Runtime.getRuntime().addShutdownHook(Thread { engine.stop() })
        Looper.loop()
    }
}
