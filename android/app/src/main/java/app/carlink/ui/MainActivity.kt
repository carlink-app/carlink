package app.carlink.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.carlink.Prefs
import app.carlink.engine.EngineArgs
import app.carlink.engine.Hotspot
import app.carlink.engine.IEngine
import app.carlink.engine.startFor
import app.carlink.notify.Listener
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {
    companion object {
        const val CAR_PAGE = "https://carlink-app.github.io/carlink/"
        private const val REQ_SHIZUKU = 1
        private const val REQ_BLUETOOTH = 2
    }

    private lateinit var statusView: TextView
    private lateinit var codeView: TextView
    private lateinit var carView: TextView
    private lateinit var notifView: TextView
    private lateinit var notifButton: Button
    private var engine: IEngine? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            engine = IEngine.Stub.asInterface(binder)
            // Bound just to look (app opened): only start if it should be running.
            if (Prefs.engineWanted(this@MainActivity)) call { it.startFor(this@MainActivity) } else call { it.status() }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            engine = null
            setStatus("engine stopped")
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQ_SHIZUKU && result == PackageManager.PERMISSION_GRANTED) startEngine()
        else if (code == REQ_SHIZUKU) setStatus("Shizuku permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, pad / 2, 0, pad / 2)
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        root.addView(text(26f, true).apply { text = "carlink" })
        root.addView(text(15f).apply { text = "Pairing code (type it once in the car page):" })
        codeView = text(34f, true).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        root.addView(codeView)
        root.addView(text(14f).apply { text = "Car page: $CAR_PAGE\nPhone address in car hotspot mode: ${Hotspot.PHONE}" })
        statusView = text(16f)
        root.addView(statusView)
        root.addView(button("Start engine") { startEngine() })
        root.addView(button("Stop engine") { stopEngine() })
        root.addView(button("Car hotspot on (${Hotspot.PHONE})") { call { it.carHotspot(true) } })
        root.addView(button("Normal hotspot") { call { it.carHotspot(false) } })
        root.addView(button("New pairing code (the car must re-enter it)") { newCode() })
        root.addView(button("Engine log") { showLog() })
        carView = text(14f)
        root.addView(carView)
        root.addView(button("Choose car (Bluetooth auto-start)") { chooseCar() })
        notifView = text(14f)
        root.addView(notifView)
        notifButton = button("") { toggleNotifications() }
        root.addView(notifButton)
        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })
        Prefs.devAllow(this).takeIf { it.isNotEmpty() }?.let { dev ->
            root.addView(text(14f).apply { text = "DEV: car page also accepted from $dev (files/dev_allow)" })
        }
        codeView.text = Prefs.code(this)
        showCar()
        setStatus("checking engine...")
        // Show the engine's real state even when it was started elsewhere (auto-start).
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Shizuku.bindUserService(EngineArgs.args, connection)
        } else {
            setStatus("engine not running (Shizuku ${if (Shizuku.pingBinder()) "not allowed" else "not running"})")
        }
    }

    override fun onResume() {
        super.onResume()
        showNotifications()
        call { it.status() }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun startEngine() {
        if (!Shizuku.pingBinder()) {
            setStatus("Shizuku isn't running. Open Shizuku and start it (Wireless debugging).")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQ_SHIZUKU)
            return
        }
        setStatus("starting engine...")
        Prefs.setEngineWanted(this, true)
        val e = engine
        if (e != null) call { it.startFor(this) } else Shizuku.bindUserService(EngineArgs.args, connection)
    }

    private fun stopEngine() {
        Prefs.setEngineWanted(this, false)
        call { it.stop(); "stopped" }
        if (Shizuku.pingBinder()) Shizuku.unbindUserService(EngineArgs.args, connection, true)
        engine = null
    }

    /** Engine calls block (hotspot restart takes seconds): run off the UI thread. */
    private fun call(block: (IEngine) -> String?) {
        val e = engine ?: return
        thread {
            val result = try {
                block(e)
            } catch (t: Throwable) {
                "error: $t"
            }
            if (result != null) setStatus(result)
        }
    }

    private fun setStatus(s: String) = runOnUiThread { statusView.text = "Status: $s" }

    private fun newCode() {
        val code = Prefs.newCode(this)
        codeView.text = code
        // The running engine still serves the old code: restart it.
        if (engine != null) call { it.stop(); it.startFor(this, code) }
    }

    private val listener get() = ComponentName(this, Listener::class.java)

    private fun notificationsOn() = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)

    private fun showNotifications() {
        val on = notificationsOn()
        notifView.text = "Car notifications: ${if (on) "on" else "off"}. Shows new phone notifications as banners in the car; " +
            "content you hide on the lock screen stays hidden."
        notifButton.text = if (on) "Turn car notifications off" else "Turn car notifications on"
    }

    /**
     * Notification access is only ever changed here, by the user. Through the
     * engine (shell) it's one tap; without it, the system settings page.
     */
    private fun toggleNotifications() {
        val want = !notificationsOn()
        if (engine == null) {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString()),
            )
            return
        }
        call { e -> e.notificationAccess(want).also { runOnUiThread { showNotifications() } } }
    }

    /** Recent engine log (logcat as shell), to read or share after a car test. */
    private fun showLog() {
        val e = engine ?: return setStatus("engine not bound: start it first")
        thread {
            val log = try { e.logs() } catch (t: Throwable) { "error: $t" }
            runOnUiThread {
                val view = TextView(this).apply {
                    text = log.ifBlank { "(empty)" }
                    typeface = Typeface.MONOSPACE
                    textSize = 10f
                    setTextIsSelectable(true)
                }
                AlertDialog.Builder(this)
                    .setTitle("Engine log")
                    .setView(ScrollView(this).apply { addView(view) })
                    .setPositiveButton("Share") { _, _ ->
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log), "carlink log"))
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun showCar() {
        val name = Prefs.carName(this)
        carView.text = if (name != null) "Car: $name — the engine and car hotspot start when it connects." else "Car: not chosen (start manually)."
    }

    /** Pick the car among paired Bluetooth devices; its connection starts everything. */
    @SuppressLint("MissingPermission") // checked just above the calls
    private fun chooseCar() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val devices = adapter?.bondedDevices?.sortedBy { it.name ?: "" }.orEmpty()
        if (devices.isEmpty()) {
            setStatus("No paired Bluetooth devices. Pair the phone with the car first.")
            return
        }
        val labels = devices.map { it.name ?: it.address }.toTypedArray<CharSequence>() + "None (no auto-start)"
        AlertDialog.Builder(this)
            .setTitle("Which one is your car?")
            .setItems(labels) { _, i ->
                val d = devices.getOrNull(i)
                Prefs.setCar(this, d?.address, d?.name)
                showCar()
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLUETOOTH) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) chooseCar()
        else setStatus("Bluetooth permission is needed to start with the car.")
    }
}
