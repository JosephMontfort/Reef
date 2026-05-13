package dev.pranav.reef.timer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TimerSessionState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val timeRemaining: Long = 0,
    val pomodoroPhase: PomodoroPhase = PomodoroPhase.FOCUS,
    val currentCycle: Int = 0,
    val totalCycles: Int = 4,
    val isPomodoroMode: Boolean = false,
    val isStrictMode: Boolean = false
)

enum class PomodoroPhase {
    FOCUS,
    SHORT_BREAK,
    LONG_BREAK,
    COMPLETE
}

data class PomodoroConfig(
    val focusDuration: Long,
    val shortBreakDuration: Long,
    val longBreakDuration: Long,
    val cyclesBeforeLongBreak: Int
)

object TimerStateManager {
    private val _state = MutableStateFlow(TimerSessionState())
    val state: StateFlow<TimerSessionState> = _state.asStateFlow()

    private var pomodoroConfig: PomodoroConfig? = null

    fun updateState(update: TimerSessionState.() -> TimerSessionState) {
        _state.value = _state.value.update()
    }

    fun setPomodoroConfig(config: PomodoroConfig) {
        pomodoroConfig = config
    }

    fun getPomodoroConfig(): PomodoroConfig? = pomodoroConfig

    fun reset() {
        _state.value = TimerSessionState()
        pomodoroConfig = null
    }

    fun getCurrentPhase(): PomodoroPhase = _state.value.pomodoroPhase

    fun getTimeRemaining(): Long = _state.value.timeRemaining

    fun isInBreak(): Boolean {
        return _state.value.pomodoroPhase == PomodoroPhase.SHORT_BREAK ||
                _state.value.pomodoroPhase == PomodoroPhase.LONG_BREAK
    }
}

