package dev.pranav.reef.accessibility

import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log
import dev.pranav.reef.util.SessionPersistence

class ReefNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i("ReefListener", "Listener connected! OS revived the process.")
        checkAndRevive()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        checkAndRevive()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun checkAndRevive() {
        // If the database says a session is active...
        if (SessionPersistence.hasActiveSession(this)) {
            val state = dev.pranav.reef.timer.TimerStateManager.state.value
            
            // ...but RAM says the timer is dead, the process was killed!
            if (!state.isRunning && !state.isPaused) {
                Log.i("ReefListener", "Process death detected. Reviving FocusModeService.")
                val reviveIntent = Intent(this, FocusModeService::class.java).apply {
                    action = FocusModeService.ACTION_RESUME_PERSISTED
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(reviveIntent)
                } else {
                    startService(reviveIntent)
                }
            } else {
                // If timer is already running, just ensure blocker is awake
                AppBlockerService.start(this)
            }
        }
    }
}
