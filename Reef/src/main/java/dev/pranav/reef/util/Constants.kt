package dev.pranav.reef.util

import android.content.SharedPreferences

const val BLOCKER_CHANNEL_ID = "content_blocker"
const val FOCUS_MODE_CHANNEL_ID = "focus_mode"
const val REMINDER_CHANNEL_ID = "reminders"
const val ROUTINE_CHANNEL_ID = "routine_notification_id"
const val ROUTINE_STATUS_CHANNEL_ID = "routine_status"
// Dedicated silent channel for the AppBlockerService persistent FGS notification.
const val APP_BLOCKER_SERVICE_CHANNEL_ID = "app_blocker_service"
lateinit var prefs: SharedPreferences

val isPrefsInitialized: Boolean
    get() = ::prefs.isInitialized
