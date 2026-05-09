package dev.pranav.reef.accessibility

import android.content.Intent
import android.content.Intent.FLAG_RECEIVER_FOREGROUND
import android.widget.RemoteViews

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
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
        const val EXTRA_TIME_LEFT = "extra_time_left"
        const val EXTRA_TIMER_STATE = "extra_timer_state"
    }

    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private val systemNotificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var countDownTimer: CountDownTimer? = null
    private var notificationBuilder: NotificationCompat.Builder? = null
    private var previousInterruptionFilter: Int? = null
    private var initialDuration: Long = 0

    // Track the minute that was last reflected in the notification so we only
    // call notificationManager.notify() ~once per minute during ticking, not every second.
    private var lastNotifiedMinute = -1L

    override fun onCreate() {
        super.onCreate()
        if (!isPrefsInitialized) {
            createDeviceProtectedStorageContext().also { ctx ->
                prefs = ctx.getSharedPreferences("prefs", MODE_PRIVATE)
            }
        }
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
        if (intent.action != ACTION_PAUSE) promoteToForeground()

        when (intent.action) {
            ACTION_PAUSE   -> pauseTimer()
            ACTION_RESUME  -> resumeTimer()
            ACTION_RESTART -> restartCurrentPhase()
            ACTION_START   -> startTimer()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        // Ensure the foreground notification is removed on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        notificationManager.cancel(NOTIFICATION_ID)
        restoreDND()
        if (FocusStats.activeSession != null) FocusStats.endSession(isCompleted = false)
        prefs.edit { putBoolean("focus_mode", false) }
        TimerStateManager.reset()
    }

    // ─── Timer actions ──────────────────────────────────────────────────────────

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
            TimerStateManager.updateState {
                copy(
                    isRunning = true, isPaused = false,
                    timeRemaining = focusTimeMillis,
                    pomodoroPhase = PomodoroPhase.FOCUS,
                    currentCycle = prefs.getInt("pomodoro_current_cycle", 1),
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
        FocusStats.startPhase(PhaseType.FOCUS, focusTimeMillis)
        prefs.edit { putBoolean("focus_mode", true) }
        enableDNDIfNeeded()

        postNotification(
            title = getNotificationTitle(),
            isRunning = true,
            showPauseButton = !isStrictMode,
            timeLeft = focusTimeMillis
        )
        startCountdown(focusTimeMillis)
    }

    private fun pauseTimer() {
        val state = TimerStateManager.state.value
        if (state.isStrictMode) return
        countDownTimer?.cancel()
        TimerStateManager.updateState { copy(isRunning = false, isPaused = true) }
        prefs.edit { putBoolean("focus_mode", false) }
        restoreDND()

        // Paused: disable chronometer and show static remaining time
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
        countDownTimer?.cancel()
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
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(timeMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (TimerStateManager.state.value.isPaused) return
                TimerStateManager.updateState { copy(timeRemaining = millisUntilFinished) }

                // ── Broadcast every second so the in-app timer UI stays smooth ──
                broadcastTimerUpdate(formatTime(millisUntilFinished))

                // ── Update notification only when the displayed minute changes ──
                // The chronometer handles second-level counting; we only need to
                // post a new notification to update the chip text and action buttons.
                val minute = millisUntilFinished / 60_000L
                if (minute != lastNotifiedMinute) {
                    lastNotifiedMinute = minute
                    postNotification(
                        title = getNotificationTitle(),
                        isRunning = true,
                        showPauseButton = !TimerStateManager.state.value.isStrictMode,
                        timeLeft = millisUntilFinished
                    )
                }
            }

            override fun onFinish() = handleTimerComplete()
        }.start()
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

        // ── Chronometer: let the OS count down live in the notification ──────
        // When running: anchor setWhen to the future finish time so the system
        //   displays a live countdown automatically.
        // When paused:  disable chronometer and show the static remaining time.
        notificationBuilder!!.apply {
            setContentTitle(title)

            if (isRunning) {
                val customView = RemoteViews(packageName, R.layout.notification_live_timer)
                val elapsedFinishTime = SystemClock.elapsedRealtime() + timeLeft
                
                // Bind the system clock and set the timer logic directly into the RemoteViews layout
                customView.setChronometer(R.id.live_chronometer, elapsedFinishTime, null, true)
                customView.setBoolean(R.id.live_chronometer, "setCountDown", true)
                
                setCustomContentView(customView)
                setCustomBigContentView(customView)
                
                // Explicitly disable the OS-level chronometer header injections 
                setUsesChronometer(false)
                setStyle(null)
                
                // Safe fallback for edge cases
                setContentText(getString(R.string.time_remaining, "${TimeUnit.MILLISECONDS.toMinutes(timeLeft)} m"))
            } else {
                // Clear custom layouts when paused so it reverts back to default text display
                setCustomContentView(null)
                setCustomBigContentView(null)
                setUsesChronometer(false)
                setStyle(null)
                
                setWhen(System.currentTimeMillis())
                setContentText(getString(R.string.paused_time, "${TimeUnit.MILLISECONDS.toMinutes(timeLeft)} m"))
            }

            // Dynamic-island / promoted chip: show remaining minutes
            val chipMin = TimeUnit.MILLISECONDS.toMinutes(timeLeft)
            setShortCriticalText(if (chipMin > 0) "${chipMin}m" else "<1m")

            clearActions()

            val state = TimerStateManager.state.value
            val isBreak = state.pomodoroPhase == PomodoroPhase.SHORT_BREAK ||
                    state.pomodoroPhase == PomodoroPhase.LONG_BREAK

            if (!isStrictMode || (!showPauseButton && isBreak && state.isPaused)) {
                val (action, label) = if (showPauseButton)
                    ACTION_PAUSE to getString(R.string.notification_pause)
                else
                    ACTION_RESUME to getString(R.string.notification_resume)

                val actionPending = PendingIntent.getService(
                    this@FocusModeService,
                    if (showPauseButton) 1 else 2,
                    Intent(this@FocusModeService, FocusModeService::class.java).apply { this.action = action },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                addAction(NotificationCompat.Action.Builder(0, label, actionPending).build())
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
        val shouldAutoStart = when (nextPhase.phase) {
            PomodoroPhase.FOCUS -> prefs.getBoolean("auto_start_pomodoro", true)
            else                -> prefs.getBoolean("auto_start_breaks", false)
        }

        TimerStateManager.updateState {
            copy(
                pomodoroPhase = nextPhase.phase, currentCycle = nextPhase.currentCycle,
                timeRemaining = nextPhase.duration,
                isRunning = shouldAutoStart, isPaused = !shouldAutoStart
            )
        }
        prefs.edit {
            putInt("pomodoro_current_cycle", nextPhase.currentCycle)
            putBoolean("focus_mode", shouldAutoStart && nextPhase.phase == PomodoroPhase.FOCUS)
        }

        initialDuration = nextPhase.duration
        lastNotifiedMinute = -1L
        FocusStats.startPhase(nextPhaseType, nextPhase.duration)

        if (nextPhase.phase == PomodoroPhase.FOCUS) {
            if (shouldAutoStart) enableDNDIfNeeded()
            if (prefs.getBoolean("break_alerts", true)) showBreakEndedNotification()
        } else {
            restoreDND()
        }

        if (prefs.getBoolean("pomodoro_sound_enabled", true)) playTransitionSound()
        if (prefs.getBoolean("pomodoro_vibration_enabled", true)) AndroidUtilities.vibrate(this, 1000)

        postNotification(
            title = getNotificationTitle(),
            isRunning = shouldAutoStart,
            showPauseButton = shouldAutoStart && !state.isStrictMode,
            timeLeft = nextPhase.duration
        )
        broadcastTimerUpdate(formatTime(nextPhase.duration))
        if (shouldAutoStart) startCountdown(nextPhase.duration)
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
}

fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    return String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
