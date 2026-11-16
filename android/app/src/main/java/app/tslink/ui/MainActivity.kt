package app.tslink.ui

import android.Manifest
import android.annotation.SuppressLint
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
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
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.tslink.BuildConfig
import app.tslink.Prefs
import app.tslink.engine.EngineArgs
import app.tslink.engine.Hotspot
import app.tslink.engine.IEngine
import app.tslink.engine.startFor
import app.tslink.notify.CarSessionNotice
import app.tslink.notify.Listener
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

@SuppressLint("SetTextI18n") // English-only UI built in code
class MainActivity : Activity() {
    companion object {
        const val CAR_PAGE = "https://tslink.app/"
        private const val REQ_SHIZUKU = 1
        private const val REQ_BLUETOOTH = 2
        private const val REQ_NOTIFICATIONS = 3
    }

    private val ui = Ui(this)

    // Status card
    private lateinit var stateDot: View
    private lateinit var stateView: TextView
    private lateinit var certView: TextView
    private lateinit var messageView: TextView
    private lateinit var engineButton: Button
    private var engineRunning = false

    private lateinit var codeView: TextView
    private lateinit var hotspotChoice: LinearLayout
    private lateinit var hotspotView: TextView
    private lateinit var carName: TextView
    private lateinit var carView: TextView
    private lateinit var carButton: Button
    private lateinit var notifView: TextView
    private lateinit var notifSwitch: Switch
    private lateinit var diagView: TextView
    private var engine: IEngine? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            engine = IEngine.Stub.asInterface(binder)
            // Bound just to look (app opened): only start if it should be running.
            if (Prefs.engineWanted(this@MainActivity)) call { it.startAndStatus() } else call { it.status() }
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
        setContentView(buildScreen())
        // The pairing code is a credential: keep it out of the Recents thumbnail.
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        if (Prefs.engineWanted(this)) askNotificationPermission() // engine already running: Start isn't tapped

