package dev.pranav.reef.accessibility

import android.content.Intent
import android.content.Intent.FLAG_RECEIVER_FOREGROUND

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.app.AlarmManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.pranav.reef.MainActivity
import dev.pranav.reef.R
import dev.pranav.reef.data.PhaseType
import dev.pranav.reef.data.SessionType
import dev.pranav.reef.timer.PomodoroConfig
import dev.pranav.reef.timer.PomodoroPhase
import dev.pranav.reef.timer.TimerSessionState
import dev.pranav.reef.timer.TimerStateManager
import dev.pranav.reef.util.*
import dev.pranav.reef.util.WhitelistAppCache
import dev.pranav.reef.util.WatchdogManager
import dev.pranav.reef.util.ResilienceManager
import dev.pranav.reef.util.SessionPersistence
import java.util.Locale
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class FocusModeService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val BREAK_ALERT_NOTIFICATION_ID = 2
        private const val COMPLETE_NOTIFICATION_ID = 3
        const val ACTION_TIMER_UPDATED = "dev.pranav.reef.TIMER_UPDATED"
        const val ACTION_START = "dev.pranav.reef.START_TIMER"
        const val ACTION_PAUSE = "dev.pranav.reef.PAUSE_TIMER"
        const val ACTION_RESUME = "dev.pranav.reef.RESUME_TIMER"
        const val ACTION_RESTART = "dev.pranav.reef.RESTART_TIMER"
        const val ACTION_RESUME_PERSISTED = "dev.pranav.reef.RESUME_PERSISTED"
        const val ACTION_SKIP_BREAK = "dev.pranav.reef.SKIP_BREAK"
        const val ACTION_STOP = "dev.pranav.reef.STOP_SESSION"
        const val EXTRA_TIME_LEFT = "extra_time_left"
        const val EXTRA_TIMER_STATE = "extra_timer_state"
        const val ACTION_PHASE_COMPLETE = "dev.pranav.reef.PHASE_COMPLETE"
    }

    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private var expectedCompletionElapsed: Long = 0L
    private val systemNotificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val remaining = (phaseEndEpoch - System.currentTimeMillis()).coerceAtLeast(0L)
            if (!TimerStateManager.state.value.isPaused) {
                TimerStateManager.updateState { copy(timeRemaining = remaining) }
                broadcastTimerUpdate(formatTime(remaining))
                val minute = remaining / 60_000L
                if (minute != lastNotifiedMinute) {
                    lastNotifiedMinute = minute
                    postNotification(
                        title = getNotificationTitle(),
                        isRunning = true,
                        showPauseButton = !TimerStateManager.state.value.isStrictMode && !TimerStateManager.isInBreak(),
                        timeLeft = remaining
                    )
                }
                // Persist roughly every 30 s (window-based — safe to fire more than once per window)
                if (remaining % 30_000L < 1_100L) {
                    SessionPersistence.saveRunning(this@FocusModeService, TimerStateManager.state.value, remaining, initialDuration, TimerStateManager.getPomodoroConfig())
                }
                // Tick focus stats exactly ONCE per 30-second boundary using edge-triggered
                // detection — prevRemaining/30000 > remaining/30000 fires precisely on crossing.
                // The old modulo-only approach fired 2× per window at 500 ms polling (Bug4 fix).
                val prev = lastCheckpointRemaining
                lastCheckpointRemaining = remaining
                if (prev / 30_000L > remaining / 30_000L) {
                    if (TimerStateManager.state.value.pomodoroPhase == PomodoroPhase.FOCUS || !TimerStateManager.state.value.isPomodoroMode) {
                        FocusStats.tickFocusMinute(30_000L)
                    }
                }
            }
            if (remaining > 0L) {
                tickHandler.postDelayed(this, 500L)
            } else {
                val nowElapsed = SystemClock.elapsedRealtime()
                if (nowElapsed >= expectedCompletionElapsed - 1000) {
                    cancelCompletionAlarm()
                    handleTimerComplete()
                } else {
                    val trueRemaining = expectedCompletionElapsed - nowElapsed
                    startCountdown(trueRemaining)
                }
            }
        }
    }
    private var notificationBuilder: NotificationCompat.Builder? = null
    private var previousInterruptionFilter: Int? = null
    private var initialDuration: Long = 0

    // Track the minute that was last reflected in the notification so we only
    // call notificationManager.notify() ~once per minute during ticking, not every second.
    private var lastNotifiedMinute = -1L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun checkForegroundAndApplyBlock() {
        if (!prefs.getBoolean("block_home_screen", false)) return
        if (!prefs.getBoolean("focus_mode", false)) return
        // Delegate to AppBlockerService which has getForegroundApp logic
        sendBroadcast(android.content.Intent("dev.pranav.reef.CHECK_FOREGROUND_NOW")
            .setPackage(packageName))
    }

    private val screenOnReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_SCREEN_ON) {
                val state = TimerStateManager.state.value
                if (state.isRunning) {
                    // Reanchor phaseEndEpoch from current remaining and rebuild notification
                    // so the chronometer never shows stale/negative values after screen-on
                    phaseEndEpoch = System.currentTimeMillis() + state.timeRemaining
                    postNotification(
                        title = getNotificationTitle(),
                        isRunning = true,
                        showPauseButton = !state.isStrictMode && !TimerStateManager.isInBreak(),
                        timeLeft = state.timeRemaining
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!isPrefsInitialized) {
            createDeviceProtectedStorageContext().also { ctx ->
                prefs = ctx.getSharedPreferences("prefs", MODE_PRIVATE)
            }
        }
        registerReceiver(screenOnReceiver, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON))
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────────

    private fun promoteToForeground() {
        val state = TimerStateManager.state.value
        val timeToDisplay = if (state.timeRemaining > 0) state.timeRemaining
        else prefs.getLong("focus_time", TimeUnit.MINUTES.toMillis(10))

        val notification = buildNotification(
            title = getNotificationTitle(),
            isRunning = state.isRunning,
            showPauseButton = !state.isStrictMode && state.isRunning,
            timeLeft = timeToDisplay
        )

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == null) return START_NOT_STICKY
        if (intent.action != ACTION_PAUSE && intent.action != ACTION_PHASE_COMPLETE) promoteToForeground()

        when (intent.action) {
            ACTION_PAUSE            -> pauseTimer()
            ACTION_RESUME           -> resumeTimer()
            ACTION_RESTART          -> restartCurrentPhase()
            ACTION_START            -> startTimer()
            ACTION_RESUME_PERSISTED -> resumePersistedSession()
            ACTION_SKIP_BREAK       -> skipBreak()
            ACTION_STOP             -> stopSession()
            ACTION_PHASE_COMPLETE   -> handlePhaseCompleteIntent()
        }
        return START_STICKY
    }

    private fun handlePhaseCompleteIntent() {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed < expectedCompletionElapsed - 2000) {
            val trueRemaining = expectedCompletionElapsed - nowElapsed
            startCountdown(trueRemaining)
        } else {
            handleTimerComplete()
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacksAndMessages(null)
        cancelCompletionAlarm()
        handler.removeCallbacksAndMessages(null)  // Flaw4: cancel transitionPomodoroPhase callbacks

        // ── Record partial stats on force-stop so no focused minutes are lost ──
        // endPhase computes actualDuration = now - phase.startTimestamp regardless of
        // whether it was planned. endSession then persists everything to disk.
        if (FocusStats.activeSession != null) {
            FocusStats.endSession(isCompleted = false)
        }
        runCatching { unregisterReceiver(screenOnReceiver) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        notificationManager.cancel(NOTIFICATION_ID)
        restoreDND()
        // Note: focus_mode written false ONLY in stopSession/endSession, not here,
        // to avoid creating a blocking gap during force-stop revival.
        // AppBlockerService will detect session end via SessionPersistence.
        dismissHomeBlockOverlay()
        TimerStateManager.reset()
        // NOTE: do NOT clear SessionPersistence here — force-stop calls onDestroy
        // too and we need the persisted data to restore the session on restart.
    }

    // ─── Persisted session recovery ─────────────────────────────────────────────

    private fun resumePersistedSession() {
        val restored = SessionPersistence.restore(this) ?: run { stopSelf(); return }

        initialDuration = restored.phaseDurationMs.coerceAtLeast(restored.remainingMs)
        lastNotifiedMinute = -1L

        restored.pomodoroConfig?.let { TimerStateManager.setPomodoroConfig(it) }
        TimerStateManager.updateState { restored.state }

        val isBreakPhase = restored.state.pomodoroPhase == PomodoroPhase.SHORT_BREAK ||
                restored.state.pomodoroPhase == PomodoroPhase.LONG_BREAK
        prefs.edit { putBoolean("focus_mode", !isBreakPhase || !restored.state.isPomodoroMode) }

        // Restart stats tracking so time is recorded from the resume point
        WhitelistAppCache.refresh(this)  // Bug8: warm cache for overlay grid
        FocusStats.clearCheckpoint()  // Bug20: clear pre-kill checkpoint before new tracking
        FocusStats.startSession(
            if (restored.state.isPomodoroMode) SessionType.POMODORO else SessionType.SIMPLE
        )
        val phaseType = when (restored.state.pomodoroPhase) {
            PomodoroPhase.SHORT_BREAK -> PhaseType.SHORT_BREAK
            PomodoroPhase.LONG_BREAK  -> PhaseType.LONG_BREAK
            else                      -> PhaseType.FOCUS
        }
        FocusStats.startPhase(phaseType, initialDuration)

        val title = getNotificationTitle()
        val canPause = !restored.state.isStrictMode && !isBreakPhase

        if (!restored.state.isPaused) {
            enableDNDIfNeeded()
            // Use correct title (break vs focus) from the start — Bug 8 fix
            postNotification(title, isRunning = true, showPauseButton = canPause, timeLeft = restored.remainingMs)
            startCountdown(restored.remainingMs)
        } else {
            postNotification(title, isRunning = false, showPauseButton = false, timeLeft = restored.remainingMs)
            broadcastTimerUpdate(formatTime(restored.remainingMs))
        }

        if (SessionPersistence.isNuclearRunning(this)) {
            Thread { WatchdogManager.start(this) }.start()
        }
        if (prefs.getBoolean("resilience_mode_enabled", false)) {
            ResilienceManager.start(this)
        }
        // Bug15: Restore home-block overlay that was dismissed by onDestroy during force-stop.
        // Without this, the overlay is absent until AppBlockerService's 1-second poll catches up.
        if (prefs.getBoolean("block_home_screen", false) && !isBreakPhase) {
            handler.postDelayed({ checkForegroundAndApplyBlock() }, 500L)
        }
    }

    /** Stored once when a phase starts so the chronometer anchor never drifts. */
    private var phaseEndEpoch: Long = 0L
    private var lastCheckpointRemaining: Long = Long.MAX_VALUE

    private fun skipBreak() {
        val state = TimerStateManager.state.value
        if (!state.isPomodoroMode) return
        if (state.pomodoroPhase != PomodoroPhase.SHORT_BREAK &&
            state.pomodoroPhase != PomodoroPhase.LONG_BREAK) return
        tickHandler.removeCallbacksAndMessages(null)
        cancelCompletionAlarm()
        FocusStats.endPhase(isCompleted = false)
        transitionPomodoroPhase()
    }

    private fun stopSession() {
        tickHandler.removeCallbacksAndMessages(null)
        cancelCompletionAlarm()
        handler.removeCallbacksAndMessages(null)
        SessionPersistence.clear(this)
        Thread { WatchdogManager.stop(this) }.start()
        dev.pranav.reef.watchdog.WatchdogService.stop(this)
        ResilienceManager.stop(this)  // Flaw1: cancel resilience alarm on user cancel, not just on natural completion
        // User-cancelled — record partial stats but no congratulations notification
        TimerStateManager.updateState { copy(isRunning = false, isPaused = false) }
        prefs.edit { putBoolean("focus_mode", false) }
        FocusStats.endSession(isCompleted = false)
        broadcastTimerUpdate("00:00")
        TimerStateManager.reset()
        restoreDND()
        dismissHomeBlockOverlay()
        stopSelf()
    }

    private fun startTimer() {
        val focusTimeMillis = prefs.getLong("focus_time", TimeUnit.MINUTES.toMillis(10))
        val isStrictMode = prefs.getBoolean("strict_mode", false)
        val isPomodoroMode = prefs.getBoolean("pomodoro_mode", false)

        initialDuration = focusTimeMillis
        lastNotifiedMinute = -1L

        if (isPomodoroMode) {
            val config = PomodoroConfig(
                focusDuration = prefs.getLong("pomodoro_focus_duration", 25 * 60 * 1000L),
                shortBreakDuration = prefs.getLong("pomodoro_short_break_duration", 5 * 60 * 1000L),
                longBreakDuration = prefs.getLong("pomodoro_long_break_duration", 15 * 60 * 1000L),
                cyclesBeforeLongBreak = prefs.getInt("pomodoro_cycles_before_long_break", 4)
            )
            TimerStateManager.setPomodoroConfig(config)
            val pomodoroFirstDuration = config.focusDuration
            initialDuration = pomodoroFirstDuration
            TimerStateManager.updateState {
                copy(
                    isRunning = true, isPaused = false,
                    timeRemaining = pomodoroFirstDuration,
                    pomodoroPhase = PomodoroPhase.FOCUS,
                    currentCycle = 1,   // Bug17: always reset to 1 for new session
                    totalCycles = config.cyclesBeforeLongBreak,
                    isPomodoroMode = true, isStrictMode = isStrictMode
                )
            }
        } else {
            TimerStateManager.updateState {
                copy(
                    isRunning = true, isPaused = false,
                    timeRemaining = focusTimeMillis,
                    isPomodoroMode = false, isStrictMode = isStrictMode
                )
            }
        }

        FocusStats.startSession(if (isPomodoroMode) SessionType.POMODORO else SessionType.SIMPLE)
        val actualStartDuration = if (isPomodoroMode) initialDuration else focusTimeMillis
        FocusStats.startPhase(PhaseType.FOCUS, actualStartDuration)
        prefs.edit { putBoolean("focus_mode", true) }
        enableDNDIfNeeded()

        // Persist immediately + save session start epoch for grace-period keying
        SessionPersistence.saveRunning(
            this, TimerStateManager.state.value,
            actualStartDuration, actualStartDuration,
            TimerStateManager.getPomodoroConfig()
        )
        prefs.edit { putLong("session_start_epoch", System.currentTimeMillis()) }

        // Pre-warm whitelist app icon cache for instant overlay display
        WhitelistAppCache.refresh(this)

        // Start nuclear watchdog if enabled
        if (prefs.getBoolean("nuclear_watchdog_enabled", false)) {
            Thread { WatchdogManager.start(this) }.start()
        }
        if (prefs.getBoolean("resilience_mode_enabled", false)) {
            ResilienceManager.start(this)
        }

        postNotification(
            title = getNotificationTitle(),
            isRunning = true,
            showPauseButton = !isStrictMode,
            timeLeft = actualStartDuration  // Flaw18 fix: was focusTimeMillis — wrong for Pomodoro first phase
        )
        startCountdown(actualStartDuration)
    }

    private fun pauseTimer() {
        val state = TimerStateManager.state.value
        if (state.isStrictMode) return
        // Don't pause during breaks — breaks are unpauseable
        if (state.isPomodoroMode && (state.pomodoroPhase == dev.pranav.reef.timer.PomodoroPhase.SHORT_BREAK || state.pomodoroPhase == dev.pranav.reef.timer.PomodoroPhase.LONG_BREAK)) return

        tickHandler.removeCallbacksAndMessages(null)
        cancelCompletionAlarm()
        
        // Calculate true remaining time to prevent sub-second pause drift
        val exactRemaining = (expectedCompletionElapsed - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        TimerStateManager.updateState { copy(isRunning = false, isPaused = true, timeRemaining = exactRemaining) }

        // ── Blocks stay active during pause ──────────────────────────────────
        // Do NOT set focus_mode=false, do NOT dismiss overlay, do NOT restore DND.
        // The user paused the timer but the focus session (and all blocking) is
        // still in effect.  Only resuming or ending the session lifts the blocks.

        SessionPersistence.savePaused(
            this, TimerStateManager.state.value,
            state.timeRemaining, initialDuration,
            TimerStateManager.getPomodoroConfig()
        )

        lastNotifiedMinute = -1L
        postNotification(
            title = getNotificationTitle(),
            isRunning = false,
            showPauseButton = false,
            timeLeft = state.timeRemaining
        )
        broadcastTimerUpdate(formatTime(state.timeRemaining))
    }

    private fun resumeTimer() {
        val state = TimerStateManager.state.value
        TimerStateManager.updateState { copy(isRunning = true, isPaused = false) }

        val isFocusPhase = state.isPomodoroMode && state.pomodoroPhase == PomodoroPhase.FOCUS
        prefs.edit { putBoolean("focus_mode", isFocusPhase || !state.isPomodoroMode) }
        if (isFocusPhase || !state.isPomodoroMode) enableDNDIfNeeded()

        lastNotifiedMinute = -1L
        postNotification(
            title = getNotificationTitle(),
            isRunning = true,
            showPauseButton = !state.isStrictMode,
            timeLeft = state.timeRemaining
        )
        startCountdown(state.timeRemaining)
    }

    private fun restartCurrentPhase() {
        tickHandler.removeCallbacksAndMessages(null)
        val currentPhaseType = when (TimerStateManager.state.value.pomodoroPhase) {
            PomodoroPhase.SHORT_BREAK -> PhaseType.SHORT_BREAK
            PomodoroPhase.LONG_BREAK  -> PhaseType.LONG_BREAK
            else                      -> PhaseType.FOCUS
        }
        FocusStats.endPhase(isCompleted = false)
        FocusStats.startPhase(currentPhaseType, initialDuration)
        TimerStateManager.updateState {
            copy(timeRemaining = initialDuration, isPaused = false, isRunning = true)
        }
        prefs.edit { putBoolean("focus_mode", true) }

        lastNotifiedMinute = -1L
        postNotification(
            title = getNotificationTitle(),
            isRunning = true,
            showPauseButton = !TimerStateManager.state.value.isStrictMode,
            timeLeft = initialDuration
        )
        broadcastTimerUpdate(formatTime(initialDuration))
        startCountdown(initialDuration)
    }

    // ─── Countdown ──────────────────────────────────────────────────────────────

    private fun startCountdown(timeMillis: Long) {
        tickHandler.removeCallbacksAndMessages(null)
        phaseEndEpoch = System.currentTimeMillis() + timeMillis
        expectedCompletionElapsed = SystemClock.elapsedRealtime() + timeMillis
        lastNotifiedMinute = -1L
        lastCheckpointRemaining = Long.MAX_VALUE
        SessionPersistence.saveRunning(this, TimerStateManager.state.value, timeMillis, initialDuration, TimerStateManager.getPomodoroConfig())
        scheduleCompletionAlarm(timeMillis)
        tickHandler.post(tickRunnable)
    }

    // ─── Notification ───────────────────────────────────────────────────────────

    /**
     * Builds and posts the notification. Uses [setUsesChronometer] + [setChronometerCountDown]
     * so the OS counts down live without the service posting every second.
     */
    private fun postNotification(
        title: String,
        isRunning: Boolean,
        showPauseButton: Boolean,
        timeLeft: Long
    ) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, isRunning, showPauseButton, timeLeft))
    }

    private fun buildNotification(
        title: String,
        isRunning: Boolean,
        showPauseButton: Boolean,
        timeLeft: Long
    ): Notification {
        val isStrictMode = TimerStateManager.state.value.isStrictMode

        if (notificationBuilder == null) {
            val tapIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("navigate_to_timer", true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val tapPending = PendingIntent.getActivity(
                this, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            notificationBuilder = NotificationCompat.Builder(this, FOCUS_MODE_CHANNEL_ID)
                .setContentIntent(tapPending)
                .setSmallIcon(R.drawable.hourglass)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setRequestPromotedOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        notificationBuilder!!.apply {
            val chipMin = TimeUnit.MILLISECONDS.toMinutes(timeLeft)
            val minutesText = if (chipMin > 0) "${chipMin}m" else "<1m"
            setContentTitle(minutesText)

            if (isRunning) {
                setUsesChronometer(true)
                setChronometerCountDown(true)
                // Use the pre-anchored epoch so the chip never drifts across rebuilds
                setWhen(if (phaseEndEpoch > 0) phaseEndEpoch else System.currentTimeMillis() + timeLeft)

                val contentText = getString(R.string.time_remaining, minutesText)
                setContentText(contentText)
                setStyle(NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(contentText))
            } else {
                setUsesChronometer(false)
                setWhen(System.currentTimeMillis())
                val contentText = getString(R.string.paused_time, minutesText)
                setContentText(contentText)
                setStyle(NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(contentText))
            }

            setSubText(title)
            clearActions()

            val state = TimerStateManager.state.value
            val isBreak = state.pomodoroPhase == PomodoroPhase.SHORT_BREAK ||
                    state.pomodoroPhase == PomodoroPhase.LONG_BREAK

            val canShowPauseOrResume = !isStrictMode && !isBreak
            if (canShowPauseOrResume) {
                val (action, label) = if (showPauseButton)
                    ACTION_PAUSE to getString(R.string.notification_pause)
                else
                    ACTION_RESUME to getString(R.string.notification_resume)
                val actionPending = PendingIntent.getService(
                    this@FocusModeService, if (showPauseButton) 1 else 2,
                    Intent(this@FocusModeService, FocusModeService::class.java).apply { this.action = action },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                addAction(NotificationCompat.Action.Builder(0, label, actionPending).build())
            }

            // Skip action — available during all break phases
            if (isBreak && state.isPomodoroMode) {
                val skipPending = PendingIntent.getService(
                    this@FocusModeService, 5,
                    Intent(this@FocusModeService, FocusModeService::class.java).apply { this.action = ACTION_SKIP_BREAK },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                addAction(NotificationCompat.Action.Builder(0, getString(R.string.skip_break), skipPending).build())
            }
        }
        return notificationBuilder!!.build()
    }

    // ─── Broadcast ──────────────────────────────────────────────────────────────

    private fun broadcastTimerUpdate(formattedTime: String) {
        val state = TimerStateManager.state.value
        sendBroadcast(Intent(ACTION_TIMER_UPDATED).apply {
            setPackage(packageName)
            putExtra(EXTRA_TIME_LEFT, formattedTime)
            putExtra(EXTRA_TIMER_STATE, state.pomodoroPhase.name)
            addFlags(FLAG_RECEIVER_FOREGROUND)
        })
    }

    // ─── Timer completion ───────────────────────────────────────────────────────

    private fun handleTimerComplete() {
        if (!TimerStateManager.state.value.isPomodoroMode) endSession()
        else transitionPomodoroPhase()
    }

    private fun endSession() {
        TimerStateManager.updateState { copy(isRunning = false, isPaused = false) }
        prefs.edit { putBoolean("focus_mode", false) }
        FocusStats.endSession(isCompleted = true)
        broadcastTimerUpdate("00:00")
        TimerStateManager.reset()
        restoreDND()
        SessionPersistence.clear(this)
        Thread { WatchdogManager.stop(this) }.start()
        dev.pranav.reef.watchdog.WatchdogService.stop(this)
        ResilienceManager.stop(this)
        showFocusCompleteNotification()
        stopSelf()
    }

    private fun transitionPomodoroPhase() {
        val state = TimerStateManager.state.value
        val config = TimerStateManager.getPomodoroConfig() ?: return endSession()
        val nextPhase = calculateNextPhase(state, config)

        FocusStats.endPhase(isCompleted = true)

        if (nextPhase.isComplete) {
            prefs.edit { putBoolean("pomodoro_mode", false); remove("pomodoro_current_cycle") }
            endSession()
            return
        }

        val nextPhaseType = when (nextPhase.phase) {
            PomodoroPhase.SHORT_BREAK -> PhaseType.SHORT_BREAK
            PomodoroPhase.LONG_BREAK  -> PhaseType.LONG_BREAK
            else                      -> PhaseType.FOCUS
        }
        // Breaks and the next focus cycle always start automatically.
        // The only thing that can be paused is an active focus phase (if not in strict mode).
        // "auto_start_breaks" and "auto_start_pomodoro" toggles have been removed.

        val goingToBreak = nextPhase.phase == PomodoroPhase.SHORT_BREAK ||
                nextPhase.phase == PomodoroPhase.LONG_BREAK
        val goingToFocus = nextPhase.phase == PomodoroPhase.FOCUS

        TimerStateManager.updateState {
            copy(
                pomodoroPhase = nextPhase.phase, currentCycle = nextPhase.currentCycle,
                timeRemaining = nextPhase.duration,
                isRunning = true, isPaused = false
            )
        }
        prefs.edit().apply {
            putInt("pomodoro_current_cycle", nextPhase.currentCycle)
            // focus_mode=true only during focus phases (not breaks)
            putBoolean("focus_mode", goingToFocus)
            commit() // synchronous — ensures focus_mode is readable before broadcast fires
        }

        if (goingToBreak && prefs.getBoolean("block_home_screen", false)) {
            dismissHomeBlockOverlay()
        }

        if (goingToFocus && prefs.getBoolean("block_home_screen", false)) {
            // Fire immediately (prefs already committed) and again after 1s as safety net
            handler.post { checkForegroundAndApplyBlock() }
            handler.postDelayed({ checkForegroundAndApplyBlock() }, 1_000L)
        }

        initialDuration = nextPhase.duration
        lastNotifiedMinute = -1L
        phaseEndEpoch = 0L  // will be set in startCountdown for the new phase
        FocusStats.startPhase(nextPhaseType, nextPhase.duration)

        if (goingToFocus) {
            enableDNDIfNeeded()
            if (prefs.getBoolean("break_alerts", true)) showBreakEndedNotification()
        } else {
            restoreDND()
        }

        if (prefs.getBoolean("pomodoro_sound_enabled", true)) playTransitionSound()
        if (prefs.getBoolean("pomodoro_vibration_enabled", true)) AndroidUtilities.vibrate(this, 1000)

        postNotification(
            title = getNotificationTitle(),
            isRunning = true,
            showPauseButton = goingToFocus && !state.isStrictMode,
            timeLeft = nextPhase.duration
        )
        broadcastTimerUpdate(formatTime(nextPhase.duration))
        startCountdown(nextPhase.duration)
    }

    // ─── Phase calculation ───────────────────────────────────────────────────────

    private data class NextPhaseResult(
        val phase: PomodoroPhase, val duration: Long,
        val currentCycle: Int, val isComplete: Boolean
    )

    private fun calculateNextPhase(state: TimerSessionState, config: PomodoroConfig): NextPhaseResult =
        when (state.pomodoroPhase) {
            PomodoroPhase.FOCUS -> {
                if (state.currentCycle >= state.totalCycles)
                    NextPhaseResult(PomodoroPhase.LONG_BREAK, config.longBreakDuration, 0, false)
                else
                    NextPhaseResult(PomodoroPhase.SHORT_BREAK, config.shortBreakDuration, state.currentCycle + 1, false)
            }
            PomodoroPhase.LONG_BREAK ->
                NextPhaseResult(PomodoroPhase.COMPLETE, 0, 0, true)
            else ->
                NextPhaseResult(PomodoroPhase.FOCUS, config.focusDuration, state.currentCycle, false)
        }

    // ─── Notification titles ─────────────────────────────────────────────────────

    private fun getNotificationTitle(): String {
        val state = TimerStateManager.state.value
        if (!state.isPomodoroMode) return getString(R.string.focus_mode)
        return when (state.pomodoroPhase) {
            PomodoroPhase.SHORT_BREAK -> getString(R.string.short_break_label)
            PomodoroPhase.LONG_BREAK  -> getString(R.string.long_break_label)
            else                      -> getString(R.string.focus_mode)
        }
    }

    // ─── DND ─────────────────────────────────────────────────────────────────────

    private fun enableDNDIfNeeded() {
        if (!prefs.getBoolean("enable_dnd", false)) return
        if (systemNotificationManager.isNotificationPolicyAccessGranted) {
            previousInterruptionFilter = systemNotificationManager.currentInterruptionFilter
            systemNotificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
    }

    private fun restoreDND() {
        if (previousInterruptionFilter != null &&
            systemNotificationManager.isNotificationPolicyAccessGranted) {
            systemNotificationManager.setInterruptionFilter(
                previousInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
            )
            previousInterruptionFilter = null
        }
    }

    // ─── Secondary notifications ─────────────────────────────────────────────────

    private fun showBreakEndedNotification() {
        notificationManager.notify(
            BREAK_ALERT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(R.string.break_ended_title))
                .setContentText(getString(R.string.break_ended_message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun showFocusCompleteNotification() {
        val soundUri = runCatching {
            val s = prefs.getString("pomodoro_sound", null)
            if (s.isNullOrEmpty()) android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            else s.toUri()
        }.getOrElse { android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION) }

        val tapPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val continuePending = PendingIntent.getService(
            this, 4,
            Intent(this, FocusModeService::class.java).apply { action = ACTION_START },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        notificationManager.notify(
            COMPLETE_NOTIFICATION_ID,
            NotificationCompat.Builder(this, FOCUS_MODE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(R.string.focus_session_complete))
                .setContentText(getString(R.string.focus_session_complete_message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(soundUri)
                .setAutoCancel(true)
                .setContentIntent(tapPending)
                .addAction(NotificationCompat.Action.Builder(0, getString(R.string.notification_continue), continuePending).build())
                .build()
        )
    }

    // ─── Home-block overlay dismiss ──────────────────────────────────────────────

    private fun dismissHomeBlockOverlay() {
        if (prefs.getBoolean("block_home_screen", false)) {
            HomeBlockOverlayService.stop(this)
        }
    }

    // ─── Sound ───────────────────────────────────────────────────────────────────

    private fun playTransitionSound() {
        try {
            val uri = prefs.getString("pomodoro_sound", null).let { s ->
                if (s.isNullOrEmpty()) android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                else s.toUri()
            }
            android.media.RingtoneManager.getRingtone(applicationContext, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun scheduleCompletionAlarm(timeMillis: Long) {
        val alarmManager = getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, FocusModeService::class.java).apply { action = ACTION_PHASE_COMPLETE }
        val pendingIntent = PendingIntent.getService(
            this, 99, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerTimeMs = System.currentTimeMillis() + timeMillis
        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTimeMs, null)
        try {
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (e: SecurityException) {
            // Fallback: If Android 14+ denies exact alarm permission,
            // swallow the crash. The tickHandler loop acts as the fallback.
            e.printStackTrace()
        }
    }

    private fun cancelCompletionAlarm() {
        val alarmManager = getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, FocusModeService::class.java).apply { action = ACTION_PHASE_COMPLETE }
        val pendingIntent = PendingIntent.getService(
            this, 99, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}

fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    return String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
