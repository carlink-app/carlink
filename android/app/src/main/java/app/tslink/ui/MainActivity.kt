package app.tslink.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

@SuppressLint("SetTextI18n") // English-only UI built in code
class MainActivity : Activity() {
    companion object {
        const val CAR_PAGE = "https://tslink.app/"
        /** Pairing results polled once a second, a bit past the engine's 2 minute window. */
        private const val PAIRING_POLLS = 130
        /** Pairing hands back to the normal engine ~1.5 s after the code went out, plus its stop. */
        private const val ENGINE_SWAP_MS = 4_000L
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
    private lateinit var carTextChoice: LinearLayout
    private lateinit var carTextView: TextView
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
        if (!bindIfAllowed()) {
            showStopped()
            setStatus("Shizuku ${if (Shizuku.pingBinder()) "hasn't allowed tslink yet" else "isn't running"}.")
        }
        // First launch: walk through setup (it marks itself seen).
        if (savedInstanceState == null && !Prefs.setupSeen(this)) startActivity(Intent(this, SetupActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        showCar() // may have been chosen in setup
        showNotifications()
        // Shizuku may have been allowed meanwhile (in Setup): link to the engine now.
        if (engine == null) bindIfAllowed() else call { it.status() }
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    /** Bind the engine service if Shizuku runs and allows us; false if not. */
    private fun bindIfAllowed(): Boolean {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false
        Shizuku.bindUserService(EngineArgs.args, connection)
        return true
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

        return ui.screen(window, content)
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
                    if (r != "pairing") return@call showMessage(r)
                    showMessage("Pairing: the car connects in a few seconds. Keep the car page open.")
                    watchPairing(PAIRING_POLLS)
                }) { it.startPairing(secret) }
            }
            .addOnFailureListener { e -> showMessage("error: scanner unavailable (${e.message})") }
    }

    /**
     * Tell the user which car got the code: anyone else on the hotspot who
     * saw the QR code could have raced the car (then: New code).
     */
    private fun watchPairing(left: Int) {
        if (left <= 0 || isDestroyed) return
        call(onResult = { r ->
            // The engine restarted with the real code: refresh the state line once it's back.
            if (r != "waiting") window.decorView.postDelayed({ call { it.status() } }, ENGINE_SWAP_MS)
            when {
                r.startsWith("paired ") -> showMessage(
                    "Paired with the car at ${r.removePrefix("paired ").substringBefore(':')}. " +
                        "If the car didn't connect, another device may have taken the code: tap New code.",
                )
                r == "expired" -> showMessage("Pairing timed out. Open tslink.app in the car and scan again.")
                r == "waiting" -> window.decorView.postDelayed({ watchPairing(left - 1) }, 1_000)
            }
        }) { it.pairingResult() }
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
        card.addView(ui.line("Message text in the car"), fullWidth(top = 12))
        val modes = Prefs.CarText.values()
        carTextChoice = ui.segmented(listOf("Lock screen", "Always", "Never")) { i ->
            Prefs.setCarText(this, modes[i])
            showNotifications()
        }
        card.addView(carTextChoice, fullWidth(top = 4))
        carTextView = ui.line()
        card.addView(carTextView)
    }

    private fun diagnosticsCard(parent: LinearLayout) {
        val card = ui.card(parent, "Diagnostics")
        card.addView(ui.button("Setup & checks", primary = false) { startActivity(Intent(this, SetupActivity::class.java)) }, fullWidth(top = 4))
        card.addView(ui.button("Device check", primary = false) { deviceCheck() }, fullWidth(top = 8))
        card.addView(ui.button("Engine log", primary = false) { showLog() }, fullWidth(top = 8))
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
        notifView.text = "New notifications appear as banners in the car: app, sender and (see below) the message."
        val mode = Prefs.carText(this)
        ui.selectSegment(carTextChoice, mode.ordinal)
        carTextView.text = when (mode) {
            Prefs.CarText.LOCK_SCREEN -> "Like your lock screen: shown when it shows it (Settings > Notifications > sensitive notifications)."
            Prefs.CarText.ALWAYS -> "Always shown in the car, even when your lock screen hides it. Passengers can read it."
            Prefs.CarText.NEVER -> "Never shown in the car: only the app and the sender."
        }
    }

    /**
     * Notification access is the user's to give: Android's own page for it
     * (the app never grants it to itself).
     */
    private fun toggleNotifications() {
        notifSwitch.isChecked = notificationsOn() // the page decides, not the tap
        try {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString()),
            )
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    // ---- Diagnostics --------------------------------------------------------

    /** Recent engine log (logcat as shell), to read or share after a car test. */
    /** Encoders, encode speed and car display support, without a car (see SelfTest). */
    private fun deviceCheck() {
        // The message line is up in the Engine card: say it where the button is.
        val e = engine ?: return toast(if (bindIfAllowed()) "Connecting to the engine, tap again in a moment." else "Allow Shizuku first (Setup & checks).")
        toast("Device check: about 15 seconds...")
        thread {
            val report = try { e.selfTest() } catch (t: Throwable) { "error: $t" }
            runOnUiThread {
                textDialog("Device check", report, "TSLink device check")
            }
        }
    }

    private fun showLog() {
        val e = engine ?: run {
            diagView.text = "Start the engine first to see its log."
            return
        }
        thread {
            val log = try { e.logs() } catch (t: Throwable) { "error: $t" }
            runOnUiThread { textDialog("Engine log", log, "TSLink engine log") }
        }
    }

    /** Monospace text with Share. */
    private fun textDialog(title: String, text: String, shareTitle: String) {
        val view = TextView(this).apply {
            this.text = text.ifBlank { "(empty)" }
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), shareTitle))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---- Car (Bluetooth auto-start) -----------------------------------------

    private fun showCar(note: String? = null) {
        val name = Prefs.carName(this)
        carName.text = name ?: "No car chosen"
        carView.text = note ?: if (name != null) "The engine and car hotspot start when the car connects." else "Start the engine yourself, or choose your car so it starts automatically."
        carView.setTextColor(if (note != null) Ui.WARN else Ui.MUTED)
        carButton.text = if (name != null) "Change car" else "Choose car"
    }

    private fun chooseCar() = CarPicker.pick(this, REQ_BLUETOOTH) { note -> showCar(note) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLUETOOTH) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) chooseCar()
        else showCar(CarPicker.PERMISSION_NOTE)
    }
}
