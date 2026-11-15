package app.carlink.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.carlink.Prefs
import app.carlink.R
import app.carlink.auto.carEvents
import app.carlink.auto.withEngine
import app.carlink.ui.MainActivity

/**
 * "Connected to car" while a car session is up, like Android Auto's: visible
 * on the lock screen, so it's clear why the phone is kept awake (dark) and how
 * to end it. Our own ongoing notification is never forwarded to the car (the
 * listener skips ongoing ones).
 */
object CarSessionNotice {
    private const val CHANNEL = "car"
    private const val ID = 1

    fun update(ctx: Context, up: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!up) return nm.cancel(ID)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Car connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while your phone's apps are on the car screen"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, Disconnect::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_car)
            .setContentTitle("Connected to car")
            .setContentText("Your apps are on the car screen")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build())
            .build()
        try {
            nm.notify(ID, n)
        } catch (e: SecurityException) {
            Log.w("carlink-notif", "no notification permission: $e") // Android 13+, not granted
        }
    }

    /** "Disconnect": stop the engine (the car disconnects, the phone sleeps normally). */
    class Disconnect : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext
            Prefs.setEngineWanted(app, false)
            update(app, false)
            val pending = goAsync()
            // Same queue as Bluetooth auto start/stop, so they can't interleave.
            carEvents.execute {
                try {
                    withEngine { it.stop() }
                } catch (e: Exception) {
                    Log.w("carlink-notif", "disconnect: $e")
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
