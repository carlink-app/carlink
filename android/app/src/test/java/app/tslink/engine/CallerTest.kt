package app.tslink.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerTest {
    /** A phone with app.tslink at [uid] signed by [signer] (null uid: not installed); counts signer reads. */
    private class Phone(var uid: Int?, var signer: String = "key") {
        var signerReads = 0
        val appUid = AppUid(uidOf = { uid }, signerOf = { signerReads++; signer })
    }

    @Test
    fun theAppIsAllowed() {
        assertTrue(Phone(10331).appUid.isApp(10331))
    }

    @Test
    fun aReinstalledAppIsStillAllowed() {
        // Uninstall + install gives the app a new uid; this engine process outlived it.
        val p = Phone(10331)
        assertTrue(p.appUid.isApp(10331))
        p.uid = 10566
        assertTrue(p.appUid.isApp(10566))
        assertFalse("the old uid is no longer TSLink", p.appUid.isApp(10331))
    }

    @Test
    fun anotherSignerUnderTheSameNameIsNotTSLink() {
        val p = Phone(10331)
        assertTrue(p.appUid.isApp(10331))
        p.uid = 10600
        p.signer = "other key"
        assertFalse(p.appUid.isApp(10600))
        assertTrue("and that counts as TSLink gone", p.appUid.gone())
    }

    @Test
    fun goneOnceUninstalled() {
        val p = Phone(10331)
        assertFalse(p.appUid.gone())
        p.uid = null
        assertTrue(p.appUid.gone())
        assertFalse("nobody is TSLink when it's gone", p.appUid.isApp(10331))
    }

    @Test
    fun theSignerIsReadOnlyWhenTheUidChanges() {
        // The uninstall check runs every few seconds: no certificate hashing while nothing changed.
        val p = Phone(10331)
        repeat(5) { p.appUid.gone() }
        p.appUid.isApp(2000) // a stranger
        assertEquals(1, p.signerReads)
        p.uid = 10566
        p.appUid.gone()
        assertEquals(2, p.signerReads)
    }
}
