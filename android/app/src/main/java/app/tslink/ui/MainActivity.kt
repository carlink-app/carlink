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
        private const val STATUS_POLL_MS = 2_000L
        private const val REQ_SHIZUKU = 1
        private const val REQ_BLUETOOTH = 2
        private const val REQ_NOTIFICATIONS = 3
    }

    private val ui = Ui(this)

    // Status card: state, what to do in the car, the one next action.
    private lateinit var stateDot: View
    private lateinit var stateView: TextView
    private lateinit var hintView: TextView
    private lateinit var primaryButton: Button
    private lateinit var carHotspotButton: Button
    /** Car hotspot turned on from here (the engine can't report the mode). */
    private var carHotspotOn = false
    private lateinit var messageView: TextView
    private lateinit var codeBox: LinearLayout
    private lateinit var codeView: TextView
    private lateinit var codeToggle: Button
    /** Last engine state (not messages): what [render] draws. */
    private var lastStatus: EngineStatus = EngineStatus.Stopped
    private val engineRunning get() = lastStatus is EngineStatus.Running
    /** Pair was tapped while TSLink was off: open the scanner once it runs. */
    private var pendingScan = false
    /** A linked secret waiting for TSLink to run (with [pendingScan]): pair with it instead of scanning. */
    private var pendingSecret: String? = null
    private var linkDialog: AlertDialog? = null
    /** A status poll is out (UI thread only). */
    private var polling = false

    private lateinit var carName: TextView
    private lateinit var carView: TextView
    private lateinit var carButton: Button
    private lateinit var notifView: TextView
    private lateinit var notifSwitch: Switch
    private lateinit var carTextChoice: LinearLayout
    private lateinit var carTextView: TextView
    private lateinit var startStopButton: Button
    private lateinit var certView: TextView
    private var engine: IEngine? = null
    /** A bind is under way or done (cleared when the user stops the engine). */
    private var binding = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            engine = IEngine.Stub.asInterface(binder)
            // Bound just to look (app opened): only start if it should be running.
            if (Prefs.engineWanted(this@MainActivity)) call { it.startAndStatus() } else call { it.status() }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binding = false
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
        setStatus("checking...")
        // Show the real state even when TSLink was started elsewhere (auto-start).
        if (!bindIfAllowed()) setStatus("stopped")
        // First launch: walk through setup (it marks itself seen).
        if (savedInstanceState == null && !Prefs.setupSeen(this)) startActivity(Intent(this, SetupActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        showCar() // may have been chosen in setup
        showNotifications()
        render() // setup may have changed (Shizuku allowed, started, stopped)
        pairFromLink()
        // Shizuku may have been allowed meanwhile (in Setup): link to the engine now.
        // Not after the user stopped it: binding would start the engine's process again.
        if (engine == null) { if (Prefs.engineWanted(this)) bindIfAllowed() } else call { it.status() }
    }


    /** Bind the engine service if Shizuku runs and allows us; false if not. */
    private fun bindIfAllowed(): Boolean {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false
        if (!binding) { // once: onCreate and onResume both get here before it connects
            binding = true
            Shizuku.bindUserService(EngineArgs.args, connection)
        }
        return true
    }

    /** The engine doesn't push its state: while the screen is up, read it every couple of seconds (a car connecting). */
    private val refresher = object : Runnable {
        override fun run() {
            // Not while a Pair tap starts TSLink: an in-between "stopped" would drop the scan.
            if (engine != null && !pendingScan && !polling) {
                polling = true // one at a time: a slow engine (hotspot restart) mustn't pile up threads
                call(onResult = { polling = false; setStatus(it) }) { it.status() }
            }
            render() // setup may change meanwhile (Shizuku stopped)
            window.decorView.postDelayed(this, STATUS_POLL_MS)
        }
    }

    override fun onStart() {
        super.onStart()
        window.decorView.postDelayed(refresher, STATUS_POLL_MS)
    }

    override fun onStop() {
        window.decorView.removeCallbacks(refresher)
        super.onStop()
    }

    override fun onDestroy() {
        linkDialog?.dismiss() // shown again by the next screen while unanswered
        linkDialog = null
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    // ---- Layout -------------------------------------------------------------

    private fun buildScreen(): View {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ui.text(26f, bold = true).apply { text = "TSLink" })
        content.addView(ui.line("Your phone's apps on the car screen").apply { setPadding(0, 0, 0, ui.dp(16)) })

        statusCard(content)
        optionsCard(content)
        moreCard(content)

        return ui.screen(window, content)
    }

    private fun statusCard(parent: LinearLayout) {
        val card = ui.card(parent, "Status")
        stateDot = ui.dot()
        stateView = ui.text(20f, bold = true)
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stateDot)
            addView(stateView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        hintView = ui.line().apply { visibility = View.GONE }
        card.addView(hintView)
        // The car can only join the hotspot in car mode: offer it where the user looks.
        carHotspotButton = ui.button("Turn on car hotspot", primary = false) { carHotspot() }
        card.addView(carHotspotButton, fullWidth(top = 12))
        primaryButton = ui.button("", primary = true) { onPrimary() }
        card.addView(primaryButton, fullWidth(top = 16))
        // Right under the button: what happened when it was tapped.
        messageView = ui.line().apply { visibility = View.GONE; setPadding(0, ui.dp(8), 0, 0) }
        card.addView(messageView)

        codeToggle = ui.textButton("Type the code instead") { toggleCode() }
        card.addView(codeToggle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.dp(4)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        codeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        codeView = ui.text(32f, bold = true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            // Not selectable: a copied code would sit in clipboard history.
            setPadding(0, ui.dp(6), 0, ui.dp(6))
        }
        codeBox.addView(codeView, MATCH_PARENT, WRAP_CONTENT)
        codeBox.addView(ui.line("On the car page, tap Type the code instead and enter this.").apply { gravity = Gravity.CENTER })
        card.addView(codeBox)
    }

    private fun toggleCode() {
        val show = codeBox.visibility != View.VISIBLE
        codeBox.visibility = if (show) View.VISIBLE else View.GONE
        codeToggle.text = if (show) "Hide the code" else "Type the code instead"
    }

    private fun onPrimary() = when (Home.view(setupReady(), lastStatus).primary) {
        Primary.FINISH_SETUP -> startActivity(Intent(this, SetupActivity::class.java))
        Primary.PAIR -> pairCar()
        Primary.DISCONNECT -> stopEngine()
    }

    /**
     * Pair needs Shizuku running and TSLink allowed (the Shizuku setup page);
     * the other setup steps can wait. Cheap: it runs on every redraw.
     */
    private fun setupReady() = Shizuku.pingBinder() && try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: RuntimeException) {
        false // Shizuku went away between the two
    }

    private fun optionsCard(parent: LinearLayout) {
        val card = ui.card(parent, "Options")
        val (row, sw) = ui.switchRow("Notifications in the car") {
            notifSwitch.isChecked = notificationsOn() // the real state follows once it changes
            toggleNotifications()
        }
        notifSwitch = sw
        card.addView(row)
        notifView = ui.line()
        card.addView(notifView)
        card.addView(ui.line("Message text in the car"), fullWidth(top = 12))
        val modes = Prefs.CarText.values()
        carTextChoice = ui.segmented(listOf("Default", "Always", "Never")) { i ->
            Prefs.setCarText(this, modes[i])
            showNotifications()
        }
        card.addView(carTextChoice, fullWidth(top = 4))
        carTextView = ui.line()
        card.addView(carTextView)

        card.addView(ui.text(16f).apply { text = "Start with the car" }, fullWidth(top = 20))
        // The chosen car stands out like a selected option.
        carName = ui.text(17f, bold = true).apply {
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            compoundDrawablePadding = ui.dp(10)
        }
        card.addView(carName, fullWidth(top = 8))
        carView = ui.line()
        card.addView(carView)
        carButton = ui.button("Choose car", primary = false) { chooseCar() }
        card.addView(carButton, fullWidth(top = 8))
    }

    private fun moreCard(parent: LinearLayout) {
        val more = ui.expander(parent, "More")
        more.addView(ui.row("Phone address", Hotspot.PHONE), fullWidth(top = 8))
        more.addView(ui.row("Car page", CAR_PAGE))

        more.addView(ui.button("Unpair all cars", primary = false) { confirmUnpair() }, fullWidth(top = 16))
        more.addView(ui.line("Makes a new code: every car pairs again with the QR code."))
        startStopButton = ui.button("Stop TSLink", primary = false) { if (engineRunning) stopEngine() else startEngine() }
        more.addView(startStopButton, fullWidth(top = 12))
        more.addView(ui.button("Setup", primary = false) { startActivity(Intent(this, SetupActivity::class.java)) }, fullWidth(top = 8))
        more.addView(ui.button("Device check", primary = false) { deviceCheck() }, fullWidth(top = 8))
        more.addView(ui.button("Log", primary = false) { showLog() }, fullWidth(top = 8))
        certView = ui.line(size = 12f).apply { typeface = Typeface.MONOSPACE; visibility = View.GONE }
        more.addView(certView, fullWidth(top = 8))
        more.addView(ui.line("TSLink ${BuildConfig.VERSION_NAME}", size = 12f))
        Prefs.devAllow(this).takeIf { it.isNotEmpty() }?.let { dev ->
            more.addView(ui.line("DEV: car page also accepted from $dev (files/dev_allow)", size = 12f, color = Ui.WARN))
        }
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle("Unpair all cars?")
            .setMessage("Every car has to pair again with the QR code.")
            .setPositiveButton("Unpair") { _, _ -> newCode() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(top) }

    /**
     * Scan the QR code on the car page; the engine then hands the pairing code
     * to that car. TSLink off: start it first, the scanner opens once it runs.
     */
    private fun pairCar() {
        pendingSecret = null // the user asked to scan: not a link from earlier
        if (engine == null || !engineRunning) {
            pendingScan = true
            startEngine()
            return
        }
        openScanner()
    }

    /** Hand the scanned (or linked) secret to the running engine and follow the result. */
    private fun startPairing(secret: String) {
        showMessage("Pairing...")
        call(onResult = { r ->
            if (r != "pairing") return@call showMessage(r, Home.replyTone(r))
            showMessage("Pairing: the car connects in a moment. Keep the car page open.")
            watchPairing(PAIRING_POLLS)
        }) { it.startPairing(secret) }
    }

    /**
     * Opened from a pairing link (the phone's camera on the car's QR code).
     * Any web page can link here, with a secret its owner knows: ask before
     * pairing, so only a QR code the user just scanned pairs a car. Shown
     * again after a rotation until answered.
     */
    private fun pairFromLink() {
        if (PairLinkActivity.pending == null || linkDialog?.isShowing == true) return
        fun answer(pair: Boolean) {
            val secret = PairLinkActivity.pending
            PairLinkActivity.pending = null
            if (pair && secret != null) pairWith(secret)
        }
        linkDialog = AlertDialog.Builder(this)
            .setTitle("Pair a car?")
            .setMessage("Only if you just scanned the QR code on your own car's screen. The car you pair can show and control your phone's apps.")
            .setPositiveButton("Pair") { _, _ -> answer(true) }
            .setNegativeButton("Cancel") { _, _ -> answer(false) }
            .setOnCancelListener { answer(false) }
            .show()
            .apply {
                // No taps through an overlay drawn over the dialog (tapjacking).
                getButton(AlertDialog.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true
                getButton(AlertDialog.BUTTON_NEGATIVE)?.filterTouchesWhenObscured = true
            }
    }

    /** Pair with [secret], starting TSLink first if it is off. */
    private fun pairWith(secret: String) {
        if (engine != null && engineRunning) return startPairing(secret)
        pendingSecret = secret
        pendingScan = true
        startEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairFromLink()
    }

    private fun openScanner() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val secret = Pairing.secretFrom(barcode.rawValue)
                    ?: return@addOnSuccessListener showMessage("That isn't a TSLink pairing code. Open tslink.app in the car.", Tone.WARN)
                startPairing(secret)
            }
            .addOnFailureListener { e -> showMessage("error: scanner unavailable (${e.message})", Tone.ERROR) }
    }

    /**
     * Tell the user which car got the code: anyone else on the hotspot who
     * saw the QR code could have raced the car (then: New code).
     */
    private fun watchPairing(left: Int) {
        if (left <= 0 || isDestroyed) return
        call(onResult = { r ->
            // The engine restarted with the real code: refresh the state line once it's back.
            when {
                r.startsWith("paired ") -> showMessage(
                    "Paired with the car at ${r.removePrefix("paired ").substringBefore(':')}. " +
                        "If the car didn't connect, another device may have taken the code: More > Unpair all cars.",
                    Tone.OK,
                )
                r == "expired" -> showMessage("Pairing timed out. Open tslink.app in the car and scan again.", Tone.WARN)
                r == "waiting" -> window.decorView.postDelayed({ watchPairing(left - 1) }, 1_000)
            }
        }) { it.pairingResult() }
    }

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
        setStatus("starting...")
        Prefs.setEngineWanted(this, true)
        askNotificationPermission()
        val e = engine
        if (e != null) call { it.startAndStatus() } else bindIfAllowed()
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
        binding = false
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
     * redraw the status card; anything else is shown as a message under its button.
     */
    private fun setStatus(s: String) = runOnUiThread {
        val st = EngineStatus.parse(s)
        val scan = Home.scanAfter(pendingScan, st)
        pendingScan = scan.pending
        val linked = pendingSecret
        if (!scan.pending) pendingSecret = null
        when (st) {
            is EngineStatus.Message -> {
                showMessage(st.text, Home.replyTone(s))
                // A failed start/check must not leave "Starting..." up.
                if (EngineStatus.isError(s)) lastStatus = EngineStatus.Stopped
            }
            else -> {
                if (Home.clearsNote(lastStatus, st)) showMessage(null)
                lastStatus = st
                // Off: the car hotspot may be off too (the car left, Disconnect).
                if (st == EngineStatus.Stopped) carHotspotOn = false
            }
        }
        render()
        if (scan.open) if (linked != null) startPairing(linked) else openScanner()
    }

    /** Draw the status card and the Start/Stop button from [lastStatus] and the setup state. */
    private fun render() {
        val v = Home.view(setupReady(), lastStatus)
        stateView.text = v.state
        ui.setDot(stateDot, when (v.color) { HomeColor.OK -> Ui.OK; HomeColor.WARN -> Ui.WARN; HomeColor.MUTED -> Ui.MUTED })
        hintView.text = v.hint.orEmpty()
        hintView.visibility = if (v.hint == null) View.GONE else View.VISIBLE
        carHotspotButton.visibility = if (v.hint != null) View.VISIBLE else View.GONE
        carHotspotButton.text = if (carHotspotOn) "Car hotspot is on ✓" else "Turn on car hotspot"
        primaryButton.text = when (v.primary) {
            Primary.FINISH_SETUP -> "Finish setup"
            Primary.PAIR -> "Pair a car (scan QR)"
            Primary.DISCONNECT -> "Disconnect"
        }
        ui.stylePrimary(primaryButton, primary = v.primary != Primary.DISCONNECT)
        startStopButton.text = if (engineRunning) "Stop TSLink" else "Start TSLink"
        val cert = (lastStatus as? EngineStatus.Running)?.cert
        certView.text = "Certificate $cert (the car page's debug view shows the same)"
        certView.visibility = if (cert != null) View.VISIBLE else View.GONE
    }

    private fun showMessage(s: String?, tone: Tone = Tone.INFO) {
        messageView.text = s.orEmpty()
        messageView.setTextColor(ui.color(tone))
        messageView.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun newCode() {
        val code = Prefs.newCode(this)
        codeView.text = code
        // The running engine still serves the old code: restart it.
        if (engine != null) call { it.stop(); it.startAndStatus(code) }
    }

    // ---- Hotspot ------------------------------------------------------------

    /**
     * The car only joins the hotspot in car mode (fixed address). The engine
     * can't report the mode, so the button shows what was set from here.
     */
    private fun carHotspot() {
        if (engine == null) return showMessage("Start TSLink first.", Tone.WARN)
        showMessage("Turning on the car hotspot...")
        call(onResult = { r ->
            if (r == "ok") {
                carHotspotOn = true
                render()
                showMessage("Car hotspot is on. Join it from the car, then open tslink.app.", Tone.OK)
            } else {
                showMessage("error: hotspot: $r", Tone.ERROR)
            }
        }) { it.carHotspot(true) }
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
            Prefs.CarText.LOCK_SCREEN -> "Default: like your lock screen, shown when it shows it (Settings > Notifications > sensitive notifications)."
            Prefs.CarText.ALWAYS -> "Always shown in the car, even when your lock screen hides it. Passengers can read it."
            Prefs.CarText.NEVER -> "Never shown in the car: only the app and the title (usually the sender; some apps put more there)."
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

    /** Encoders, encode speed and car display support, without a car (see SelfTest). */
    private fun deviceCheck() {
        // The message line is up in the Engine card: say it where the button is.
        val e = engine ?: return toast(if (bindIfAllowed()) "Starting TSLink, tap again in a moment." else "Finish setup first.")
        toast("Device check: about 15 seconds...")
        thread {
            val report = try { e.selfTest() } catch (t: Throwable) { "error: $t" }
            runOnUiThread {
                textDialog("Device check", report, "TSLink device check")
            }
        }
    }

    /** Recent engine log (logcat as shell), to read or share after a car test. */
    private fun showLog() {
        val e = engine ?: run {
            toast("Start TSLink first to see its log.")
            return
        }
        thread {
            val log = try { e.logs() } catch (t: Throwable) { "error: $t" }
            runOnUiThread { textDialog("TSLink log", log, "TSLink log") }
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
        ui.styleChoice(carName, selected = name != null)
        carView.text = note ?: if (name != null) "TSLink and the car hotspot start when the car connects." else "Choose your car so TSLink starts when you get in."
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
