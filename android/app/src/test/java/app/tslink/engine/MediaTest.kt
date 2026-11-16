package app.tslink.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {
    private fun msg() = JSONObject().put("t", "media").put("pkg", "com.spotify.music").put("app", "Spotify")
        .put("title", "Song").put("artist", "Band").put("playing", true).put("next", true)

    @Test fun keepsTheFields() {
        val m = Media.sanitize(msg())!!
        assertEquals("Song", m.getString("title"))
        assertEquals("Band", m.getString("artist"))
        assertTrue(m.getBoolean("playing"))
        assertTrue(m.getBoolean("next"))
        assertFalse(m.getBoolean("prev"))
        assertFalse(m.has("art"))
    }

    @Test fun none() = assertTrue(Media.sanitize(JSONObject().put("none", true))!!.getBoolean("none"))

    @Test fun rejectsBadInput() {
        assertNull(Media.sanitize(msg().put("pkg", "bad pkg!")))
        assertNull(Media.sanitize(msg().put("title", "")))
    }

    @Test fun limitsTextAndArt() {
        val m = Media.sanitize(msg().put("title", "x".repeat(500)).put("art", "javascript:alert(1)"))!!
        assertEquals(120, m.getString("title").length)
        assertFalse(m.has("art"))
        val big = "data:image/jpeg;base64," + "A".repeat(40_000)
        assertFalse(Media.sanitize(msg().put("art", big))!!.has("art"))
        assertTrue(Media.sanitize(msg().put("art", "data:image/jpeg;base64,AAAA"))!!.has("art"))
    }
}
