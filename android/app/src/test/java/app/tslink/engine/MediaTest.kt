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

    @Test fun noneWinsAndDropsEverythingElse() {
        val m = Media.sanitize(msg().put("none", true))!!
        assertTrue(m.getBoolean("none"))
        assertFalse(m.has("title"))
        assertFalse(m.has("pkg"))
    }

    @Test fun packageNameRules() {
        assertNull(Media.sanitize(msg().put("pkg", "")))
        assertNull(Media.sanitize(JSONObject().put("title", "T")))
        assertNull(Media.sanitize(msg().put("pkg", "a".repeat(201))))
        assertNull(Media.sanitize(msg().put("pkg", "com.x/../y")))
        assertEquals("com.x_y.z1", Media.sanitize(msg().put("pkg", "com.x_y.z1"))!!.getString("pkg"))
    }

    @Test fun missingFieldsDefault() {
        val m = Media.sanitize(JSONObject().put("pkg", "com.a").put("title", "T"))!!
        assertEquals("", m.getString("app"))
        assertEquals("", m.getString("artist"))
        assertFalse(m.getBoolean("playing"))
        assertFalse(m.getBoolean("prev"))
        assertFalse(m.getBoolean("next"))
    }

    @Test fun capsAppAndArtist() {
        val m = Media.sanitize(msg().put("app", "a".repeat(100)).put("artist", "b".repeat(300)))!!
        assertEquals(60, m.getString("app").length)
        assertEquals(120, m.getString("artist").length)
    }

    @Test fun artFormats() {
        // MediaWatch sends "" while the cover is still being encoded.
        assertFalse(Media.sanitize(msg().put("art", ""))!!.has("art"))
        assertTrue(Media.sanitize(msg().put("art", "data:image/png;base64,AAAA"))!!.has("art"))
        assertFalse(Media.sanitize(msg().put("art", "data:image/svg+xml;base64,AAAA"))!!.has("art"))
        val atLimit = "data:image/jpeg;base64,".let { it + "A".repeat(32 * 1024 - it.length) }
        assertTrue(Media.sanitize(msg().put("art", atLimit))!!.has("art"))
        assertFalse(Media.sanitize(msg().put("art", atLimit + "A"))!!.has("art"))
    }

    @Test fun commands() = assertEquals(setOf("play", "pause", "next", "prev"), Media.COMMANDS)
}
