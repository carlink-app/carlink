package app.tslink.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

object Codecs {
    /** Highest format level for [configure] (all optional keys). */
    const val TOP_LEVEL = 3

    fun mime(codec: String): String = if (codec == "hevc") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /** A video encoder on this phone: [hardware] if the platform says so (or, before Android 10, guessed from the name). */
    data class Encoder(val name: String, val hardware: Boolean, val types: List<String>)

    fun encoders(): List<Encoder> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { t -> t.startsWith("video/") } }
        .map { info ->
            val hw = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly else !softwareName(info.name)
            Encoder(info.name, hw, info.supportedTypes.filter { it.startsWith("video/") })
        }

    /** Android's own software codecs (and the old Google ones). */
    fun softwareName(name: String): Boolean = name.startsWith("c2.android.") || name.startsWith("OMX.google.")

    /**
     * Encoder to use for [mime], best first: hardware before software, in the
     * platform's order otherwise (vendors list their preferred one first).
     */
    fun ranked(list: List<Encoder>, mime: String): List<Encoder> =
        list.filter { e -> e.types.any { it.equals(mime, ignoreCase = true) } }.sortedBy { if (it.hardware) 0 else 1 }

    /**
     * Whether to offer [codec] to the car: H.264 with any encoder, HEVC only
     * with a hardware one (software HEVC can't keep up at car sizes; H.264 is
     * the better fallback).
     */
    fun hasEncoder(codec: String): Boolean {
        val r = ranked(encoders(), mime(codec))
        return if (codec == "hevc") r.any { it.hardware } else r.isNotEmpty()
    }

    /** The best encoder for [codec] (see [ranked]). */
    fun createEncoder(codec: String): MediaCodec {
        val mime = mime(codec)
        val best = ranked(encoders(), mime).firstOrNull() ?: return MediaCodec.createEncoderByType(mime)
        return try {
            MediaCodec.createByCodecName(best.name)
        } catch (e: Exception) {
            Log.w(TAG, "encoder ${best.name}: $e, using the default")
            MediaCodec.createEncoderByType(mime)
        }
    }

    /**
     * Configure [encoder] for the car stream. Optional keys go from most to
     * least desirable; encoders reject ones they don't support (with
     * IllegalArgumentException, or a CodecException on some software and
     * older encoders), so retry with fewer, down to the bare minimum.
     */
    fun configure(encoder: MediaCodec, mime: String, width: Int, height: Int, bitrate: Int) {
        fun format(level: Int) = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            // Long GOP; keyframes are requested when a client (re)connects or
            // the sender has to skip frames.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            for ((key, value) in optionalKeys(level)) {
                if (value is Long) setLong(key, value) else setInteger(key, value.toInt())
            }
        }
        val level = firstAccepted(
            TOP_LEVEL,
            retryable = { it is IllegalArgumentException || it is MediaCodec.CodecException },
            attempt = { encoder.configure(format(it), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) },
            refused = { level, e -> Log.w(TAG, "encoder ${encoder.codecInfo.name} refused format level $level: $e") },
            reset = { encoder.reset() },
        )
        if (level < TOP_LEVEL) Log.w(TAG, "encoder ${encoder.codecInfo.name} accepted format level $level of $TOP_LEVEL")
    }

    /**
     * The optional format keys at [level] (0 = none, [TOP_LEVEL] = all), in
     * insertion order; each level adds to the one below. Long values go in
     * with setLong, the rest with setInteger.
     */
    fun optionalKeys(level: Int): Map<String, Number> = buildMap {
        if (level >= 1) {
            // Static screen: resend the last frame every 100 ms so the car
            // stays in sync without a constant 60 fps stream.
            put(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }
        if (level >= 2) {
            put(MediaFormat.KEY_MAX_B_FRAMES, 0)
            put(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        if (level >= 3) {
            put(MediaFormat.KEY_PRIORITY, 0) // realtime
            put(MediaFormat.KEY_LATENCY, 1)
            // Keep the encoder clocked up between frames (lower encode latency).
            put(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        }
    }

    /**
     * Calls [attempt] with [top], [top]-1, ... 0 until one doesn't throw, and
     * returns that level. A [retryable] failure is reported to [refused], then
     * [reset] runs before the next level; level 0's failure, or any other
     * exception, is rethrown.
     */
    fun firstAccepted(
        top: Int,
        retryable: (Exception) -> Boolean,
        attempt: (Int) -> Unit,
        refused: (Int, Exception) -> Unit,
        reset: () -> Unit,
    ): Int {
        for (level in top downTo 0) {
            try {
                attempt(level)
                return level
            } catch (e: Exception) {
                if (!retryable(e)) throw e
                refused(level, e)
                if (level == 0) throw e
                reset()
            }
        }
        throw IllegalArgumentException("no levels to try: $top")
    }
}
