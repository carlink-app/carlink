package app.tslink.ui

/** The car page's pairing QR code: `TSLINK-PAIR:<one-time secret>`. */
object Pairing {
    const val PREFIX = "TSLINK-PAIR:"

    /** The secret from a scanned QR code, or null if it isn't a TSLink pairing code. */
    fun secretFrom(raw: String?): String? {
        if (raw == null || !raw.startsWith(PREFIX, ignoreCase = true)) return null
        val s = raw.substring(PREFIX.length).trim()
        return s.takeIf { it.length in 16..64 && it.all { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' } }?.uppercase()
    }
}
