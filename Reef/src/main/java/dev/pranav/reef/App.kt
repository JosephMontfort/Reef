package dev.pranav.reef

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.material3.ColorScheme
import androidx.work.*
import dev.pranav.reef.accessibility.AppBlockerService
import dev.pranav.reef.receivers.DailySummaryScheduler
import dev.pranav.reef.services.routines.RoutineAlarmScheduler
import dev.pranav.reef.services.routines.RoutineSessionManager
import dev.pranav.reef.util.*
import java.util.concurrent.TimeUnit

class App : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        setupSafePreferences()

        AppLimits.init(this)
        Whitelist.init(this)
        FocusStats.init(this)
        FocusStats.initCheckpoint(this)
        // If there's accumulated focus time from a force-stopped session (checkpoint exists)
        // but activeSession is null (memory was cleared), flush the partial record now.
        FocusStats.flushOrphanedSession()
        WebsiteBlocklist.init(this)

        // Start the UsageStats-based app blocker immediately
        AppBlockerService.start(this)

        // Recover any focus session that was interrupted by a force-stop
        // Bug2: use startForegroundService; Bug13: only if not already running
        if (SessionPersistence.hasActiveSession(this) &&
            !dev.pranav.reef.timer.TimerStateManager.state.value.isRunning) {
            try {
                startForegroundService(Intent(this, dev.pranav.reef.accessibility.FocusModeService::class.java).apply {
                    action = dev.pranav.reef.accessibility.FocusModeService.ACTION_RESUME_PERSISTED
                })
            } catch (e: Exception) {
                android.util.Log.e("App", "Session recovery failed", e)
            }
        }

        scheduleWatcher(this)

        RoutineSessionManager.evaluateAndSync(this)
        NotificationHelper.syncRoutineNotification(this)
        RoutineAlarmScheduler.scheduleAll(this, dev.pranav.reef.routine.Routines.getAll())

        if (prefs.getBoolean("daily_summary", false)) {
            DailySummaryScheduler.scheduleDailySummary(this)
        }

        setupCrashHandler()
    }

    private fun setupSafePreferences() {
        val deviceContext = createDeviceProtectedStorageContext()
        deviceContext.moveSharedPreferencesFrom(this, "prefs")
        prefs = deviceContext.getSharedPreferences("prefs", MODE_PRIVATE)
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            Log.e("ReefApp", "CRITICAL CRASH: $stackTrace")

            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val currentTime = System.currentTimeMillis()

            // Restart the UsageStats-based app blocker after a crash
            val appBlockerIntent = Intent(this, AppBlockerService::class.java)
            val appBlockerPendingIntent = PendingIntent.getService(
                this,
                111,
                appBlockerIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            // Use setExactAndAllowWhileIdle — crash recovery needs a guaranteed 1-second
            // trigger. AlarmManager.set() is inexact on API 19+ and may fire 30+ min late.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, currentTime + 1000, appBlockerPendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, currentTime + 1000, appBlockerPendingIntent)
            }

            // Show DebugActivity with the error details
            val debugIntent = Intent(this, DebugActivity::class.java).apply {
                putExtra("error", stackTrace)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val debugPendingIntent = PendingIntent.getActivity(
                this,
                112,
                debugIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, currentTime + 1500, debugPendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, currentTime + 1500, debugPendingIntent)
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        lateinit var colorScheme: ColorScheme
    }
}


fun scheduleWatcher(context: Context) {
    val workRequest = PeriodicWorkRequestBuilder<ReefWorker>(
        15, TimeUnit.MINUTES,
        5, TimeUnit.MINUTES
    ).setConstraints(Constraints.NONE).build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "ReefSafetyNet",
        ExistingPeriodicWorkPolicy.KEEP,
        workRequest
    )
}
