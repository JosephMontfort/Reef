package dev.pranav.reef.watchdog

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.pranav.reef.R
import dev.pranav.reef.util.SessionPersistence

/**
 * Runs in a SEPARATE process (:watchdog). Android's force-stop only kills the
 * main process (dev.pranav.reef). This process keeps running, periodically
 * checking if the main process is alive and restarting AppBlockerService if not.
 *
 * This is the ONLY reliable non-root way to survive force-stop — AlarmManager
 * broadcasts, WorkManager, and JobScheduler are all silently blocked when the
 * main process is force-stopped (Android API 31+ security restriction).
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "WatchdogService"
        const val ACTION_START = "dev.pranav.reef.watchdog.START"
        const val ACTION_STOP  = "dev.pranav.reef.watchdog.STOP"
        private const val NOTIF_ID = 9911
        private const val CHANNEL_ID = "reef_watchdog_channel"
        private const val CHECK_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, WatchdogService::class.java).apply { action = ACTION_START }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WatchdogService", e)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WatchdogService::class.java).apply { action = ACTION_STOP })
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            try {
                checkAndRevive()
            } catch (e: Exception) {
                Log.e(TAG, "Check failed", e)
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        promoteToForeground()
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        super.onDestroy()
    }

    private fun checkAndRevive() {
        // Read session persistence from device-protected storage — accessible
        // even from this separate process without the main app running
        val sp = createDeviceProtectedStorageContext()
            .getSharedPreferences("session_persistence", Context.MODE_PRIVATE)
        val sessionActive = sp.getBoolean("sp_active", false)

        if (!sessionActive) {
            // Session ended — no need to keep watching
            stopSelf()
            return
        }

        // Check if main app process is alive
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mainAlive = am.runningAppProcesses?.any { proc ->
            proc.processName == packageName &&
            proc.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
        } ?: false

        if (!mainAlive) {
            Log.i(TAG, "Main process dead — reviving AppBlockerService")
            reviveMainService()
        }
    }

    private fun reviveMainService() {
        try {
            val intent = Intent().apply {
                setClassName(packageName, "$packageName.accessibility.AppBlockerService")
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Revive failed", e)
        }
    }

    private fun promoteToForeground() {
        // Create notification channel (we're in a separate process so can't share the main app's channel manager easily)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = android.app.NotificationChannel(
                    CHANNEL_ID, "Reef Watchdog",
                    android.app.NotificationManager.IMPORTANCE_MIN
                ).apply { setShowBadge(false) }
                nm.createNotificationChannel(ch)
            }
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Focus session protected")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .build()

        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, fgType)
    }
}
