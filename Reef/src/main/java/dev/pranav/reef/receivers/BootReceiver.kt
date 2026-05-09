package dev.pranav.reef.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import dev.pranav.reef.accessibility.AppBlockerService
import dev.pranav.reef.accessibility.BlockerService
import dev.pranav.reef.accessibility.FocusModeService
import dev.pranav.reef.services.routines.RoutineAlarmScheduler
import dev.pranav.reef.services.routines.RoutineSessionManager
import dev.pranav.reef.util.NotificationHelper
import dev.pranav.reef.util.isAccessibilityServiceEnabledForBlocker
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val safeContext = context.createDeviceProtectedStorageContext()

        if (!isPrefsInitialized) {
            prefs = safeContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        }

        Log.d("BootReceiver", "Action received: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT -> {
                refreshServices(safeContext)

                RoutineSessionManager.evaluateAndSync(safeContext)
                NotificationHelper.syncRoutineNotification(safeContext)
                RoutineAlarmScheduler.scheduleAll(
                    safeContext,
                    dev.pranav.reef.routine.Routines.getAll()
                )

                if (prefs.getBoolean("daily_summary", false)) {
                    DailySummaryScheduler.scheduleDailySummary(safeContext)
                }

                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    prefs.edit { putBoolean("show_dialog", true) }
                }
            }
        }
    }

    private fun refreshServices(context: Context) {
        // Always start the UsageStats-based app blocker — it doesn't need any special
        // user-granted toggle, only the Usage Access permission.
        AppBlockerService.start(context)

        // Also nudge the accessibility service if the user has it enabled
        // (needed for website/browser blocking only).
        if (context.isAccessibilityServiceEnabledForBlocker()) {
            try {
                context.startService(Intent(context, BlockerService::class.java))
            } catch (e: Exception) {
                Log.e("BootReceiver", "Could not nudge BlockerService", e)
            }
        }

        if (prefs.getBoolean("focus_mode", false)) {
            val serviceIntent = Intent(context, FocusModeService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
