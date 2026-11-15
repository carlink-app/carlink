package app.carlink.engine

import android.media.MediaCodecList
import android.media.MediaFormat

object Codecs {
    fun mime(codec: String): String = if (codec == "hevc") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /** Whether a hardware (or any) encoder exists for "h264"/"hevc". */
    fun hasEncoder(codec: String): Boolean {
        val mime = mime(codec)
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }
}
