package app.tslink.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.tslink.Prefs
import app.tslink.notify.Listener
import rikka.shizuku.Shizuku

/**
 * First-run setup and later checks: each step shows its live state and one
 * action. States are re-read on every return to this screen (from Shizuku,
 * system settings) and when Shizuku starts, stops or answers.
 */
@SuppressLint("SetTextI18n") // English-only UI built in code
class SetupActivity : Activity() {
    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQ_SHIZUKU = 11
        private const val REQ_NOTIFICATIONS = 12
        private const val REQ_BLUETOOTH = 13
    }

    private val ui = Ui(this)

    /** A step's views, built once (the steps only depend on the phone's brand), updated by [refresh]. */
    private class StepViews(val dot: View, val title: TextView, val state: TextView, val text: TextView, val action: Button, val tick: Button)

    private val views = LinkedHashMap<StepId, StepViews>()
    private lateinit var summaryView: TextView
    private val brand = Brand.of(Build.MANUFACTURER, Build.BRAND)
    private var askedNotifications = false

    private val shizukuChanged = Shizuku.OnBinderReceivedListener { runOnUiThread(::refresh) }
    private val shizukuDead = Shizuku.OnBinderDeadListener { runOnUiThread(::refresh) }
    private val shizukuAnswered = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == REQ_SHIZUKU) runOnUiThread(::refresh)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.setSetupSeen(this) // opens by itself once; later from Diagnostics
        setContentView(buildScreen())
        Shizuku.addBinderReceivedListener(shizukuChanged)
        Shizuku.addBinderDeadListener(shizukuDead)
        Shizuku.addRequestPermissionResultListener(shizukuAnswered)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuChanged)
        Shizuku.removeBinderDeadListener(shizukuDead)
        Shizuku.removeRequestPermissionResultListener(shizukuAnswered)
        super.onDestroy()
    }

    // ---- Layout -------------------------------------------------------------

    private fun buildScreen(): View {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ui.text(26f, bold = true).apply { text = "Setup" })
        content.addView(ui.line("Get the phone ready for the car. Come back here any time from Diagnostics."))
        summaryView = ui.text(16f, bold = true).apply { setPadding(0, ui.dp(8), 0, ui.dp(16)) }
        content.addView(summaryView)

        var number = 0
        for (step in Setup.steps(facts())) {
            val card = ui.card(content, if (step.optional) "Optional" else "Step ${++number}")
            val dot = ui.dot()
            val title = ui.text(17f, bold = true)
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dot)
                addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            val state = ui.line(size = 13f)
            card.addView(state)
            val text = ui.line()
            card.addView(text)
            val action = ui.button("", primary = true) { act(step.id) }
            val tick = ui.textButton("") { toggleTick(step.id) }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(action, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(tick, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ui.dp(8) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(12) })
            views[step.id] = StepViews(dot, title, state, text, action, tick)
        }

        content.addView(ui.button("Done", primary = true) { finish() }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(4) })
        return ui.screen(window, content)
    }

    private fun refresh() {
        val steps = Setup.steps(facts())
        summaryView.text = Setup.summary(steps)
        summaryView.setTextColor(if (Setup.allRequiredReady(steps)) Ui.OK else Ui.TEXT)
        for (step in steps) {
            val v = views[step.id] ?: continue
            v.title.text = step.title
            v.text.text = step.text
            val color = when (step.state) {
                StepState.OK -> Ui.OK
                StepState.ACTION -> if (step.optional) Ui.MUTED else Ui.WARN
                StepState.UNKNOWN -> Ui.MUTED
            }
            ui.setDot(v.dot, color)
            v.state.text = Setup.stateLabel(step)
            v.state.setTextColor(color)
            v.action.visibility = if (step.action != null) View.VISIBLE else View.GONE
            v.action.text = step.action.orEmpty()
            v.action.isEnabled = step.actionEnabled
            v.action.alpha = if (step.actionEnabled) 1f else 0.4f
            // Filled while it needs doing, outlined once it's done.
            ui.stylePrimary(v.action, primary = step.state != StepState.OK && step.actionEnabled)
            v.tick.visibility = if (step.tickable) View.VISIBLE else View.GONE
            v.tick.text = if (step.state == StepState.OK) "Not done" else "Mark done"
        }
    }

    // ---- Checks -------------------------------------------------------------

    private fun facts(): SetupFacts {
        val running = Shizuku.pingBinder()
        return SetupFacts(
            shizukuInstalled = shizukuInstalled(),
            shizukuRunning = running,
            shizukuAllowed = if (running) shizukuAllowed() else null,
            notificationPermission = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            carNotifications = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener),
            batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            carChosen = Prefs.carAddress(this) != null,
            brand = brand,
            ticked = Prefs.setupTicked(this).mapNotNull { n -> StepId.entries.firstOrNull { it.name == n } }.toSet(),
        )
    }

    private fun shizukuInstalled() = try {
        packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Null if Shizuku went away between the ping and the question. */
    private fun shizukuAllowed(): Boolean? = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: RuntimeException) {
        null
    }

    private val listener get() = ComponentName(this, Listener::class.java)

    // ---- Actions ------------------------------------------------------------

    private fun act(id: StepId) {
        when (id) {
            StepId.SHIZUKU_INSTALLED -> open(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE")),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")),
            )
            StepId.SHIZUKU_RUNNING -> openShizuku()
            StepId.SHIZUKU_ALLOWED -> allowInShizuku()
            StepId.BATTERY, StepId.OPPO_BACKGROUND -> open(appInfo())
            StepId.HOTSPOT -> open(Intent("android.settings.TETHER_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            StepId.XIAOMI_USB_SECURITY -> open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            StepId.XIAOMI_AUTOSTART -> open(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                appInfo(),
            )
            StepId.SAMSUNG_SLEEP -> open(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), appInfo())
            StepId.NOTIFICATION_PERMISSION -> askNotifications()
            StepId.CAR_NOTIFICATIONS -> open(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString()),
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            )
            StepId.CAR -> CarPicker.pick(this, REQ_BLUETOOTH, ::carPicked)
        }
    }

    /** Start the first of [intents] the phone has (settings pages differ between makers). */
    private fun open(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        toast("This phone has no such settings page. Look for it in Settings.")
    }

    private fun appInfo() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launch != null) open(launch) else toast("Install Shizuku first.")
    }

    private fun allowInShizuku() {
        if (!Shizuku.pingBinder()) {
            toast("Start Shizuku first.")
            return
        }
        try {
            // Denied with "don't ask again": only Shizuku's own list can change it now.
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast("In Shizuku, open Authorized applications and allow TSLink.")
                openShizuku()
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU)
            }
        } catch (_: RuntimeException) {
            refresh() // Shizuku stopped meanwhile
        }
    }

    /** Ask once; after a refusal Android won't ask again, so open the app's notification settings. */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (askedNotifications && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName), appInfo())
            return
        }
        askedNotifications = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    private fun carPicked(note: String?) {
        if (note != null) toast(note)
        refresh()
    }

    private fun toggleTick(id: StepId) {
        val ticked = Prefs.setupTicked(this)
        Prefs.setSetupTicked(this, if (id.name in ticked) ticked - id.name else ticked + id.name)
        refresh()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_BLUETOOTH -> if (granted) CarPicker.pick(this, REQ_BLUETOOTH, ::carPicked) else carPicked(CarPicker.PERMISSION_NOTE)
            else -> refresh()
        }
    }
}
