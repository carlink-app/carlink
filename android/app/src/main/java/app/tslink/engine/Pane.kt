package app.tslink.engine

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * One car pane: a virtual display rendered straight into a hardware encoder's
 * input surface. Encoded access units go to the native core as they come out.
 */
class Pane(
    val id: Int,
    /** Size the car asked for; the encoded size may be smaller (see [fit]). */
    val requestedWidth: Int,
    val requestedHeight: Int,
    val dpi: Int,
    val codec: String,
    /** Initial bitrate level, permille of [maxBitrate] (see core/src/rate.rs). */
    level: Int,
    /** Native core handle; 0 once the engine stopped. */
    private val core: () -> Long,
) {
    private val mime = Codecs.mime(codec)
    private val encoder: MediaCodec = MediaCodec.createEncoderByType(mime)
    private var surface: Surface? = null
    private var display: VirtualDisplay? = null
    private var thread: Thread? = null
    @Volatile private var running = true
    private var config: ByteArray? = null
    private var joinBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)

    val width: Int
    val height: Int
    /** Keeps the car display awake (see keepAwake); released with the pane. */
    private var awake: android.os.PowerManager.WakeLock? = null

    val displayId: Int
    val touch: Touch
    /**
     * The display didn't get its own display group (seen on a Pixel with
     * Android 17), so it sleeps when the phone sleeps: see PhonePower.
     */
    val sharesPhonePower: Boolean

    init {
        try {
            val caps = encoder.codecInfo.getCapabilitiesForType(mime).videoCapabilities
            val (w, h) = fit(requestedWidth, requestedHeight, caps.widthAlignment, caps.heightAlignment, caps::isSizeSupported)
            width = w
            height = h
            if (w != requestedWidth || h != requestedHeight) {
                Log.w(TAG, "pane $id: encoder ${encoder.codecInfo.name} can't do ${requestedWidth}x$requestedHeight, using ${w}x$h")
            }
            configureEncoder(bitrate(level))
            val s = encoder.createInputSurface().also { surface = it }
            encoder.start()
            // A scaled-down display keeps the layout the car asked for (same dp size).
            val d = createDisplay(scaledDpi(dpi, requestedWidth, width), s).also { display = it }
            displayId = d.display.displayId
            keyboardOnCar(displayId)
            sharesPhonePower = displayGroupId(d.display) == 0
            Log.i(TAG, "pane $id: display $displayId, ${if (sharesPhonePower) "shares the phone's power group" else "own power group"}")
            // Own group: a wake lock on it keeps the car display on while idle.
            // Shared group: PhonePower takes care of it.
            if (!sharesPhonePower) awake = keepAwake(displayId)
            touch = Touch(displayId)
            thread = Thread(::drain, "pane-$id-out").apply { start() }
        } catch (t: Throwable) {
            // Hardware encoders are a limited resource: never leak one.
            release()
            throw t
        }
        Log.i(TAG, "pane $id: ${width}x$height@$dpi $codec, display $displayId, encoder ${encoder.codecInfo.name}")
    }

    private fun bitrate(level: Int) = (maxBitrate(codec).toLong() * level.coerceIn(1, 1000) / 1000).toInt()

    /** Follow the core's rate control without restarting the encoder. */
    fun setLevel(level: Int) = setParameter(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate(level))

    fun requestKeyframe() = setParameter(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)

    private fun setParameter(key: String, value: Int) {
        try {
            encoder.setParameters(Bundle().apply { putInt(key, value) })
        } catch (e: IllegalStateException) {
            // released
        }
    }

    private fun configureEncoder(bitrate: Int) {
        // Optional keys go from most to least desirable; encoders reject
        // unknown ones with IllegalArgumentException, so retry with fewer.
        fun format(level: Int) = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            // Long GOP; keyframes are requested when a client (re)connects or
            // the sender has to skip frames.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            // Static screen: resend the last frame every 100 ms so the car
            // stays in sync without a constant 60 fps stream.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (level >= 1) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
            if (level >= 2) {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                setInteger(MediaFormat.KEY_LATENCY, 1)
                // Keep the encoder clocked up between frames (lower encode latency).
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            }
        }
        for (level in 2 downTo 0) {
            try {
                encoder.configure(format(level), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                if (level < 2) Log.w(TAG, "encoder ${encoder.codecInfo.name} accepted format level $level of 2")
                return
            } catch (e: IllegalArgumentException) {
                if (level == 0) throw e
                encoder.reset()
            }
        }
    }

    @android.annotation.SuppressLint("WrongConstant") // hidden flags (trusted, own focus, ...), as scrcpy uses
    private fun createDisplay(dpi: Int, surface: Surface): VirtualDisplay {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            (1 shl 6) or // SUPPORTS_TOUCH
            (1 shl 7) or // ROTATES_WITH_CONTENT
            // DESTROY_CONTENT_ON_REMOVAL: when the car display goes, its apps
            // close instead of moving to the phone screen. Moving apps between
            // displays can make Android 17 restart the app itself onto ours,
            // which it isn't allowed to do, and system_server crashes.
            (1 shl 8)
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or (1 shl 10) or // TRUSTED
                (1 shl 11) or // OWN_DISPLAY_GROUP
                (1 shl 12) or // ALWAYS_UNLOCKED
                (1 shl 13) // TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) {
            // Not DEVICE_DISPLAY_GROUP: it only applies to virtual devices, and the
            // car display must stay in its OWN group so it keeps power (and touch)
            // while the phone sleeps.
            flags = flags or (1 shl 14) // OWN_FOCUS
        }
        val ctor = DisplayManager::class.java.getDeclaredConstructor(android.content.Context::class.java)
        ctor.isAccessible = true
        val dm = ctor.newInstance(Shell.context)
        return dm.createVirtualDisplay("tslink-$id", width, height, dpi, surface, flags)
    }

    private fun drain() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                encoder.dequeueOutputBuffer(info, 100_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (index < 0) continue
            val buf = try {
                encoder.getOutputBuffer(index)
            } catch (e: IllegalStateException) {
                break
            }
            if (running && buf != null && info.size > 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = ByteArray(info.size).also {
                        buf.position(info.offset)
                        buf.get(it)
                    }
                } else {
                    send(buf, info)
                }
            }
            try {
                encoder.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                break
            }
        }
    }

    private fun send(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val core = core()
        if (core == 0L) return
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val flags = if (key) NativeCore.FLAG_KEY else 0
        val cfg = config
        if (key && cfg != null && !startsWithParameterSets(buf, info.offset)) {
            // Encoder ignored PREPEND_HEADER_TO_SYNC_FRAMES: prepend SPS/PPS
            // so the car can configure its decoder from this keyframe.
            val total = cfg.size + info.size
            if (joinBuffer.capacity() < total) joinBuffer = ByteBuffer.allocateDirect(total * 2)
            joinBuffer.clear()
            joinBuffer.put(cfg)
            buf.position(info.offset).limit(info.offset + info.size)
            joinBuffer.put(buf)
            NativeCore.nativeSendFrame(core, id, joinBuffer, 0, total, info.presentationTimeUs, flags)
        } else {
            NativeCore.nativeSendFrame(core, id, buf, info.offset, info.size, info.presentationTimeUs, flags)
        }
    }

    private fun startsWithParameterSets(buf: ByteBuffer, offset: Int): Boolean {
        // Skip the start code (00 00 01 or 00 00 00 01) and read the NAL type.
        if (buf.limit() - offset < 6) return false
        var i = offset
        while (i < offset + 4 && buf.get(i).toInt() == 0) i++
        if (buf.get(i).toInt() != 1) return false
        val header = buf.get(i + 1).toInt() and 0xff
        return if (codec == "hevc") (header shr 1) and 0x3f in 32..34 else header and 0x1f == 7
    }

    /**
     * The car display has its own power group with its own idle timeout: with
     * nobody touching it (driving with a map open) it would doze and the car
     * would show a frozen frame. A screen wake lock on that display only keeps
     * it on; the phone's own screen still turns off. Hidden API, Android 12+.
     */
    @android.annotation.SuppressLint("PrivateApi", "WakelockTimeout")
    private fun keepAwake(displayId: Int): android.os.PowerManager.WakeLock? = try {
        val pm = Shell.context.getSystemService(android.os.PowerManager::class.java)
        @Suppress("DEPRECATION")
        val level = android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
        val lock = pm.javaClass.getMethod("newWakeLock", Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
            .invoke(pm, level, "tslink:pane$id", displayId) as android.os.PowerManager.WakeLock
        lock.setReferenceCounted(false)
        lock.acquire()
        lock
    } catch (e: Exception) {
        Log.w(TAG, "car display may sleep when idle: $e")
        null
    }

    /** Hidden DisplayInfo.displayGroupId; -1 if unreadable (treated as its own group). */
    @android.annotation.SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun displayGroupId(display: android.view.Display): Int = try {
        val info = Class.forName("android.view.DisplayInfo").getDeclaredConstructor().newInstance()
        android.view.Display::class.java.getMethod("getDisplayInfo", info.javaClass).invoke(display, info)
        info.javaClass.getField("displayGroupId").getInt(info)
    } catch (e: Exception) {
        Log.w(TAG, "display group unknown: $e")
        -1
    }

    /**
     * Show the on-screen keyboard on the car display, not the phone (as
     * scrcpy's --display-ime-policy=local). Hidden API, Android 11+.
     */
    private fun keyboardOnCar(displayId: Int) {
        try {
            val wm = Shell.service("window", "android.view.IWindowManager")!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, displayId, 0 /* WindowManager.DISPLAY_IME_POLICY_LOCAL */)
        } catch (e: Exception) {
            Log.w(TAG, "keyboard stays on the phone: ${Shell.cause(e)}")
        }
    }

    companion object {
        /** Density for a display shrunk from [requested] to [actual] px wide, so its dp size stays. */
        fun scaledDpi(dpi: Int, requested: Int, actual: Int): Int =
            if (actual >= requested) dpi else (dpi.toLong() * actual / requested).toInt().coerceAtLeast(72)

        /**
         * Bitrate at level 1000. In the car (short hotspot link, RTT ~5 ms)
         * the old caps (HEVC 9, H.264 15 Mbit/s) were reached within seconds
         * and held all session, so the cap, not the link, set the picture
         * quality. Rate control still backs off when the link gets busy.
         */
        fun maxBitrate(codec: String) = if (codec == "hevc") 16_000_000 else 20_000_000

        /**
         * Largest size with the requested aspect ratio that the encoder
         * supports, scaling down in 5% steps (e.g. a 2x-DPR window can exceed
         * the encoder's maximum). Dimensions round down to the alignments.
         */
        fun fit(w: Int, h: Int, alignW: Int, alignH: Int, supported: (Int, Int) -> Boolean): Pair<Int, Int> {
            val aw = maxOf(alignW, 2)
            val ah = maxOf(alignH, 2)
            for (step in 20 downTo 2) {
                val fw = w * step / 20 / aw * aw
                val fh = h * step / 20 / ah * ah
                if (fw > 0 && fh > 0 && supported(fw, fh)) return fw to fh
            }
            throw IllegalArgumentException("encoder supports no size near ${w}x$h")
        }
    }

    fun release() {
        awake?.release()
        running = false
        thread?.join() // drain loop wakes at least every 100 ms
        display?.release()
        try {
            encoder.stop()
        } catch (_: IllegalStateException) {
        }
        encoder.release()
        surface?.release()
        Log.i(TAG, "pane $id released")
    }
}
