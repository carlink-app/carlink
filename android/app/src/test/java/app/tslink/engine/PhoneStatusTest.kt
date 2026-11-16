package app.tslink.engine

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneStatusTest {
    @Test fun labels() {
        assertEquals("5G", PhoneStatus.networkLabel(TelephonyManager.NETWORK_TYPE_NR))
        assertEquals("LTE", PhoneStatus.networkLabel(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("3G", PhoneStatus.networkLabel(TelephonyManager.NETWORK_TYPE_HSPAP))
        assertEquals("2G", PhoneStatus.networkLabel(TelephonyManager.NETWORK_TYPE_EDGE))
        assertEquals("", PhoneStatus.networkLabel(TelephonyManager.NETWORK_TYPE_UNKNOWN))
    }
}
