package app.tslink.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerTest {
    @Test
    fun theAppIsAllowed() {
        val uids = AppUid { 10331 }
        assertTrue(uids.isApp(10331))
    }

    @Test
    fun aReinstalledAppIsStillAllowed() {
        // Uninstall + install gives the app a new uid; this engine process outlived it.
        var installed = 10331
        val uids = AppUid { installed }
        assertTrue(uids.isApp(10331))
        installed = 10566
        assertTrue(uids.isApp(10566))
        assertFalse("the old uid is no longer TSLink", uids.isApp(10331))
    }

    @Test
    fun othersAreDeniedWithoutALookupEachTime() {
        var lookups = 0
        val uids = AppUid { lookups++; 10331 }
        uids.isApp(10331)
        assertFalse(uids.isApp(2000))
        assertFalse(uids.isApp(2000))
        assertEquals("one lookup per stranger call at most", 3, lookups)
    }
}
