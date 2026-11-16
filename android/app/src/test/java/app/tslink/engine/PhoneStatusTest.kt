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

    @Test fun everyKnownTypeHasALabel() {
        val tm = TelephonyManager::class.java
        fun t(name: String) = tm.getField("NETWORK_TYPE_$name").getInt(null)
        // Wi-Fi calling counts as LTE (that's what the status bar shows on most phones).
        assertEquals("LTE", PhoneStatus.networkLabel(t("IWLAN")))
        for (n in listOf("HSPA", "HSDPA", "HSUPA", "UMTS", "TD_SCDMA", "EVDO_0", "EVDO_A", "EVDO_B", "EHRPD")) assertEquals(n, "3G", PhoneStatus.networkLabel(t(n)))
        for (n in listOf("GPRS", "GSM", "CDMA", "1xRTT", "IDEN")) assertEquals(n, "2G", PhoneStatus.networkLabel(t(n)))
    }

    @Test fun unknownValuesAreBlank() {
        assertEquals("", PhoneStatus.networkLabel(-1))
        assertEquals("", PhoneStatus.networkLabel(999))
    }
}
