package dev.pranav.reef.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.pranav.reef.accessibility.AppBlockerService

object ResilienceManager {
    private const val TAG = "ResilienceManager"
    const val ACTION_RESILIENCE_PING = "dev.pranav.reef.RESILIENCE_PING"
    private const val REQUEST_CODE = 9901
    private const val INTERVAL_MS = 60_000L

    // User's toggle preference — never changed by start()/stop() internally
    private const val PREF_USER_ENABLED = "resilience_mode_enabled"
    // Runtime flag — true while an alarm is scheduled for this session
    private const val PREF_RUNNING = "resilience_running"

    /** Device-protected prefs — readable before first unlock, consistent with global prefs. */
    private fun sp(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun isUserEnabled(context: Context) = sp(context).getBoolean(PREF_USER_ENABLED, false)

    fun setUserEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(PREF_USER_ENABLED, enabled).apply()
        // Keep runtime global prefs in sync if already initialised
        if (isPrefsInitialized) prefs.edit().putBoolean(PREF_USER_ENABLED, enabled).apply()
    }

    /** Called when a focus session starts (if user has resilience enabled). */
    fun start(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context)
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            else
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            sp(context).edit().putBoolean(PREF_RUNNING, true).apply()
            if (isPrefsInitialized) prefs.edit().putBoolean(PREF_RUNNING, true).apply()
            Log.i(TAG, "Resilience alarm scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm", e)
        }
    }

    /** Called when a focus session ends. Cancels alarm but does NOT touch user preference. */
    fun stop(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildPendingIntent(context))
        sp(context).edit().putBoolean(PREF_RUNNING, false).apply()
        if (isPrefsInitialized) prefs.edit().putBoolean(PREF_RUNNING, false).apply()
        Log.i(TAG, "Resilience alarm cancelled")
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_RESILIENCE_PING).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class ResilienceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ResilienceManager.ACTION_RESILIENCE_PING) return
        // Only reschedule if session is still active; user pref is not consulted
        // here because stop() already cancelled the alarm when session ended.
        if (SessionPersistence.hasActiveSession(context)) {
            AppBlockerService.start(context)
            ResilienceManager.start(context)
        } else {
            ResilienceManager.stop(context)
        }
    }
}

