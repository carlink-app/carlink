package app.tslink.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

object Codecs {
    private const val TAG = "tslink"
    private const val TOP_LEVEL = 3

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
            if (level >= 1) {
                // Static screen: resend the last frame every 100 ms so the car
                // stays in sync without a constant 60 fps stream.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            }
            if (level >= 2) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
            if (level >= 3) {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                setInteger(MediaFormat.KEY_LATENCY, 1)
                // Keep the encoder clocked up between frames (lower encode latency).
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            }
        }
        for (level in TOP_LEVEL downTo 0) {
            try {
                encoder.configure(format(level), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                if (level < TOP_LEVEL) Log.w(TAG, "encoder ${encoder.codecInfo.name} accepted format level $level of $TOP_LEVEL")
                return
            } catch (e: Exception) {
                if (e !is IllegalArgumentException && e !is MediaCodec.CodecException) throw e
                Log.w(TAG, "encoder ${encoder.codecInfo.name} refused format level $level: $e")
                if (level == 0) throw e
                encoder.reset()
            }
        }
    }
}
