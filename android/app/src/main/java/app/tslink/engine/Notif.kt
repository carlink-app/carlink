package app.tslink.engine

import app.tslink.notify.takeChars
import org.json.JSONObject

/** Notification payloads for the car: the limits from docs/PROTOCOL.md. */
object Notif {
    val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val PKG = Regex("^[A-Za-z0-9_.]{1,200}$")
    private val KINDS = setOf("msg", "call", "nav", "other")
    private const val MAX_ICON = 16 * 1024

    /** A clean `notif` message for the car, or null if [m] isn't valid. */
    fun sanitize(m: JSONObject): JSONObject? {
        val id = m.optString("id")
        val pkg = m.optString("pkg")
        if (!ID.matches(id) || !PKG.matches(pkg)) return null
        val icon = m.optString("icon").takeIf { it.length <= MAX_ICON && it.startsWith("data:image/png;base64,") }
        return JSONObject()
            .put("t", "notif")
            .put("id", id)
            .put("pkg", pkg)
            .put("app", m.optString("app").takeChars(60))
            .put("title", m.optString("title").takeChars(120))
            .put("text", m.optString("text").takeChars(300))
            .put("kind", m.optString("kind").takeIf { it in KINDS } ?: "other")
            .apply { if (icon != null) put("icon", icon) }
    }
}
