package dev.pranav.reef.accessibility

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.pranav.reef.BlockedActivity
import dev.pranav.reef.R
import dev.pranav.reef.scheduleWatcher
import dev.pranav.reef.services.routines.RoutineSessionManager
import dev.pranav.reef.util.FocusStats
import dev.pranav.reef.util.NotificationHelper.createNotificationChannel
import dev.pranav.reef.util.NotificationHelper.syncRoutineNotification
import dev.pranav.reef.util.Whitelist
import dev.pranav.reef.util.BLOCKER_CHANNEL_ID
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs

class AppBlockerService : android.app.Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var keyguardManager: KeyguardManager? = null
    private var isScreenOn = true

    // Per-package cooldown to avoid spam-launching BlockedActivity when UsageStats
    // events lag slightly behind the actual foreground switch.
    private val lastBlockedTime = mutableMapOf<String, Long>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> isScreenOn = false
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> isScreenOn = true
            }
        }
    }

    // App-blocking poll — runs every second.
    private val appPollRunnable = object : Runnable {
        override fun run() {
            try {
                if (isScreenOn && keyguardManager?.isKeyguardLocked != true) {
                    checkForegroundApp()
                }
            } catch (e: Exception) {
                Log.e(TAG, "App poll error", e)
            }
            handler.postDelayed(this, APP_POLL_INTERVAL_MS)
        }
    }

    // Routine sync poll — runs every 30 seconds.
    private val routinePollRunnable = object : Runnable {
        override fun run() {
            try {
                RoutineSessionManager.evaluateAndSync(this@AppBlockerService)
                syncRoutineNotification(this@AppBlockerService)
            } catch (e: Exception) {
                Log.e(TAG, "Routine poll error", e)
            }
            handler.postDelayed(this, ROUTINE_POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (!isPrefsInitialized) {
            val deviceContext = createDeviceProtectedStorageContext()
            prefs = deviceContext.getSharedPreferences("prefs", MODE_PRIVATE)
        }

        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        // Re-posting is safe; Handler deduplicates identical Runnable references only
        // when using removeCallbacks first, so remove before re-posting to avoid stacking.
        handler.removeCallbacks(appPollRunnable)
        handler.removeCallbacks(routinePollRunnable)
        handler.post(appPollRunnable)
        handler.post(routinePollRunnable)
        scheduleWatcher(this)
        return START_STICKY
    }

    private fun promoteToForeground() {
        val notification = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.blocker_service_running))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
    }

    /**
     * Reads the most recent MOVE_TO_FOREGROUND event from UsageStats within the last
     * [EVENT_WINDOW_MS] ms. Returns null if usage-stats permission is not granted or
     * no event is found in the window.
     */
    private fun getCurrentForegroundApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - EVENT_WINDOW_MS, now)
        val event = UsageEvents.Event()
        var lastPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPkg = event.packageName
            }
        }
        return lastPkg
    }

    private fun checkForegroundApp() {
        val pkg = getCurrentForegroundApp() ?: return

        // Skip our own package (e.g. BlockedActivity is in foreground)
        if (pkg == packageName) return

        val now = System.currentTimeMillis()

        // Per-package cooldown prevents spam when UsageStats lags behind the real switch
        val lastTime = lastBlockedTime[pkg] ?: 0L
        if (now - lastTime < BLOCK_COOLDOWN_MS) return

        // Focus mode: block everything not whitelisted
        if (prefs.getBoolean("focus_mode", false)) {
            if (!Whitelist.isWhitelisted(pkg)) {
                FocusStats.recordBlockEvent(pkg, "focus_mode")
                lastBlockedTime[pkg] = now
                launchBlockedActivity(pkg, UsageTracker.BlockReason.ROUTINE_LIMIT)
                return
            }
        }

        // Daily-limit / routine-limit check
        val blockReason = UsageTracker.checkBlockReason(this, pkg)
        if (blockReason != UsageTracker.BlockReason.NONE) {
            lastBlockedTime[pkg] = now
            launchBlockedActivity(pkg, blockReason)
        }
    }

    private fun launchBlockedActivity(pkg: String, reason: UsageTracker.BlockReason) {
        Log.d(TAG, "Blocking $pkg reason=$reason")
        val intent = Intent(this, BlockedActivity::class.java).apply {
            putExtra(BlockedActivity.EXTRA_BLOCKED_PKG, pkg)
            putExtra(BlockedActivity.EXTRA_BLOCK_REASON, reason.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        startActivity(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(appPollRunnable)
        handler.removeCallbacks(routinePollRunnable)
        stopForeground(STOP_FOREGROUND_REMOVE)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "AppBlockerService"
        private const val NOTIFICATION_ID = 9001
        private const val APP_POLL_INTERVAL_MS = 1_000L
        private const val ROUTINE_POLL_INTERVAL_MS = 30_000L
        private const val EVENT_WINDOW_MS = 3_000L   // look back 3 s for foreground events
        private const val BLOCK_COOLDOWN_MS = 2_500L // per-package re-block cooldown

        fun start(context: Context) {
            try {
                val intent = Intent(context, AppBlockerService::class.java)
                context.startForegroundService(intent)
                Log.d(TAG, "AppBlockerService start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AppBlockerService", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AppBlockerService::class.java))
        }
    }
}
