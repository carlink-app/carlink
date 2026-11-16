package app.tslink.ui

import app.tslink.Prefs

/** The car page's pairing QR code: `TSLINK-PAIR:<one-time secret>` (web/src/pairing.ts). */
object Pairing {
    const val PREFIX = "TSLINK-PAIR:"
    const val SECRET_LENGTH = 20

    /** Exactly what the page makes: 20 characters from the alphabet (upper case). */
    fun isSecret(s: String): Boolean = s.length == SECRET_LENGTH && s.all { it in Prefs.ALPHABET }

    /** The secret from a scanned QR code, or null if it isn't a TSLink pairing code. */
    fun secretFrom(raw: String?): String? {
        if (raw == null || !raw.startsWith(PREFIX, ignoreCase = true)) return null
        return raw.substring(PREFIX.length).trim().uppercase().takeIf(::isSecret)
    }
}
