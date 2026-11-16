package app.tslink.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import app.tslink.Prefs

/** Pick the car among paired Bluetooth devices; its connection starts everything. Shared by the main screen and setup. */
object CarPicker {
    const val PERMISSION_NOTE = "Bluetooth permission is needed to start automatically with the car."

    /**
     * Asks for the Bluetooth permission first if needed (with [requestCode]:
     * call again from onRequestPermissionsResult once granted). [onDone] gets
     * null after a choice, or a note to show when there is nothing to choose.
     */
    @SuppressLint("MissingPermission") // checked just above the calls
    fun pick(activity: Activity, requestCode: Int, onDone: (note: String?) -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), requestCode)
            return
        }
        val adapter = activity.getSystemService(BluetoothManager::class.java)?.adapter
        val devices = adapter?.bondedDevices?.sortedBy { it.name ?: "" }.orEmpty()
        if (devices.isEmpty()) {
            onDone("No paired Bluetooth devices. Pair the phone with your car first.")
            return
        }
        val labels = devices.map { it.name ?: it.address }.toTypedArray<CharSequence>() + "None (no auto-start)"
        AlertDialog.Builder(activity)
            .setTitle("Which one is your car?")
            .setItems(labels) { _, i ->
                val d = devices.getOrNull(i)
                Prefs.setCar(activity, d?.address, d?.name)
                onDone(null)
            }
            .show()
    }
}
