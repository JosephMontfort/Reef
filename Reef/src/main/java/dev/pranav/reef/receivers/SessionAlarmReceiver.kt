package dev.pranav.reef.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import dev.pranav.reef.accessibility.FocusModeService

/**
 * Receives the exact-alarm broadcast when a focus/break phase ends.
 * Holds a brief WakeLock so the CPU stays alive long enough for
 * FocusModeService to handle the phase transition.
 *
 * The WakeLock is released either by FocusModeService.onStartCommand
 * after the transition, or by the 10-second timeout as safety net.
 */
class SessionAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PHASE_END = "dev.pranav.reef.PHASE_END"
        private const val TAG = "SessionAlarmReceiver"
        private const val WAKE_LOCK_TAG = "reef:phase_transition"
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L

        // Shared WakeLock — acquired here, released by FocusModeService
        @Volatile
        private var wakeLock: PowerManager.WakeLock? = null

        fun releaseWakeLock() {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PHASE_END) return
        Log.i(TAG, "Phase-end alarm fired")

        // Acquire PARTIAL_WAKE_LOCK so CPU doesn't sleep before state transition completes
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }

        // Tell FocusModeService the phase ended (same path as natural CountDownTimer finish)
        try {
            context.startForegroundService(
                Intent(context, FocusModeService::class.java).apply {
                    action = FocusModeService.ACTION_PHASE_END_ALARM
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FocusModeService for phase transition", e)
            releaseWakeLock()
        }
    }
}
