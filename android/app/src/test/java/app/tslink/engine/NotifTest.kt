package app.tslink.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NotifTest {
    private fun notif(vararg kv: Pair<String, Any>) = JSONObject(mapOf("id" to "n0123abcd", "pkg" to "com.whatsapp", *kv))

    @Test fun sanitizeKeepsValidFieldsAndCapsLengths() {
        val m = Notif.sanitize(notif("app" to "WhatsApp", "title" to "a".repeat(500), "text" to "b".repeat(500), "kind" to "msg"))!!
        assertEquals("notif", m.getString("t"))
        assertEquals(120, m.getString("title").length)
        assertEquals(300, m.getString("text").length)
        assertEquals("msg", m.getString("kind"))
        assertFalse(m.has("icon"))
    }

    @Test fun sanitizeRejectsBadIdsAndPackages() {
        assertNull(Notif.sanitize(notif("id" to "../../x")))
        assertNull(Notif.sanitize(notif("id" to "")))
        assertNull(Notif.sanitize(notif("id" to "a".repeat(65))))
        assertNull(Notif.sanitize(notif("pkg" to "com.x; rm -rf")))
    }

    @Test fun sanitizeFiltersIconsAndKinds() {
        val png = "data:image/png;base64,AAAA"
        assertEquals(png, Notif.sanitize(notif("icon" to png))!!.getString("icon"))
        assertFalse(Notif.sanitize(notif("icon" to "javascript:alert(1)"))!!.has("icon"))
        assertFalse(Notif.sanitize(notif("icon" to png + "A".repeat(20_000)))!!.has("icon"))
        assertEquals("other", Notif.sanitize(notif("kind" to "<b>"))!!.getString("kind"))
        // Unknown fields are not passed through.
        assertFalse(Notif.sanitize(notif("evil" to "x"))!!.has("evil"))
    }

    @Test fun sanitizeEdgeLimits() {
        assertNull(Notif.sanitize(JSONObject().put("id", "n1"))) // no pkg
        assertNull(Notif.sanitize(JSONObject().put("pkg", "com.x"))) // no id
        assertNull(Notif.sanitize(notif("pkg" to "")))
        assertNull(Notif.sanitize(notif("pkg" to "a".repeat(201))))
        assertEquals("a".repeat(200), Notif.sanitize(notif("pkg" to "a".repeat(200)))!!.getString("pkg"))
        assertEquals("A-z_0".padEnd(64, '9'), Notif.sanitize(notif("id" to "A-z_0".padEnd(64, '9')))!!.getString("id"))
        assertNull(Notif.sanitize(notif("id" to "n1\n")))
        // App label is capped at 60.
        assertEquals(60, Notif.sanitize(notif("app" to "a".repeat(100)))!!.getString("app").length)
        // Exact limits are kept.
        val m = Notif.sanitize(notif("title" to "t".repeat(120), "text" to "x".repeat(300)))!!
        assertEquals("t".repeat(120), m.getString("title"))
        assertEquals("x".repeat(300), m.getString("text"))
    }

    @Test fun sanitizeDefaultsMissingFields() {
        val m = Notif.sanitize(notif())!!
        assertEquals("", m.getString("app"))
        assertEquals("", m.getString("title"))
        assertEquals("", m.getString("text"))
        assertEquals("other", m.getString("kind"))
        assertEquals(setOf("t", "id", "pkg", "app", "title", "text", "kind"), m.keys().asSequence().toSet())
        // A spoofed type is overwritten.
        assertEquals("notif", Notif.sanitize(notif("t" to "error"))!!.getString("t"))
    }

    @Test fun sanitizeKeepsAllKnownKinds() {
        for (k in listOf("msg", "call", "nav", "other")) assertEquals(k, Notif.sanitize(notif("kind" to k))!!.getString("kind"))
        assertEquals("other", Notif.sanitize(notif("kind" to "MSG"))!!.getString("kind"))
    }

    @Test fun sanitizeIconSizeBoundary() {
        val prefix = "data:image/png;base64,"
        val max = prefix + "A".repeat(16 * 1024 - prefix.length)
        assertEquals(max, Notif.sanitize(notif("icon" to max))!!.getString("icon"))
        assertFalse(Notif.sanitize(notif("icon" to max + "A"))!!.has("icon"))
        assertFalse(Notif.sanitize(notif("icon" to ""))!!.has("icon"))
        assertFalse(Notif.sanitize(notif("icon" to "data:image/svg+xml;base64,AAAA"))!!.has("icon"))
    }
}
