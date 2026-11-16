package app.tslink.engine

import app.tslink.notify.takeChars
import org.json.JSONObject

/** Now-playing payloads for the car: the limits from docs/PROTOCOL.md. */
object Media {
    private val PKG = Regex("^[A-Za-z0-9_.]{1,200}$")
    const val MAX_ART = 32 * 1024
    val COMMANDS = setOf("play", "pause", "next", "prev")

    /** A clean `media` message for the car, or null if [m] isn't valid. */
    fun sanitize(m: JSONObject): JSONObject? {
        if (m.optBoolean("none")) return JSONObject().put("t", "media").put("none", true)
        val pkg = m.optString("pkg")
        val title = m.optString("title").takeChars(120)
        if (!PKG.matches(pkg) || title.isEmpty()) return null
        val art = m.optString("art").takeIf { it.length <= MAX_ART && (it.startsWith("data:image/jpeg;base64,") || it.startsWith("data:image/png;base64,")) }
        return JSONObject()
            .put("t", "media")
            .put("pkg", pkg)
            .put("app", m.optString("app").takeChars(60))
            .put("title", title)
            .put("artist", m.optString("artist").takeChars(120))
            .put("playing", m.optBoolean("playing"))
            .put("prev", m.optBoolean("prev"))
            .put("next", m.optBoolean("next"))
            .apply { if (art != null) put("art", art) }
    }
}
