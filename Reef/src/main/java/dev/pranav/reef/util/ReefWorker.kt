package dev.pranav.reef.util

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.pranav.reef.R
import dev.pranav.reef.accessibility.AppBlockerService
import dev.pranav.reef.accessibility.FocusModeService
import dev.pranav.reef.services.routines.RoutineSessionManager

class ReefWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val safeContext = applicationContext.createDeviceProtectedStorageContext()
        val prefs = safeContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // Ensure the UsageStats-based app blocker is alive.
        // startForegroundService is safe to call if already running — onStartCommand
        // will simply re-arm the polling loop.
        if (!isAppBlockerServiceRunning(safeContext)) {
            Log.d(TAG, "AppBlockerService not running — restarting")
            AppBlockerService.start(safeContext)
        }

        // If accessibility is enabled but not for website blocking, optionally warn the user.
        // We no longer block the app from working just because accessibility is off.
        if (!safeContext.isAccessibilityServiceEnabledForBlocker()) {
            Log.d(TAG, "Accessibility not enabled — website blocking unavailable")
            // Only send a notification if the user had website limits set up.
            // For now we stay silent to avoid pestering users who don't use website blocking.
        }

        val isFocusModeActive = prefs.getBoolean("focus_mode", false)
        if (isFocusModeActive) {
            try {
                safeContext.startForegroundService(
                    Intent(safeContext, FocusModeService::class.java)
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

    @Suppress("DEPRECATION")
    private fun isAppBlockerServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == AppBlockerService::class.java.name }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun sendInstantNotification(
        context: Context,
        channelId: String,
        channelName: String,
        title: String,
        message: String,
        notificationId: Int = System.currentTimeMillis().toInt()
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Channel for Reef alerts" }
        manager.createNotificationChannel(channel)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {}
    }

    companion object {
        private const val TAG = "ReefWorker"
    }
}
