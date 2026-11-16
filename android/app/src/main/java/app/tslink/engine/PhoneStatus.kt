package app.tslink.engine

import android.os.BatteryManager
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject

/**
 * Battery and mobile signal for the car's sidebar (like Android Auto's
 * status bar). Public APIs; the shell may read the phone state.
 */
object PhoneStatus {
    private const val TAG = "tslink"

    /** `{"t":"phone","bat":0-100,"chg":bool,"sig":0-4 or -1,"net":"5G"|"LTE"|...}`; parts that can't be read are left out. */
    // READ_PHONE_STATE: the engine runs as the shell, which holds it; failures are caught.
    @android.annotation.SuppressLint("MissingPermission")
    fun read(): JSONObject {
        val m = JSONObject().put("t", "phone")
        try {
            val bm = Shell.context.getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) m.put("bat", level)
            m.put("chg", bm.isCharging)
        } catch (e: Exception) {
            Log.d(TAG, "battery: $e")
        }
        try {
            val tm = Shell.context.getSystemService(TelephonyManager::class.java)
            // No SIM or airplane mode: no bars (-1), not "0 bars".
            val inService = tm.simState == TelephonyManager.SIM_STATE_READY
            m.put("sig", if (inService) tm.signalStrength?.level ?: -1 else -1)
            if (inService) m.put("net", networkLabel(tm.dataNetworkType))
        } catch (e: Exception) {
            Log.d(TAG, "signal: $e")
        }
        return m
    }

    /** Short label for a TelephonyManager.NETWORK_TYPE_* value ("" if unknown). */
    fun networkLabel(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
        else -> ""
    }
}
