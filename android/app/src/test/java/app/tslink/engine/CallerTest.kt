package app.tslink.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerTest {
    @Test
    fun theAppIsAllowed() {
        val uids = AppUid { AppUid.Install(10331, "key") }
        assertTrue(uids.isApp(10331))
    }

    @Test
    fun aReinstalledAppIsStillAllowed() {
        // Uninstall + install gives the app a new uid; this engine process outlived it.
        var installed = 10331
        val uids = AppUid { AppUid.Install(installed, "key") }
        assertTrue(uids.isApp(10331))
        installed = 10566
        assertTrue(uids.isApp(10566))
        assertFalse("the old uid is no longer TSLink", uids.isApp(10331))
    }

    @Test
    fun othersAreDeniedWithoutALookupEachTime() {
        var lookups = 0
        val uids = AppUid { lookups++; AppUid.Install(10331, "key") }
        uids.isApp(10331)
        assertFalse(uids.isApp(2000))
        assertFalse(uids.isApp(2000))
        assertEquals("one lookup per stranger call at most", 3, lookups)
    }

    @Test
    fun goneOnceUninstalled() {
        var installed: AppUid.Install? = AppUid.Install(10331, "key")
        val uids = AppUid { installed }
        assertFalse(uids.gone())
        installed = null // the lookup's answer when the package isn't there
        assertTrue(uids.gone())
        assertFalse("nobody is TSLink when it's gone", uids.isApp(10331))
    }

    @Test
    fun anotherSignerUnderTheSameNameIsNotTSLink() {
        // Uninstalled, then an impostor installed as app.tslink with another key.
        var installed = AppUid.Install(10331, "key")
        val uids = AppUid { installed }
        assertTrue(uids.isApp(10331))
        installed = AppUid.Install(10600, "other key")
        assertFalse(uids.isApp(10600))
        assertTrue("and that counts as TSLink gone", uids.gone())
    }
}
