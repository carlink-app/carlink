package app.tslink.notify

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPickTest {
    private data class S(val name: String, val playing: Boolean)

    @Test fun prefersThePlayingSession() {
        val list = listOf(S("podcast", false), S("music", true), S("radio", true))
        assertEquals("music", pickSession(list) { it.playing }?.name)
    }

    @Test fun elseTheNewest() {
        assertEquals("podcast", pickSession(listOf(S("podcast", false), S("music", false))) { it.playing }?.name)
        assertNull(pickSession(emptyList<S>()) { it.playing })
    }

    @Test fun bufferingCountsAsPlaying() {
        assertTrue(playingState(PlaybackState.STATE_PLAYING))
        assertTrue(playingState(PlaybackState.STATE_BUFFERING))
        assertFalse(playingState(PlaybackState.STATE_PAUSED))
        assertFalse(playingState(PlaybackState.STATE_STOPPED))
        assertFalse(playingState(null))
    }
}
