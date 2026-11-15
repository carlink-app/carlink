package app.carlink.engine

import java.nio.ByteBuffer

/** JNI bridge to the Rust core (core/src/lib.rs): WebTransport server. */
object NativeCore {
    interface Listener {
        fun onSession(connected: Boolean, remote: String)
        fun onControl(json: String)
        fun onKeyframeNeeded(pane: Int)
        /** Bitrate level from the core's rate control, permille of the codec maximum. */
        fun onRate(level: Int)
    }

    const val FLAG_KEY = 1

    @JvmStatic external fun nativeStart(port: Int, code: String, allow: String, listener: Listener): Long
    @JvmStatic external fun nativeSendFrame(handle: Long, pane: Int, buf: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int): Boolean
    @JvmStatic external fun nativeSendControl(handle: Long, json: String)
    @JvmStatic external fun nativeStop(handle: Long)
    @JvmStatic external fun nativeCertHash(code: String): String
}
