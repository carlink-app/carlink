package app.tslink.engine

import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device check without a car: what encoders the phone has, and how the one
 * TSLink picks keeps up with a moving test picture at a car-like size. Also
 * checks that a car display can be created. Runs in the engine (shell) process.
 */
object SelfTest {
    private const val TAG = "tslink"
    /** A car pane at the Tesla's default browser size is ~1036x918; this is the cap (capPane). */
    private val SIZES = listOf(1280 to 960, 1920 to 1080)
    private const val SECONDS = 3

    fun run(): JSONObject {
        val out = JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.SDK_INT)
            .put("soc", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
            .put("cores", Runtime.getRuntime().availableProcessors())
        val encoders = Codecs.encoders()
        out.put("capabilities", Capabilities.probe())
        out.put("encoders", JSONArray(encoders.map { "${it.name}${if (it.hardware) "" else " (software)"}: ${it.types.joinToString(",") { t -> t.removePrefix("video/") }}" }))
        val runs = JSONArray()
        for (codec in listOf("hevc", "h264")) {
            val offered = Codecs.hasEncoder(codec)
            for ((w, h) in SIZES) runs.put(if (offered) encode(codec, w, h) else JSONObject().put("codec", codec).put("size", "${w}x$h").put("skipped", "no suitable encoder"))
        }
        out.put("encode", runs)
        out.put("display", display())
        return out
    }

    /** Encode a moving picture for [SECONDS] at 60 fps; frame rate, latency and bitrate out. */
    private fun encode(codec: String, w: Int, h: Int): JSONObject {
        val r = JSONObject().put("codec", codec).put("size", "${w}x$h")
        val encoder = try { Codecs.createEncoder(codec) } catch (e: Exception) { return r.put("error", e.toString()) }
        r.put("encoder", encoder.codecInfo.name)
        val latencies = java.util.Collections.synchronizedList(ArrayList<Long>())
        var frames = 0
        var bytes = 0L
        var error: String? = null
        var input: android.view.Surface? = null
        try {
            Codecs.configure(encoder, Codecs.mime(codec), w, h, if (codec == "hevc") 16_000_000 else 20_000_000)
            val surface = encoder.createInputSurface().also { input = it }
            encoder.start()
            val drain = Thread {
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val i = try { encoder.dequeueOutputBuffer(info, 100_000) } catch (e: IllegalStateException) { break }
                    if (i < 0) continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        frames++
                        bytes += info.size
                        // Surface frames are stamped with System.nanoTime when posted: time in the encoder.
                        latencies += System.nanoTime() - info.presentationTimeUs * 1000
                    }
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    try { encoder.releaseOutputBuffer(i, false) } catch (e: IllegalStateException) { break }
                    if (end) break
                }
            }.apply { start() }
            val paint = Paint().apply { isAntiAlias = true }
            val start = SystemClock.elapsedRealtimeNanos()
            var n = 0
            while (SystemClock.elapsedRealtimeNanos() - start < SECONDS * 1_000_000_000L) {
                val c = surface.lockHardwareCanvas()
                // Detail that moves every frame, like a map being panned.
                c.drawColor(Color.rgb(20, 24, 30))
                for (k in 0 until 40) {
                    paint.color = Color.HSVToColor(floatArrayOf((k * 37f + n * 3) % 360, 0.7f, 0.9f))
                    val x = ((k * 97 + n * 9) % (w + 200)) - 100f
                    val y = ((k * 53 + n * 5) % (h + 200)) - 100f
                    c.drawCircle(x, y, 40f + k % 7 * 10, paint)
                }
                surface.unlockCanvasAndPost(c)
                n++
                val next = start + n * 16_666_667L
                val wait = next - SystemClock.elapsedRealtimeNanos()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            encoder.signalEndOfInputStream()
            drain.join(2_000)
            r.put("drawn", n)
        } catch (e: Exception) {
            error = e.toString()
            Log.w(TAG, "self-test $codec ${w}x$h: $e")
        } finally {
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
            input?.release() // also when drawing failed
        }
        // The encoder is released: the drain thread has stopped writing (or will, shortly). Copy, then sort.
        val sorted = synchronized(latencies) { latencies.toMutableList() }.apply { sort() }
        fun pct(p: Double) = if (sorted.isEmpty()) null else sorted[((sorted.size - 1) * p).toInt()] / 1_000_000.0
        return r.put("fps", frames / SECONDS.toDouble())
            .put("latencyMsP50", pct(0.5))
            .put("latencyMsP95", pct(0.95))
            .put("mbps", bytes * 8 / SECONDS / 1e6)
            .apply { if (error != null) put("error", error) }
    }

    /** Can a car display be made, and does it get its own power group? */
    private fun display(): JSONObject = try {
        val p = Pane(99, 1036, 918, 230, "h264", 500) { 0L }
        val r = JSONObject().put("ok", true).put("ownPowerGroup", !p.sharesPhonePower).put("size", "${p.width}x${p.height}")
        p.release()
        r
    } catch (e: Throwable) {
        JSONObject().put("ok", false).put("error", e.toString())
    }
}
