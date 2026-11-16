package app.tslink.auto

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import app.tslink.Prefs
import app.tslink.notify.CarSessionNotice

/**
 * Stops what auto-start started, a while after the car disconnected, unless it
 * reconnected meanwhile. A job because receivers can't wait that long.
 */
class CarStopJob : JobService() {
    companion object {
        private const val TAG = "tslink-auto"
        private const val JOB_ID = 4433

        fun schedule(ctx: Context, delayMs: Long) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, CarStopJob::class.java))
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 30_000)
                .setExtras(PersistableBundle().apply { putLong("disconnected_at", System.currentTimeMillis()) })
                .build()
            ctx.getSystemService(JobScheduler::class.java).schedule(job)
        }

        /** Stop only if nothing reconnected after the disconnect; pure for tests. */
        fun shouldStop(autoStarted: Boolean, lastConnect: Long, disconnectedAt: Long) = autoStarted && lastConnect < disconnectedAt
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        val at = params.extras.getLong("disconnected_at")
        if (!shouldStop(Prefs.autoStarted(app), Prefs.lastCarConnect(app), at)) return false
        carEvents.execute {
            try {
                withEngine { e ->
                    e.stop()
                    Log.i(TAG, "car gone: engine stopped, hotspot ${e.hotspotOff()}")
                }
                Prefs.setAutoStarted(app, false)
                Prefs.setEngineWanted(app, false)
                CarSessionNotice.update(app, false)
            } catch (t: Throwable) {
                Log.w(TAG, "auto stop failed: $t")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
