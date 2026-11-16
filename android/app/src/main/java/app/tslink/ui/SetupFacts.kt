package app.tslink.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import app.tslink.Prefs
import app.tslink.notify.Listener
import rikka.shizuku.Shizuku

const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/** What the phone says right now, for [Setup] (setup screen and the main screen's next action). */
fun Context.setupFacts(): SetupFacts {
    val running = Shizuku.pingBinder()
    return SetupFacts(
        shizukuInstalled = shizukuInstalled(),
        shizukuRunning = running,
        shizukuAllowed = if (running) shizukuAllowed() else null,
        notificationPermission = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        carNotifications = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, Listener::class.java)),
        batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        carChosen = Prefs.carAddress(this) != null,
        brand = Brand.of(Build.MANUFACTURER, Build.BRAND),
        ticked = Prefs.setupTicked(this).mapNotNull { n -> StepId.entries.firstOrNull { it.name == n } }.toSet(),
    )
}

private fun Context.shizukuInstalled() = try {
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
