package app.tslink.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    @Test fun theOldTextFormIsNotAPairingCode() {
        // Pages that showed TSLINK-PAIR: paired by an engine swap the phone no longer does.
        assertNull(Pairing.secretFrom("TSLINK-PAIR:ABCDEFGHJKLMNPQRSTUV"))
    }

    @Test fun rejectsOtherCodes() {
        assertNull(Pairing.secretFrom(null))
        assertNull(Pairing.secretFrom("https://example.com"))
        assertNull(Pairing.secretFrom("https://tslink.app/pair#ABCDEFGHJKLMNPQR;rm"))
        assertNull(Pairing.secretFrom("https://tslink.app/pair#" + "A".repeat(65)))
        assertNull(Pairing.secretFrom("https://tslink.app/pair#ABCDEFGHJKLMNPQRSTI1")) // I and 1 aren't in the alphabet
        assertNull(Pairing.secretFrom("https://tslink.app/pair#ABCD-EFGH-JKLM-NPQR"))
        assertNull(Pairing.secretFrom("https://tslink.app/pair#ÄBCDEFGHJKLMNPQRSTUV"))
    }

    @Test fun trims() = assertEquals("ABCDEFGHJKLMNPQRSTUV", Pairing.secretFrom(" https://tslink.app/pair#ABCDEFGHJKLMNPQRSTUV\n"))

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
