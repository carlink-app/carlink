package app.tslink.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingSecretTest {
    @Test fun uppercasesAndDropsSeparators() {
        assertEquals("ABCDEFGHJKLMNPQRSTUV", pairingSecret("abcd-efgh jklm_npqr stuv"))
    }

    @Test fun lengthLimits() {
        assertEquals("A".repeat(16), pairingSecret("A".repeat(16)))
        assertEquals("A".repeat(64), pairingSecret("A".repeat(64)))
        assertNull(pairingSecret("A".repeat(15)))
        assertNull(pairingSecret("A".repeat(65)))
        // Separators don't count towards the length.
        assertNull(pairingSecret("-".repeat(20) + "A".repeat(15)))
        assertNull(pairingSecret(""))
    }

    @Test fun agreesWithTheScanner() {
        // What the app's QR scanner accepts, the engine takes unchanged.
        val s = app.tslink.ui.Pairing.secretFrom("tslink-pair:abcdefghjklmnpqrstuv")!!
        assertEquals(s, pairingSecret(s))
    }
}
