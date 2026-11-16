package app.tslink.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class BindRetryTest {
    private val inUse = IOException("Address already in use (os error 98)")

    @Test
    fun retriesWhileThePortIsStillHeld() {
        var tries = 0
        val slept = mutableListOf<Long>()
        val r = retryWhileInUse(tries = 5, waitMs = 200, sleep = { slept += it }) {
            if (++tries < 3) throw inUse
            "bound"
        }
        assertEquals("bound", r)
        assertEquals(3, tries)
        assertEquals(listOf(200L, 200L), slept)
    }

    @Test
    fun givesUpAfterTheLastTry() {
        var tries = 0
        assertThrows(IOException::class.java) {
            retryWhileInUse(tries = 4, waitMs = 1, sleep = {}) { tries++; throw inUse }
        }
        assertEquals(4, tries)
    }

    @Test
    fun otherErrorsAreNotRetried() {
        var tries = 0
        assertThrows(IOException::class.java) {
            retryWhileInUse(tries = 5, waitMs = 1, sleep = {}) { tries++; throw IOException("permission denied") }
        }
        assertEquals(1, tries)
    }
}
