package dev.pranav.reef.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.pranav.reef.accessibility.AppBlockerService

/**
 * Non-root resilience: uses AlarmManager to re-check and restart AppBlockerService
 * every 60 seconds. Unlike the root watchdog, this cannot survive a force-stop
 * (Android prevents any broadcast from waking a force-stopped app without root),
 * but it handles normal kills, OOM kills, and keeps the service alive through
 * battery-saver events and task-manager swipes.
 */
object ResilienceManager {
    private const val TAG = "ResilienceManager"
    private const val ACTION_RESILIENCE_PING = "dev.pranav.reef.RESILIENCE_PING"
    private const val REQUEST_CODE = 9901
    private const val INTERVAL_MS = 60_000L // 60 seconds

    fun start(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildIntent(context)
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            prefs.edit().putBoolean("resilience_mode_enabled", true).apply()
            Log.i(TAG, "Resilience alarm scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm", e)
        }
    }

    fun stop(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildIntent(context))
        prefs.edit().putBoolean("resilience_mode_enabled", false).apply()
        Log.i(TAG, "Resilience alarm cancelled")
    }

    private fun buildIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_RESILIENCE_PING).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Manifest-registered receiver — wakes the app on each alarm tick. */
class ResilienceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "dev.pranav.reef.RESILIENCE_PING") {
            // Re-arm for next tick
            if (prefs.getBoolean("resilience_mode_enabled", false) &&
                SessionPersistence.hasActiveSession(context)) {
                AppBlockerService.start(context)
                ResilienceManager.start(context) // reschedule
            }
        }
    }
}
