package dev.pranav.reef.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.edit

/**
 * Master switch for minimal UI haptics (wheel-picker tick, button-press
 * confirmation). This does NOT gate the coarser, intentional vibrations
 * used for things like "stop focusing" / session-end alerts — those stay
 * on regardless, since they're deliberate notifications rather than
 * incidental UI polish.
 */
object HapticSettings {
    private const val KEY = "ui_haptics_enabled"

    var isEnabled by mutableStateOf(true)
        private set

    fun init() {
        isEnabled = prefs.getBoolean(KEY, true)
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        prefs.edit { putBoolean(KEY, enabled) }
    }
}

/**
 * Drop-in wrapper around Compose's HapticFeedback that respects the master
 * UI-haptics switch. Use this instead of LocalHapticFeedback directly for
 * any "nice to have" interaction feedback (wheel ticks, button taps).
 */
@Composable
fun rememberGatedHapticFeedback(): HapticFeedback {
    val actual = LocalHapticFeedback.current
    return androidx.compose.runtime.remember(actual) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                if (HapticSettings.isEnabled) {
                    actual.performHapticFeedback(hapticFeedbackType)
                }
            }
        }
    }
}
