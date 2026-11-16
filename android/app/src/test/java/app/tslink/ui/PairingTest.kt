package app.tslink.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    @Test fun readsTheSecret() = assertEquals("ABCDEFGHJKLMNPQRSTUV", Pairing.secretFrom("TSLINK-PAIR:ABCDEFGHJKLMNPQRSTUV"))

    @Test fun caseInsensitive() = assertEquals("ABCDEFGHJKLMNPQRSTUV", Pairing.secretFrom("tslink-pair:abcdefghjklmnpqrstuv"))

    @Test fun rejectsOtherCodes() {
        assertNull(Pairing.secretFrom(null))
        assertNull(Pairing.secretFrom("https://example.com"))
        assertNull(Pairing.secretFrom("TSLINK-PAIR:short"))
        assertNull(Pairing.secretFrom("TSLINK-PAIR:ABCDEFGHJKLMNPQR;rm -rf"))
        assertNull(Pairing.secretFrom("TSLINK-PAIR:" + "A".repeat(65)))
    }

    @Test fun trimsAndChecksLengthLimits() {
        assertEquals("ABCDEFGHJKLMNPQRSTUV", Pairing.secretFrom("TSLINK-PAIR:ABCDEFGHJKLMNPQRSTUV\n"))
        assertEquals("A".repeat(20), Pairing.secretFrom("TSLINK-PAIR:" + "a".repeat(20)))
        assertNull(Pairing.secretFrom("TSLINK-PAIR:" + "A".repeat(19)))
        assertNull(Pairing.secretFrom("TSLINK-PAIR:" + "A".repeat(21)))
        assertNull(Pairing.secretFrom("TSLINK-PAIR:ABCDEFGHJKLMNPQRSTI1")) // I and 1 aren't in the alphabet
        assertNull(Pairing.secretFrom("TSLINK-PAIR:"))
        assertNull(Pairing.secretFrom("XTSLINK-PAIR:ABCDEFGHJKLMNPQRSTUV")) // prefix must lead
    }

    @Test fun onlyAsciiLettersAndDigits() {
        assertNull(Pairing.secretFrom("TSLINK-PAIR:ABCD-EFGH-JKLM-NPQR"))
        assertNull(Pairing.secretFrom("TSLINK-PAIR:ÄBCDEFGHJKLMNPQRSTUV"))
        assertNull(Pairing.secretFrom("TSLINK-PAIR:ABCDEFGH JKLMNPQRSTUV"))
    }
}
