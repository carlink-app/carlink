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

    @Test fun emptyAndSeparatorsOnly() {
        assertEquals("", Prefs.parseDevAllow(""))
        assertEquals("", Prefs.parseDevAllow(" ,\n\t, "))
    }

    @Test fun subnetBounds() =
        assertEquals("0.0.0.0/0,10.0.0.0/8,255.255.255.255/32", Prefs.parseDevAllow("0.0.0.0/0 10.0.0.0/8 255.255.255.255/32 1.2.3.4/ 1.2.3.4/-1"))

    @Test fun anyIsCaseSensitiveAndWhole() = assertEquals("", Prefs.parseDevAllow("ANY anyone any.1"))

    @Test fun noInjectionIntoTheAllowList() =
        // Entries are comma-joined for the core: nothing can smuggle a separator or option in.
        assertEquals("8.8.8.8", Prefs.parseDevAllow("1.2.3.4;5.6.7.8 1.2.3.4\u0000 --any 8.8.8.8"))

    @Test fun anyAloneAllowsAll() {
        assertEquals("", allowList("any"))
        assertEquals("9.9.0.0/24,10.0.2.0/24", allowList(" 10.0.2.0/24 , "))
    }

    // The Rust core rejects leading zeros (Ipv4Addr): such an entry must be
    // skipped here, or the engine wouldn't start at all.
    @Test fun leadingZerosDropped() =
        assertEquals("1.2.3.4,10.0.0.1", Prefs.parseDevAllow("01.2.3.4,1.2.3.004,1.2.3.4,10.0.0.1,192.168.050.1"))
}
