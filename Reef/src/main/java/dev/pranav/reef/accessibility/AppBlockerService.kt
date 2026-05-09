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
import dev.pranav.reef.util.APP_BLOCKER_SERVICE_CHANNEL_ID
import dev.pranav.reef.util.FocusStats
import dev.pranav.reef.util.NotificationHelper.createNotificationChannel
import dev.pranav.reef.util.NotificationHelper.syncRoutineNotification
import dev.pranav.reef.util.Whitelist
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs

class AppBlockerService : android.app.Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var keyguardManager: KeyguardManager? = null
    private var isScreenOn = true

    // Track block state per pkg: timestamp + retry count
    private data class BlockAttempt(val time: Long, val retries: Int)
    private val blockAttempts = mutableMapOf<String, BlockAttempt>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> isScreenOn = false
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> isScreenOn = true
            }
        }
    }

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
            prefs = createDeviceProtectedStorageContext()
                .getSharedPreferences("prefs", MODE_PRIVATE)
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
        handler.removeCallbacks(appPollRunnable)
        handler.removeCallbacks(routinePollRunnable)
        handler.post(appPollRunnable)
        handler.post(routinePollRunnable)
        scheduleWatcher(this)
        return START_STICKY
    }

    private fun promoteToForeground() {
        val notification = NotificationCompat.Builder(this, APP_BLOCKER_SERVICE_CHANNEL_ID)
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
        if (pkg == packageName) return

        val now = System.currentTimeMillis()
        val attempt = blockAttempts[pkg]

        // Cooldown: don't re-attempt within 2.5s unless it's a retry
        if (attempt != null && (now - attempt.time) < BLOCK_COOLDOWN_MS && attempt.retries == 0) return

        // Focus mode: block everything not whitelisted
        if (prefs.getBoolean("focus_mode", false)) {
            if (!Whitelist.isWhitelisted(pkg)) {
                FocusStats.recordBlockEvent(pkg, "focus_mode")
                triggerBlock(pkg, UsageTracker.BlockReason.ROUTINE_LIMIT, now)
                return
            }
        }

        val blockReason = UsageTracker.checkBlockReason(this, pkg)
        if (blockReason != UsageTracker.BlockReason.NONE) {
            triggerBlock(pkg, blockReason, now)
        } else {
            // App is now allowed — clear any pending retry state
            blockAttempts.remove(pkg)
        }
    }

    private fun triggerBlock(pkg: String, reason: UsageTracker.BlockReason, now: Long) {
        val existing = blockAttempts[pkg]
        val retries = existing?.retries ?: 0

        if (retries > MAX_RETRIES) {
            // Give up after too many retries — cleared on next allowed detection
            return
        }

        Log.d(TAG, "Blocking $pkg reason=$reason retry=$retries")
        blockAttempts[pkg] = BlockAttempt(now, retries)
        launchBlockedActivity(pkg, reason)

        // Schedule a verification check: if blocked app is still foreground in 800ms,
        // the overlay may have failed — retry up to MAX_RETRIES times.
        handler.postDelayed({
            val currentFg = getCurrentForegroundApp()
            if (currentFg == pkg && prefs.getBoolean("focus_mode", false) &&
                !Whitelist.isWhitelisted(pkg)) {
                val prev = blockAttempts[pkg] ?: return@postDelayed
                blockAttempts[pkg] = prev.copy(
                    time = System.currentTimeMillis(),
                    retries = prev.retries + 1
                )
                Log.w(TAG, "Overlay may have failed for $pkg — retrying (attempt ${prev.retries + 1})")
                launchBlockedActivity(pkg, reason)
            }
        }, OVERLAY_VERIFY_DELAY_MS)
    }

    private fun launchBlockedActivity(pkg: String, reason: UsageTracker.BlockReason) {
        try {
            val intent = Intent(this, BlockedActivity::class.java).apply {
                putExtra(BlockedActivity.EXTRA_BLOCKED_PKG, pkg)
                putExtra(BlockedActivity.EXTRA_BLOCK_REASON, reason.name)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch BlockedActivity for $pkg", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(appPollRunnable)
        handler.removeCallbacks(routinePollRunnable)
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "AppBlockerService"
        private const val NOTIFICATION_ID = 9001
        private const val APP_POLL_INTERVAL_MS = 1_000L
        private const val ROUTINE_POLL_INTERVAL_MS = 30_000L
        private const val EVENT_WINDOW_MS = 3_000L
        private const val BLOCK_COOLDOWN_MS = 2_500L
        private const val OVERLAY_VERIFY_DELAY_MS = 800L
        private const val MAX_RETRIES = 3

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, AppBlockerService::class.java))
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
