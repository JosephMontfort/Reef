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
    private var defaultLauncherPkg: String? = null

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



    private val immediatCheckReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != "dev.pranav.reef.CHECK_FOREGROUND_NOW") return
            // Re-verify focus_mode here — the sender checked it before broadcasting but
            // prefs can change between send and receive (async delivery race condition).
            if (!prefs.getBoolean("focus_mode", false)) return
            if (!prefs.getBoolean("block_home_screen", false)) return
            checkForegroundApp()
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

        // Cache default launcher package for home-block mode
        defaultLauncherPkg = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }, 0
        )?.activityInfo?.packageName

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(immediatCheckReceiver,
                android.content.IntentFilter("dev.pranav.reef.CHECK_FOREGROUND_NOW"),
                RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(immediatCheckReceiver,
                android.content.IntentFilter("dev.pranav.reef.CHECK_FOREGROUND_NOW"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        handler.removeCallbacks(appPollRunnable)
        handler.removeCallbacks(routinePollRunnable)

        // Immediately check what's in foreground when service (re)starts —
        // needed when the nuclear watchdog restarts us while a blocked app
        // or the launcher is already visible.
        if (prefs.getBoolean("focus_mode", false)) {
            handler.post { checkForegroundApp() }
        }

        handler.post(appPollRunnable)
        handler.post(routinePollRunnable)
        scheduleWatcher(this)
        return START_STICKY
    }

    private fun promoteToForeground() {
        // Suppress the visible monitoring notification during an active focus session —
        // the FocusModeService already shows a timer notification; this one is redundant.
        val focusActive = prefs.getBoolean("focus_mode", false)
        val notification = NotificationCompat.Builder(this, APP_BLOCKER_SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.blocker_service_running))
            // Hidden during active focus (FocusModeService notification takes precedence).
            // Slightly visible during idle monitoring so users know the service is alive.
            .setPriority(if (focusActive) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .setVisibility(if (focusActive) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setSilent(true)
            .build()

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
    }

    private var lastKnownForegroundApp: String? = null

    private fun getCurrentForegroundApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        
        // Smart Deep-Scan: On cold boot (resurrection), look back 1 hour to catch long-running apps.
        // Once cached, we only look back 3 seconds to save battery on normal polls.
        val window = if (lastKnownForegroundApp == null) 60 * 60 * 1000L else EVENT_WINDOW_MS
        
        val events = usm.queryEvents(now - window, now)
        val event = UsageEvents.Event()
        
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastKnownForegroundApp = event.packageName
            } else if (event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (lastKnownForegroundApp == event.packageName) {
                    lastKnownForegroundApp = null
                }
            }
        }
        
        // Ultimate Fallback: If 1-hour scan found nothing, query daily usage stats
        if (lastKnownForegroundApp == null && window > EVENT_WINDOW_MS) {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24 * 60 * 60 * 1000L, now)
            if (stats != null) {
                var lastTimeUsed = 0L
                for (stat in stats) {
                    if (stat.lastTimeUsed > lastTimeUsed) {
                        lastTimeUsed = stat.lastTimeUsed
                        lastKnownForegroundApp = stat.packageName
                    }
                }
            }
        }
        
        return lastKnownForegroundApp
    }

    private fun checkForegroundApp() {
        val pkg = getCurrentForegroundApp() ?: return
        if (pkg == packageName) return  // Always allow Reef itself

        val now = System.currentTimeMillis()
        val focusMode = prefs.getBoolean("focus_mode", false)
        val blockHomeScreen = prefs.getBoolean("block_home_screen", false)

        // ── Home-block mode: persistent overlay for all non-whitelisted (incl. launcher) ──
        if (focusMode && blockHomeScreen) {
            val isAllowed = (packageManager.getLaunchIntentForPackage(pkg) == null || Whitelist.isWhitelisted(pkg)) && pkg != defaultLauncherPkg
            if (isAllowed) {
                // Whitelisted non-launcher app opened — dismiss overlay
                if (HomeBlockOverlayService.isShowing) {
                    HomeBlockOverlayService.stop(this)
                }
                blockAttempts.remove(pkg)
            } else if (!HomeBlockOverlayService.isShowing) {
                HomeBlockOverlayService.start(this)
            }
            return
        }

        // ── Normal focus mode: block non-whitelisted apps ──
        // Clear stale block attempts when focus mode is off — prevents the map from growing
        // indefinitely and avoids stale retries from a previous session firing after it ends.
        if (!focusMode && blockAttempts.isNotEmpty()) {
            blockAttempts.clear()
        }

        val attempt = blockAttempts[pkg]
        if (attempt != null && (now - attempt.time) < BLOCK_COOLDOWN_MS) return

        if (focusMode) {
            if (packageManager.getLaunchIntentForPackage(pkg) != null && packageManager.getLaunchIntentForPackage(pkg) != null && !Whitelist.isWhitelisted(pkg)) {
                FocusStats.recordBlockEvent(pkg, "focus_mode")
                triggerBlock(pkg, UsageTracker.BlockReason.FOCUS_MODE, now)
                return
            }
        }

        val blockReason = UsageTracker.checkBlockReason(this, pkg)
        if (blockReason != UsageTracker.BlockReason.NONE) {
            triggerBlock(pkg, blockReason, now)
        } else {
            blockAttempts.remove(pkg)
        }
    }

    private fun triggerBlock(pkg: String, reason: UsageTracker.BlockReason, now: Long) {
        val existing = blockAttempts[pkg]
        val retries = existing?.retries ?: 0

        if (retries >= MAX_RETRIES) {
            blockAttempts.remove(pkg)  // reset so next poll can try fresh
            return
        }

        Log.d(TAG, "Blocking $pkg reason=$reason retry=$retries")
        blockAttempts[pkg] = BlockAttempt(now, retries)
        launchBlockedActivity(pkg, reason)

        // Verify overlay shown (retry logic for non-home-block mode)
        if (!prefs.getBoolean("block_home_screen", false)) {
            handler.postDelayed({
                val currentFg = getCurrentForegroundApp()
                if (currentFg == pkg && prefs.getBoolean("focus_mode", false) &&
                    packageManager.getLaunchIntentForPackage(pkg) != null && !Whitelist.isWhitelisted(pkg)) {
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
    }

    private fun launchBlockedActivity(pkg: String, reason: UsageTracker.BlockReason) {
        try {
            val intent = Intent(this, BlockedActivity::class.java).apply {
                putExtra(BlockedActivity.EXTRA_BLOCKED_PKG, pkg)
                putExtra(BlockedActivity.EXTRA_BLOCK_REASON, reason.name)
                // CLEAR_TOP + SINGLE_TOP: if BlockedActivity is already on top, deliver
                // onNewIntent() instead of creating a new instance and stacking it.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
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
        try { unregisterReceiver(immediatCheckReceiver) } catch (_: Exception) {}
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
