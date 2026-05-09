package dev.pranav.reef.accessibility

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Intent.FLAG_RECEIVER_FOREGROUND
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
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
import dev.pranav.reef.util.NotificationHelper.createNotificationChannel
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

    // Single CountDownTimer used ONLY for detecting phase completion — no per-tick work.
    private var countDownTimer: CountDownTimer? = null
    private var notificationBuilder: NotificationCompat.Builder? = null
    private var previousInterruptionFilter: Int? = null
    private var initialDuration: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (!isPrefsInitialized) {
            createDeviceProtectedStorageContext().also { safeContext ->
                prefs = safeContext.getSharedPreferences("prefs", MODE_PRIVATE)
            }
        }
    }

    private fun promoteToForeground() {
        val state = TimerStateManager.state.value
        val timeToDisplay = if (state.timeRemaining > 0) state.timeRemaining
        else prefs.getLong("focus_time", TimeUnit.MINUTES.toMillis(10))

        val notification = buildNotification(
            title = getNotificationTitle(),
            text = getString(R.string.time_remaining, formatTime(timeToDisplay)),
            showPauseButton = !state.isStrictMode && state.isRunning,
            endTimeMillis = if (state.isRunning && state.endTimeMillis > 0) state.endTimeMillis
                            else System.currentTimeMillis() + timeToDisplay
        )

        val foregroundType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0

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

    // ─── Timer lifecycle ────────────────────────────────────────────────────────

    private fun startTimer() {
        val focusTimeMillis = prefs.getLong("focus_time", TimeUnit.MINUTES.toMillis(10))
        val isStrictMode = prefs.getBoolean("strict_mode", false)
        val isPomodoroMode = prefs.getBoolean("pomodoro_mode", false)

        initialDuration = focusTimeMillis

        if (isPomodoroMode) {
            val config = PomodoroConfig(
                focusDuration      = prefs.getLong("pomodoro_focus_duration",       25 * 60 * 1000L),
                shortBreakDuration = prefs.getLong("pomodoro_short_break_duration",  5 * 60 * 1000L),
                longBreakDuration  = prefs.getLong("pomodoro_long_break_duration",  15 * 60 * 1000L),
                cyclesBeforeLongBreak = prefs.getInt("pomodoro_cycles_before_long_break", 4)
            )
            TimerStateManager.setPomodoroConfig(config)
            val currentCycle = prefs.getInt("pomodoro_current_cycle", 1)
            val endTime = System.currentTimeMillis() + focusTimeMillis
            TimerStateManager.updateState {
                copy(
                    isRunning = true, isPaused = false,
                    timeRemaining = focusTimeMillis, endTimeMillis = endTime,
                    pomodoroPhase = PomodoroPhase.FOCUS, currentCycle = currentCycle,
                    totalCycles = config.cyclesBeforeLongBreak,
                    isPomodoroMode = true, isStrictMode = isStrictMode
                )
            }
        } else {
            val endTime = System.currentTimeMillis() + focusTimeMillis
            TimerStateManager.updateState {
                copy(
                    isRunning = true, isPaused = false,
                    timeRemaining = focusTimeMillis, endTimeMillis = endTime,
                    isPomodoroMode = false, isStrictMode = isStrictMode
                )
            }
        }

        FocusStats.startSession(if (isPomodoroMode) SessionType.POMODORO else SessionType.SIMPLE)
        FocusStats.startPhase(PhaseType.FOCUS, focusTimeMillis)

        prefs.edit { putBoolean("focus_mode", true) }
        enableDNDIfNeeded()

        val endTime = TimerStateManager.state.value.endTimeMillis
        postStateChangeNotification(showPauseButton = !isStrictMode, endTimeMillis = endTime)
        broadcastStateChange(formatTime(focusTimeMillis))
        scheduleCompletionTimer(focusTimeMillis)
    }

    private fun pauseTimer() {
        val state = TimerStateManager.state.value
        if (state.isStrictMode) return

        countDownTimer?.cancel()

        // Compute exact remaining time from the stored end-timestamp rather than
        // relying on the last ticked value — avoids drift.
        val remaining = if (state.endTimeMillis > 0)
            (state.endTimeMillis - System.currentTimeMillis()).coerceAtLeast(0)
        else state.timeRemaining

        TimerStateManager.updateState {
            copy(isRunning = false, isPaused = true, timeRemaining = remaining, endTimeMillis = 0)
        }

        prefs.edit { putBoolean("focus_mode", false) }
        restoreDND()

        postStateChangeNotification(showPauseButton = false, endTimeMillis = 0, staticText = formatTime(remaining))
        broadcastStateChange(formatTime(remaining))
    }

    private fun resumeTimer() {
        val state = TimerStateManager.state.value
        val endTime = System.currentTimeMillis() + state.timeRemaining

        TimerStateManager.updateState {
            copy(isRunning = true, isPaused = false, endTimeMillis = endTime)
        }

        val isFocusPhase = state.isPomodoroMode && state.pomodoroPhase == PomodoroPhase.FOCUS
        prefs.edit { putBoolean("focus_mode", isFocusPhase || !state.isPomodoroMode) }
        if (isFocusPhase || !state.isPomodoroMode) enableDNDIfNeeded()

        postStateChangeNotification(showPauseButton = !state.isStrictMode, endTimeMillis = endTime)
        broadcastStateChange(formatTime(state.timeRemaining))
        scheduleCompletionTimer(state.timeRemaining)
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

        val endTime = System.currentTimeMillis() + initialDuration
        TimerStateManager.updateState {
            copy(timeRemaining = initialDuration, endTimeMillis = endTime, isPaused = false, isRunning = true)
        }

        prefs.edit { putBoolean("focus_mode", true) }

        postStateChangeNotification(
            showPauseButton = !TimerStateManager.state.value.isStrictMode,
            endTimeMillis = endTime
        )
        broadcastStateChange(formatTime(initialDuration))
        scheduleCompletionTimer(initialDuration)
    }

    // ─── Completion / phase transition ──────────────────────────────────────────

    private fun handleTimerComplete() {
        val state = TimerStateManager.state.value
        if (!state.isPomodoroMode) endSession() else transitionPomodoroPhase()
    }

    private fun endSession() {
        TimerStateManager.updateState { copy(isRunning = false, isPaused = false, endTimeMillis = 0) }
        prefs.edit { putBoolean("focus_mode", false) }
        FocusStats.endSession(isCompleted = true)
        broadcastStateChange("00:00")
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
            PomodoroPhase.FOCUS ->
                prefs.getBoolean("auto_start_pomodoro", true)
            PomodoroPhase.SHORT_BREAK, PomodoroPhase.LONG_BREAK ->
                prefs.getBoolean("auto_start_breaks", false)
            else -> false
        }

        val endTime = if (shouldAutoStart) System.currentTimeMillis() + nextPhase.duration else 0L

        TimerStateManager.updateState {
            copy(
                pomodoroPhase = nextPhase.phase,
                currentCycle  = nextPhase.currentCycle,
                timeRemaining = nextPhase.duration,
                endTimeMillis = endTime,
                isRunning     = shouldAutoStart,
                isPaused      = !shouldAutoStart
            )
        }

        prefs.edit {
            putInt("pomodoro_current_cycle", nextPhase.currentCycle)
            putBoolean("focus_mode", shouldAutoStart && nextPhase.phase == PomodoroPhase.FOCUS)
        }

        initialDuration = nextPhase.duration
        FocusStats.startPhase(nextPhaseType, nextPhase.duration)

        if (nextPhase.phase == PomodoroPhase.FOCUS) {
            if (shouldAutoStart) enableDNDIfNeeded()
            if (prefs.getBoolean("break_alerts", true)) showBreakEndedNotification()
        } else {
            restoreDND()
        }

        if (prefs.getBoolean("pomodoro_sound_enabled", true)) playTransitionSound()
        if (prefs.getBoolean("pomodoro_vibration_enabled", true)) AndroidUtilities.vibrate(this, 1000)

        val notifText = if (shouldAutoStart)
            getString(R.string.time_remaining, formatTime(nextPhase.duration))
        else getString(R.string.tap_to_start_next_phase)

        // Force rebuild so title/actions reflect new phase
        notificationBuilder = null
        postStateChangeNotification(
            showPauseButton = shouldAutoStart && !state.isStrictMode,
            endTimeMillis   = endTime,
            staticText      = if (!shouldAutoStart) notifText else null
        )
        broadcastStateChange(formatTime(nextPhase.duration))

        if (shouldAutoStart) scheduleCompletionTimer(nextPhase.duration)
    }

    // ─── Notification helpers ────────────────────────────────────────────────────

    /**
     * Builds/updates the notification for a *state change* event (start, pause, resume,
     * phase change). This is the ONLY place we call notificationManager.notify().
     *
     * @param endTimeMillis  When non-zero and the timer is running, the notification shows a
     *                       live countdown using [Notification.EXTRA_CHRONOMETER_COUNT_DOWN].
     *                       When zero (paused/stopped), [staticText] is shown instead.
     */
    private fun postStateChangeNotification(
        showPauseButton: Boolean,
        endTimeMillis: Long,
        staticText: String? = null
    ) {
        val notification = buildNotification(
            title          = getNotificationTitle(),
            text           = staticText ?: getString(R.string.time_remaining, formatTime(
                if (endTimeMillis > 0) (endTimeMillis - System.currentTimeMillis()).coerceAtLeast(0)
                else TimerStateManager.state.value.timeRemaining
            )),
            showPauseButton = showPauseButton,
            endTimeMillis  = endTimeMillis
        )
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        title: String,
        text: String,
        showPauseButton: Boolean,
        endTimeMillis: Long
    ): Notification {
        val isStrictMode = TimerStateManager.state.value.isStrictMode

        if (notificationBuilder == null) {
            val tapIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("navigate_to_timer", true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val tapPendingIntent = PendingIntent.getActivity(
                this, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            notificationBuilder = NotificationCompat.Builder(this, FOCUS_MODE_CHANNEL_ID)
                .setContentIntent(tapPendingIntent)
                .setSmallIcon(R.drawable.hourglass)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
                .setOnlyAlertOnce(true)
                .setRequestPromotedOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return notificationBuilder!!.apply {
            val collapsedText = if (endTimeMillis > 0) text else title
            val expandedText = if (endTimeMillis > 0) title else text
            setContentTitle(collapsedText)
            setContentText(expandedText)
            setSubText(expandedText)

            // Live countdown: delegate to the system Chronometer widget.
            // setWhen(endTimeMillis) + setUsesChronometer(true) + count-down flag
            // lets Android render "MM:SS" natively — no app-side ticking needed.
            if (endTimeMillis > 0) {
                // Use wall-clock time directly for notification chronometer
                setWhen(endTimeMillis)
                setUsesChronometer(true)
                setChronometerCountDown(true)
                setShowWhen(true)
            } else {
                setUsesChronometer(false)
                setShowWhen(false)
            }

            clearActions()

            val state = TimerStateManager.state.value
            val isBreak = state.pomodoroPhase == PomodoroPhase.SHORT_BREAK ||
                          state.pomodoroPhase == PomodoroPhase.LONG_BREAK

            if (!isStrictMode || (!showPauseButton && isBreak && state.isPaused)) {
                val (action, label) = if (showPauseButton)
                    ACTION_PAUSE  to getString(R.string.notification_pause)
                else
                    ACTION_RESUME to getString(R.string.notification_resume)

                val actionIntent = Intent(this@FocusModeService, FocusModeService::class.java)
                    .apply { this.action = action }

                val actionPendingIntent = PendingIntent.getService(
                    this@FocusModeService,
                    if (showPauseButton) 1 else 2,
                    actionIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                addAction(NotificationCompat.Action.Builder(0, label, actionPendingIntent).build())
            }
        }.build()
    }

    /** Broadcast a state-change event to the UI. NOT called per-second. */
    private fun broadcastStateChange(formattedTime: String) {
        val state = TimerStateManager.state.value
        val intent = Intent(ACTION_TIMER_UPDATED).apply {
            setPackage(packageName)
            putExtra(EXTRA_TIME_LEFT, formattedTime)
            putExtra(EXTRA_TIMER_STATE, state.pomodoroPhase.name)
            addFlags(FLAG_RECEIVER_FOREGROUND)
        }
        sendBroadcast(intent)
    }

    // ─── Scheduling ──────────────────────────────────────────────────────────────

    /**
     * Schedules a [CountDownTimer] whose ONLY purpose is to fire [handleTimerComplete]
     * when the phase duration expires. [onTick] is intentionally empty — the UI derives
     * the live countdown from [TimerSessionState.endTimeMillis] locally.
     */
    private fun scheduleCompletionTimer(timeMillis: Long) {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(timeMillis, timeMillis /* one tick at end */) {
            override fun onTick(millisUntilFinished: Long) { /* intentionally empty */ }
            override fun onFinish() = handleTimerComplete()
        }.start()
    }

    // ─── Pomodoro phase logic ────────────────────────────────────────────────────

    private data class NextPhaseResult(
        val phase: PomodoroPhase,
        val duration: Long,
        val currentCycle: Int,
        val isComplete: Boolean
    )

    private fun calculateNextPhase(state: TimerSessionState, config: PomodoroConfig): NextPhaseResult {
        return when (state.pomodoroPhase) {
            PomodoroPhase.FOCUS -> {
                if (state.currentCycle >= state.totalCycles) {
                    NextPhaseResult(PomodoroPhase.LONG_BREAK,  config.longBreakDuration,  0,                   false)
                } else {
                    NextPhaseResult(PomodoroPhase.SHORT_BREAK, config.shortBreakDuration, state.currentCycle + 1, false)
                }
            }
            PomodoroPhase.LONG_BREAK ->
                NextPhaseResult(PomodoroPhase.COMPLETE, 0, 0, true)
            else ->
                NextPhaseResult(PomodoroPhase.FOCUS, config.focusDuration, state.currentCycle, false)
        }
    }

    // ─── DND / sound / vibration ─────────────────────────────────────────────────

    private fun enableDNDIfNeeded() {
        if (!prefs.getBoolean("enable_dnd", false)) return
        if (systemNotificationManager.isNotificationPolicyAccessGranted) {
            previousInterruptionFilter = systemNotificationManager.currentInterruptionFilter
            systemNotificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
    }

    private fun restoreDND() {
        if (previousInterruptionFilter != null && systemNotificationManager.isNotificationPolicyAccessGranted) {
            systemNotificationManager.setInterruptionFilter(
                previousInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
            )
            previousInterruptionFilter = null
        }
    }

    private fun playTransitionSound() {
        try {
            val soundUriString = prefs.getString("pomodoro_sound", null)
            val soundUri = if (soundUriString.isNullOrEmpty())
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            else soundUriString.toUri()

            val ringtone = android.media.RingtoneManager.getRingtone(applicationContext, soundUri)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ─── Alert notifications ─────────────────────────────────────────────────────

    private fun getNotificationTitle(): String {
        val state = TimerStateManager.state.value
        if (!state.isPomodoroMode) return getString(R.string.focus_mode)
        return when (state.pomodoroPhase) {
            PomodoroPhase.SHORT_BREAK -> getString(R.string.short_break_label)
            PomodoroPhase.LONG_BREAK  -> getString(R.string.long_break_label)
            else                      -> getString(R.string.focus_mode)
        }
    }

    private fun showBreakEndedNotification() {
        val notification = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.break_ended_title))
            .setContentText(getString(R.string.break_ended_message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(BREAK_ALERT_NOTIFICATION_ID, notification)
    }

    private fun showFocusCompleteNotification() {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val continueIntent = Intent(this, FocusModeService::class.java).apply { action = ACTION_START }
        val continuePendingIntent = PendingIntent.getService(
            this, 4, continueIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val soundUri = try {
            val s = prefs.getString("pomodoro_sound", null)
            if (s.isNullOrEmpty()) android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            else s.toUri()
        } catch (_: Exception) {
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        }

        val notification = NotificationCompat.Builder(this, FOCUS_MODE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.focus_session_complete))
            .setContentText(getString(R.string.focus_session_complete_message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .addAction(NotificationCompat.Action.Builder(
                0, getString(R.string.notification_continue), continuePendingIntent
            ).build())
            .build()

        notificationManager.notify(COMPLETE_NOTIFICATION_ID, notification)
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NOTIFICATION_ID)
        restoreDND()

        if (FocusStats.activeSession != null) FocusStats.endSession(isCompleted = false)

        prefs.edit { putBoolean("focus_mode", false) }
        TimerStateManager.reset()
    }
}

fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
