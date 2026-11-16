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
}
