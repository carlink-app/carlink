package app.tslink.ui

import app.tslink.Prefs
import java.net.URI

/**
 * The car page's pairing QR code (web/src/pairing.ts): the link
 * `https://tslink.app/pair#<one-time secret>`, so the phone's own camera
 * opens TSLink too (App Link).
 */
object Pairing {
    const val SECRET_LENGTH = 20
    private const val HOST = "tslink.app"
    private val PATHS = setOf("/pair", "/pair/")

    /** Exactly what the page makes: 20 characters from the alphabet (upper case). */
    fun isSecret(s: String): Boolean = s.length == SECRET_LENGTH && s.all { it in Prefs.ALPHABET }

    /** The secret from a scanned QR code or an opened link, or null if it isn't a TSLink pairing code. */
    fun secretFrom(raw: String?): String? {
        if (raw == null) return null
        return fromLink(raw.trim())?.uppercase()?.takeIf(::isSecret)
    }

    /** Only https://tslink.app/pair with the secret after # (never sent to a server); nothing else. */
    private fun fromLink(text: String): String? {
        val uri = try {
            URI(text)
        } catch (_: java.net.URISyntaxException) {
            return null
        }
        val ok = uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(HOST, ignoreCase = true) &&
            uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null &&
            uri.rawPath in PATHS
        return if (ok) uri.rawFragment else null
    }
}
