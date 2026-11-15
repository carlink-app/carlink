package app.carlink

import app.carlink.engine.allowList
import org.junit.Assert.assertEquals
import org.junit.Test

class DevAllowTest {
    @Test fun keepsValidEntries() =
        assertEquals("192.0.2.10,10.0.2.0/24", Prefs.parseDevAllow(" 192.0.2.10\n10.0.2.0/24, \n"))

    @Test fun dropsJunk() =
        assertEquals("1.2.3.4", Prefs.parseDevAllow("1.2.3.4,256.1.1.1,1.2.3.4/33,hello,1.2.3,::1,1.2.3.4"))

    @Test fun any() = assertEquals("any", Prefs.parseDevAllow("any\n"))

    @Test fun carSubnetAlwaysIncluded() {
        assertEquals("9.9.0.0/24", allowList(""))
        assertEquals("9.9.0.0/24,192.0.2.10", allowList("192.0.2.10"))
        assertEquals("", allowList("192.0.2.10,any"))
    }
}
