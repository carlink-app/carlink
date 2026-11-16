package app.tslink.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingSecretTest {
    @Test fun uppercases() = assertEquals("ABCDEFGHJKLMNPQRSTUV", pairingSecret("abcdefghjklmnpqrstuv"))

    @Test fun onlyExactPageSecrets() {
        assertNull(pairingSecret("abcd-efgh jklm_npqr stuv")) // separators aren't part of a secret
        assertNull(pairingSecret("A".repeat(19)))
        assertNull(pairingSecret("A".repeat(21)))
        assertNull(pairingSecret("ABCDEFGHJKLMNPQRSTU0")) // 0 isn't in the alphabet
        assertNull(pairingSecret("ÄBCDEFGHJKLMNPQRSTUV"))
        assertNull(pairingSecret(""))
    }

    @Test fun agreesWithTheScanner() {
        // What the app's QR scanner accepts, the engine takes unchanged.
        val s = app.tslink.ui.Pairing.secretFrom("https://tslink.app/pair#abcdefghjklmnpqrstuv")!!
        assertEquals(s, pairingSecret(s))
    }
}
