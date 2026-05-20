package dev.pranav.reef.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.pranav.reef.receivers.SessionAlarmReceiver

/**
 * Schedules a single exact alarm for when the current phase ends.
 *
 * Uses [AlarmManager.setExactAndAllowWhileIdle] which fires even in Doze Mode —
 * this is what prevents the notification chronometer from going negative.
 *
 * SCHEDULE_EXACT_ALARM (not USE_EXACT_ALARM) is used to avoid Play Store policy issues.
 */
object PhaseAlarmManager {

    private const val TAG = "PhaseAlarmManager"
    private const val REQUEST_CODE = 8821

    /**
     * Schedule an exact alarm to fire in [remainingMs] milliseconds.
     * Cancels any previously scheduled alarm first.
     */
    fun schedule(context: Context, remainingMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancel(context)

        val triggerElapsed = SystemClock.elapsedRealtime() + remainingMs
        val pi = buildPendingIntent(context)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
                Log.i(TAG, "Exact alarm scheduled in ${remainingMs / 1000}s")
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
            }
        } catch (e: SecurityException) {
            // SCHEDULE_EXACT_ALARM not granted — fall back to inexact
            Log.w(TAG, "Exact alarm denied (permission not granted), falling back", e)
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildPendingIntent(context))
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SessionAlarmReceiver::class.java).apply {
            action = SessionAlarmReceiver.ACTION_PHASE_END
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
