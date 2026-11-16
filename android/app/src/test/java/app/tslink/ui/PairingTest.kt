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

    @Test fun readsTheLink() {
        // The QR code is a link, so the phone's own camera can open TSLink too.
        assertEquals("ABCDEFGHJKLMNPQRSTUV", Pairing.secretFrom("https://tslink.app/pair#ABCDEFGHJKLMNPQRSTUV"))
        assertEquals("ABCDEFGHJKLMNPQRSTUV", Pairing.secretFrom("https://tslink.app/pair/#abcdefghjklmnpqrstuv"))
        assertEquals("ABCDEFGHJKLMNPQRSTUV", Pairing.secretFrom("HTTPS://TSLINK.APP/pair#ABCDEFGHJKLMNPQRSTUV"))
    }

    @Test fun onlyOurLink() {
        assertNull(Pairing.secretFrom("http://tslink.app/pair#ABCDEFGHJKLMNPQRSTUV")) // not https
        assertNull(Pairing.secretFrom("https://evil.example/pair#ABCDEFGHJKLMNPQRSTUV"))
        assertNull(Pairing.secretFrom("https://tslink.app.evil.example/pair#ABCDEFGHJKLMNPQRSTUV"))
        assertNull(Pairing.secretFrom("https://user@tslink.app/pair#ABCDEFGHJKLMNPQRSTUV"))
        assertNull(Pairing.secretFrom("https://tslink.app/other#ABCDEFGHJKLMNPQRSTUV"))
        assertNull(Pairing.secretFrom("https://tslink.app/pair?s=ABCDEFGHJKLMNPQRSTUV")) // the secret goes after #, never to a server
        assertNull(Pairing.secretFrom("https://tslink.app/pair#ABCDEFGHJKLMNPQRSTU")) // 19
        assertNull(Pairing.secretFrom("https://tslink.app/pair"))
    }
}
