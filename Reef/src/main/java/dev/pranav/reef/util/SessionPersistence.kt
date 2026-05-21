package dev.pranav.reef.util

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dev.pranav.reef.timer.PomodoroConfig
import dev.pranav.reef.timer.PomodoroPhase
import dev.pranav.reef.timer.TimerSessionState

/**
 * Persists focus-session state to shared preferences on every tick.
 *
 * Strategy: instead of storing "time remaining" (which goes stale the instant
 * the process dies), we store **the epoch-ms at which the current phase will
 * end**.  On recovery we compute:
 *
 *   remaining = phaseEndEpoch - System.currentTimeMillis()
 *
 * If the app was force-stopped mid-session, this gives us the correct
 * remaining time even after being offline for 2+ minutes.
 *
 * Call [save] whenever state changes (start, pause, resume, tick).
 * Call [clear] only when the session truly finishes or the user manually stops.
 */
object SessionPersistence {

    private const val TAG = "SessionPersistence"
    private const val PREFS_NAME = "session_persistence"

    // ── Keys ──────────────────────────────────────────────────────────────────
    private const val KEY_ACTIVE           = "sp_active"
    private const val KEY_PHASE_END_EPOCH  = "sp_phase_end_epoch"   // when current phase expires
    private const val KEY_PAUSED_REMAINING = "sp_paused_remaining"  // ms remaining when paused
    private const val KEY_IS_PAUSED        = "sp_is_paused"
    private const val KEY_IS_STRICT        = "sp_is_strict"
    private const val KEY_IS_POMODORO      = "sp_is_pomodoro"
    private const val KEY_POMODORO_PHASE   = "sp_pomodoro_phase"
    private const val KEY_POMODORO_CYCLE   = "sp_pomodoro_cycle"
    private const val KEY_POMODORO_TOTAL   = "sp_pomodoro_total_cycles"
    private const val KEY_PHASE_DURATION   = "sp_phase_duration"    // total duration of this phase
    // Pomodoro config
    private const val KEY_CFG_FOCUS        = "sp_cfg_focus_ms"
    private const val KEY_CFG_SHORT        = "sp_cfg_short_ms"
    private const val KEY_CFG_LONG         = "sp_cfg_long_ms"
    private const val KEY_CFG_CYCLES       = "sp_cfg_cycles"
    private const val KEY_NUCLEAR_RUNNING  = "sp_nuclear_running"

    data class RestoredSession(
        val state: TimerSessionState,
        val remainingMs: Long,
        val phaseDurationMs: Long,
        val pomodoroConfig: PomodoroConfig?
    )

