package dev.pranav.reef.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.pranav.reef.accessibility.AppBlockerService
import dev.pranav.reef.accessibility.FocusModeService
import dev.pranav.reef.services.routines.RoutineSessionManager
import dev.pranav.reef.util.SessionPersistence

class ReefWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val safeContext = applicationContext.createDeviceProtectedStorageContext()
        val prefs = safeContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // Ensure the UsageStats-based app blocker is alive.
        // startForegroundService is safe to call even when already running.
        AppBlockerService.start(safeContext)

        val isFocusModeActive = prefs.getBoolean("focus_mode", false)
        if (isFocusModeActive && SessionPersistence.hasActiveSession(safeContext)) {
            try {
                safeContext.startForegroundService(
                    Intent(safeContext, FocusModeService::class.java).apply {
                        action = FocusModeService.ACTION_RESUME_PERSISTED
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not restart FocusModeService", e)
            }
        }

        if (!isPrefsInitialized) {
            dev.pranav.reef.util.prefs =
                safeContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        }

        RoutineSessionManager.evaluateAndSync(safeContext)
        NotificationHelper.syncRoutineNotification(safeContext)

        return Result.success()
    }

    companion object {
        private const val TAG = "ReefWorker"
    }
}
