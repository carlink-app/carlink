package app.tslink.engine

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A test build switched the phone's camera off (Android's "Camera access"
 * privacy switch) while a car was connected; that was dropped (too strict:
 * no camera on the phone during a drive). This only undoes it if that build
 * left its marker behind. Camera apps are still kept off the car screen
 * (Apps.blocked).
 */
object CameraBlock {
    private val MARKER = File("/data/local/tmp/tslink-camera-blocked")

    fun restore() {
        if (!MARKER.exists()) return
        try {
            val p = ProcessBuilder("cmd", "sensor_privacy", "disable", "0", "camera").redirectErrorStream(true).start()
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                MARKER.delete()
                Log.i(TAG, "camera access restored")
            } else {
                p.destroyForcibly()
            }
        } catch (e: Exception) {
            Log.w(TAG, "camera privacy: $e")
        }
    }
}
