package app.tslink.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingStatusTest {
    @Test
    fun aScanThatPairsAtOnceStaysPaired() {
        // The core reports the hand-over during the pair() call itself (page already waiting).
        val s = PairingStatus()
        val g = s.begin()
        s.paired(g, "9.9.0.2:5000")
        s.expire(g)
        assertEquals("paired 9.9.0.2:5000", s.value)
    }

    @Test
    fun noPageInTimeExpires() {
        val s = PairingStatus()
        s.expire(s.begin())
        assertEquals("expired", s.value)
    }

    @Test
    fun anOlderScanDoesNotCount() {
        val s = PairingStatus()
        val g1 = s.begin()
        s.begin()
        s.paired(g1, "x")
        s.expire(g1)
        assertEquals("waiting", s.value)
    }

    @Test
    fun stopClearsIt() {
        val s = PairingStatus()
        val g = s.begin()
        s.reset()
        s.paired(g, "x")
        assertEquals("", s.value)
    }
}