    private fun sp(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Save ──────────────────────────────────────────────────────────────────

    fun saveRunning(
        context: Context,
        state: TimerSessionState,
        millisUntilFinished: Long,
        phaseDuration: Long,
        config: PomodoroConfig?
    ) {
        val phaseEndEpoch = System.currentTimeMillis() + millisUntilFinished
        sp(context).edit {
            putBoolean(KEY_ACTIVE, true)
            putLong(KEY_PHASE_END_EPOCH, phaseEndEpoch)
            putLong(KEY_PAUSED_REMAINING, millisUntilFinished)
            putBoolean(KEY_IS_PAUSED, false)
            putBoolean(KEY_IS_STRICT, state.isStrictMode)
            putBoolean(KEY_IS_POMODORO, state.isPomodoroMode)
            putString(KEY_POMODORO_PHASE, state.pomodoroPhase.name)
            putInt(KEY_POMODORO_CYCLE, state.currentCycle)
            putInt(KEY_POMODORO_TOTAL, state.totalCycles)
            putLong(KEY_PHASE_DURATION, phaseDuration)
            // Reset nuclear flag — WatchdogManager.start() sets it back to true if needed.
            // Without this, a previous session's nuclear=true bleeds into the next session.
            putBoolean(KEY_NUCLEAR_RUNNING, false)
            config?.let {
                putLong(KEY_CFG_FOCUS, it.focusDuration)
                putLong(KEY_CFG_SHORT, it.shortBreakDuration)
                putLong(KEY_CFG_LONG, it.longBreakDuration)
                putInt(KEY_CFG_CYCLES, it.cyclesBeforeLongBreak)
            }
        }
    }

    fun savePaused(
        context: Context,
        state: TimerSessionState,
        remainingMs: Long,
        phaseDuration: Long,
        config: PomodoroConfig?
    ) {
        sp(context).edit {
            putBoolean(KEY_ACTIVE, true)
            putLong(KEY_PAUSED_REMAINING, remainingMs)
            putBoolean(KEY_IS_PAUSED, true)
            putBoolean(KEY_IS_STRICT, state.isStrictMode)
            putBoolean(KEY_IS_POMODORO, state.isPomodoroMode)
            putString(KEY_POMODORO_PHASE, state.pomodoroPhase.name)
            putInt(KEY_POMODORO_CYCLE, state.currentCycle)
            putInt(KEY_POMODORO_TOTAL, state.totalCycles)
            putLong(KEY_PHASE_DURATION, phaseDuration)
            config?.let {
                putLong(KEY_CFG_FOCUS, it.focusDuration)
                putLong(KEY_CFG_SHORT, it.shortBreakDuration)
                putLong(KEY_CFG_LONG, it.longBreakDuration)
                putInt(KEY_CFG_CYCLES, it.cyclesBeforeLongBreak)
            }
        }
    }

    fun markNuclearRunning(context: Context, running: Boolean) {
        sp(context).edit { putBoolean(KEY_NUCLEAR_RUNNING, running) }
    }

    fun isNuclearRunning(context: Context): Boolean =
        sp(context).getBoolean(KEY_NUCLEAR_RUNNING, false)

    // ── Clear ─────────────────────────────────────────────────────────────────

    fun clear(context: Context) {
        sp(context).edit { clear() }
        Log.d(TAG, "Session persistence cleared")
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    fun hasActiveSession(context: Context): Boolean =
        sp(context).getBoolean(KEY_ACTIVE, false)

    /**
     * Returns a [RestoredSession] if a valid persisted session exists
     * and has not yet expired, or null otherwise.
     */
    fun restore(context: Context): RestoredSession? {
        val p = sp(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return null

        val isPaused       = p.getBoolean(KEY_IS_PAUSED, false)
        val isStrict       = p.getBoolean(KEY_IS_STRICT, false)
        val isPomodoro     = p.getBoolean(KEY_IS_POMODORO, false)
        val phaseStr       = p.getString(KEY_POMODORO_PHASE, PomodoroPhase.FOCUS.name)
        val phase          = runCatching { PomodoroPhase.valueOf(phaseStr!!) }.getOrDefault(PomodoroPhase.FOCUS)
        val cycle          = p.getInt(KEY_POMODORO_CYCLE, 1)
        val totalCycles    = p.getInt(KEY_POMODORO_TOTAL, 4)
        val phaseDuration  = p.getLong(KEY_PHASE_DURATION, 0L)

        val remainingMs = if (isPaused) {
            p.getLong(KEY_PAUSED_REMAINING, 0L)
        } else {
            val phaseEndEpoch = p.getLong(KEY_PHASE_END_EPOCH, 0L)
            (phaseEndEpoch - System.currentTimeMillis()).coerceAtLeast(0L)
        }

        // Session has expired naturally — clear it
        if (remainingMs <= 0L && !isPaused) {
            Log.d(TAG, "Persisted session already expired, clearing")
            clear(context)
            return null
        }

        val pomodoroConfig = if (isPomodoro) {
            PomodoroConfig(
                focusDuration         = p.getLong(KEY_CFG_FOCUS,  25 * 60_000L),
                shortBreakDuration    = p.getLong(KEY_CFG_SHORT,   5 * 60_000L),
                longBreakDuration     = p.getLong(KEY_CFG_LONG,   15 * 60_000L),
                cyclesBeforeLongBreak = p.getInt(KEY_CFG_CYCLES, 4)
            )
        } else null

        val restoredState = TimerSessionState(
            isRunning      = !isPaused,
            isPaused       = isPaused,
            timeRemaining  = remainingMs,
            pomodoroPhase  = phase,
            currentCycle   = cycle,
            totalCycles    = totalCycles,
            isPomodoroMode = isPomodoro,
            isStrictMode   = isStrict
        )

        Log.d(TAG, "Restored session: phase=$phase remaining=${remainingMs/1000}s paused=$isPaused")
        return RestoredSession(restoredState, remainingMs, phaseDuration, pomodoroConfig)
    }
}