        codeView.text = Prefs.code(this)
        showCar()
        setStatus("checking engine...")
        // Show the engine's real state even when it was started elsewhere (auto-start).
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Shizuku.bindUserService(EngineArgs.args, connection)
        } else {
            showStopped()
            setStatus("Shizuku ${if (Shizuku.pingBinder()) "hasn't allowed tslink yet" else "isn't running"}.")
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

    // ---- Layout -------------------------------------------------------------

    private fun buildScreen(): View {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ui.text(26f, bold = true).apply { text = "TSLink" })
        content.addView(ui.line("Your phone's apps on the car screen").apply { setPadding(0, 0, 0, ui.dp(16)) })

        statusCard(content)
        pairingCard(content)
        connectionCard(content)
        carCard(content)
        notificationsCard(content)
        diagnosticsCard(content)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(content, MATCH_PARENT, WRAP_CONTENT)
        }
        // Edge to edge (always on with targetSdk 36): keep content clear of the system bars.
        window.setDecorFitsSystemWindows(false)
        val pad = ui.dp(16)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        return scroll
    }

    private fun statusCard(parent: LinearLayout) {
        val card = ui.card(parent, "Engine")
        stateDot = ui.dot()
        stateView = ui.text(18f, bold = true)
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stateDot)
            addView(stateView)
        })
        certView = ui.line(size = 12f).apply { typeface = Typeface.MONOSPACE; visibility = View.GONE }
        card.addView(certView)
        messageView = ui.line().apply { visibility = View.GONE }
        card.addView(messageView)
        engineButton = ui.button("Start engine", primary = true) { if (engineRunning) stopEngine() else startEngine() }
        card.addView(engineButton, fullWidth(top = 12))
    }

    private fun pairingCard(parent: LinearLayout) {
        val card = ui.card(parent, "Pairing code")
        codeView = ui.text(32f, bold = true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            // Not selectable: a copied code would sit in clipboard history.
            setPadding(0, ui.dp(6), 0, ui.dp(6))
        }
        card.addView(codeView, MATCH_PARENT, WRAP_CONTENT)
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.line("Or type it on the car page. After a new code, pair again."), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(ui.textButton("New code") { newCode() })
        })
        card.addView(ui.button("Pair a car (scan QR)", primary = true) { pairCar() }, fullWidth(top = 12))
    }

    /** Scan the QR code on the car page; the engine then hands the pairing code to that car. */
    private fun pairCar() {
        if (engine == null || !engineRunning) return showMessage("Start the engine first, then scan.")
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val secret = Pairing.secretFrom(barcode.rawValue)
                    ?: return@addOnSuccessListener showMessage("That isn't a TSLink pairing code. Open tslink.app in the car.")
                showMessage("Pairing...")
                call(onResult = { r ->
                    showMessage(if (r == "pairing") "Pairing: the car connects in a few seconds. Keep the car page open." else r)
                }) { it.startPairing(secret) }
            }
            .addOnFailureListener { e -> showMessage("error: scanner unavailable (${e.message})") }
    }

    private fun connectionCard(parent: LinearLayout) {
        val card = ui.card(parent, "Car connection")
        hotspotChoice = ui.segmented(listOf("Car hotspot", "Normal hotspot")) { i -> setHotspot(car = i == 0) }
        card.addView(hotspotChoice, fullWidth(top = 4))
        hotspotView = ui.line("Car hotspot gives the phone the fixed address the car page connects to.")
        card.addView(hotspotView)
        card.addView(ui.row("Phone address", Hotspot.PHONE), fullWidth(top = 8))
        card.addView(ui.row("Car page", CAR_PAGE))
    }

    private fun carCard(parent: LinearLayout) {
        val card = ui.card(parent, "Car")
        carName = ui.text(16f)
        card.addView(carName)
        carView = ui.line()
        card.addView(carView)
        carButton = ui.button("Choose car", primary = false) { chooseCar() }
        card.addView(carButton, fullWidth(top = 12))
    }

    private fun notificationsCard(parent: LinearLayout) {
        val card = ui.card(parent, "Notifications")
        val (row, sw) = ui.switchRow("Show in the car") {
            notifSwitch.isChecked = notificationsOn() // the real state follows once it changes
            toggleNotifications()
        }
        notifSwitch = sw
        card.addView(row)
        notifView = ui.line()
        card.addView(notifView)
    }

    private fun diagnosticsCard(parent: LinearLayout) {
        val card = ui.card(parent, "Diagnostics")
        card.addView(ui.button("Engine log", primary = false) { showLog() }, fullWidth(top = 4))
        diagView = ui.line("TSLink ${BuildConfig.VERSION_NAME}", size = 12f)
        card.addView(diagView)
        Prefs.devAllow(this).takeIf { it.isNotEmpty() }?.let { dev ->
            card.addView(ui.line("DEV: car page also accepted from $dev (files/dev_allow)", size = 12f, color = Ui.WARN))
        }
    }

    private fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(top) }

    // ---- Engine -------------------------------------------------------------

    private fun startEngine() {
        if (!Shizuku.pingBinder()) {
            setStatus("Shizuku isn't running. Open Shizuku and start it (Wireless debugging or USB).")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQ_SHIZUKU)
            return
        }
        setStatus("starting engine...")
        Prefs.setEngineWanted(this, true)
        askNotificationPermission()
        val e = engine
        if (e != null) call { it.startAndStatus() } else Shizuku.bindUserService(EngineArgs.args, connection)
    }

    /** For the "Connected to car" notification (Android 13+ asks; optional, asked once). */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    private fun stopEngine() {
        Prefs.setEngineWanted(this, false)
        CarSessionNotice.update(this, false)
        call { it.stop(); "stopped" }
        if (Shizuku.pingBinder()) Shizuku.unbindUserService(EngineArgs.args, connection, true)
        engine = null
        setStatus("stopped")
    }

    /** Start, then read the state back (waiting for car · cert ...) unless the start failed. */
    private fun IEngine.startAndStatus(code: String = Prefs.code(this@MainActivity)): String =
        startFor(this@MainActivity, code).let { if (EngineStatus.isError(it)) it else status() }

    /** Engine calls block (hotspot restart takes seconds): run off the UI thread; results come back on it. */
    private fun call(onResult: (String) -> Unit = ::setStatus, block: (IEngine) -> String?) {
        val e = engine ?: return
        thread {
            val result = try {
                block(e)
            } catch (t: Throwable) {
                "error: $t"
            }
            if (result != null) runOnUiThread { onResult(result) }
        }
    }

    /**
     * Every engine reply and app message ends up here. Engine states
     * ("stopped", "waiting for car · cert ab12cd", "car connected (...) · cert ...")
     * update the state line; anything else is shown as a message below it.
     */
    private fun setStatus(s: String) = runOnUiThread {
        when (val st = EngineStatus.parse(s)) {
            EngineStatus.Stopped -> showStopped()
            is EngineStatus.Running -> {
                showEngine(true, st.state, if (st.connected) Ui.OK else Ui.WARN, st.cert)
                showMessage(null)
            }
            is EngineStatus.Busy -> {
                stateView.text = st.text
                ui.setDot(stateDot, Ui.MUTED)
            }
            is EngineStatus.Message -> {
                showMessage(st.text)
                // A failed start/check must not leave "Starting engine..." up.
                if (EngineStatus.isError(s)) showStopped()
            }
        }
    }

    private fun showEngine(running: Boolean, state: String, color: Int, cert: String?) {
        engineRunning = running
        stateView.text = state
        ui.setDot(stateDot, color)
        certView.text = "Certificate $cert (the car page's debug view shows the same)"
        certView.visibility = if (cert != null) View.VISIBLE else View.GONE
        engineButton.text = if (running) "Stop engine" else "Start engine"
        ui.stylePrimary(engineButton, primary = !running)
    }

    private fun showStopped() = showEngine(false, "Stopped", Ui.MUTED, null)

    private fun showMessage(s: String?) {
        messageView.text = s.orEmpty()
        messageView.setTextColor(if (s != null && EngineStatus.isError(s)) Ui.ERR else Ui.MUTED)
        messageView.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun newCode() {
        val code = Prefs.newCode(this)
        codeView.text = code
        // The running engine still serves the old code: restart it.
        if (engine != null) call { it.stop(); it.startAndStatus(code) }
    }

    // ---- Hotspot ------------------------------------------------------------

    /** The engine can't report the hotspot mode, so the control shows the last mode set here. */
    private fun setHotspot(car: Boolean) {
        if (engine == null) {
            showHotspot("Start the engine first.", Ui.WARN)
            return
        }
        showHotspot(if (car) "Switching to car hotspot..." else "Switching to normal hotspot...", Ui.MUTED)
        call(onResult = { r ->
            if (r == "ok") {
                ui.selectSegment(hotspotChoice, if (car) 0 else 1)
                showHotspot(if (car) "Car hotspot is on. Join it from the car, then open the car page." else "Normal hotspot is on.", Ui.OK)
            } else {
                showHotspot("Hotspot: $r", Ui.ERR)
            }
        }) { it.carHotspot(car) }
    }

    private fun showHotspot(s: String, color: Int) {
        hotspotView.text = s
        hotspotView.setTextColor(color)
    }

    // ---- Notifications ------------------------------------------------------

    private val listener get() = ComponentName(this, Listener::class.java)

    private fun notificationsOn() = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)

    private fun showNotifications() {
        notifSwitch.isChecked = notificationsOn()
        notifView.text = "New notifications appear as banners in the car. What your lock screen hides stays hidden."
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
        call(onResult = { r ->
            showNotifications()
            if (EngineStatus.isError(r)) showMessage(r)
        }) { e -> e.notificationAccess(want) }
    }

    // ---- Diagnostics --------------------------------------------------------

    /** Recent engine log (logcat as shell), to read or share after a car test. */
    private fun showLog() {
        val e = engine ?: run {
            diagView.text = "Start the engine first to see its log."
            return
        }
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
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log), "TSLink engine log"))
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    // ---- Car (Bluetooth auto-start) -----------------------------------------

    private fun showCar(note: String? = null) {
        val name = Prefs.carName(this)
        carName.text = name ?: "No car chosen"
        carView.text = note ?: if (name != null) "The engine and car hotspot start when the car connects." else "Start the engine yourself, or choose your car so it starts automatically."
        carView.setTextColor(if (note != null) Ui.WARN else Ui.MUTED)
        carButton.text = if (name != null) "Change car" else "Choose car"
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
            showCar("No paired Bluetooth devices. Pair the phone with your car first.")
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
        else showCar("Bluetooth permission is needed to start automatically with the car.")
    }
}
