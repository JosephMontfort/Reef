package dev.pranav.reef.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.widget.RemoteViews
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.ContextThemeWrapper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.pranav.reef.MainActivity
import dev.pranav.reef.R
import dev.pranav.reef.services.routines.RoutineSessionManager

object NotificationHelper {
    const val ROUTINE_STATUS_NOTIFICATION_ID = 5001
    private const val REMINDER_NOTIFICATION_ID = 200

    const val BLOCKER_GROUP_KEY = "dev.pranav.reef.BLOCKER_GROUP"
    const val BLOCKER_SUMMARY_ID = 5000

    fun Context.createNotificationChannel() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(
            NotificationChannel(
                APP_BLOCKER_SERVICE_CHANNEL_ID,
                getString(R.string.app_blocker_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.app_blocker_channel_description)
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
        )
        notificationManager.createNotificationChannel(
                NotificationChannel(BLOCKER_CHANNEL_ID, getString(R.string.blocker_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.blocker_channel_description)
                setSound(null, null)
                setBypassDnd(true)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ROUTINE_STATUS_CHANNEL_ID,
                getString(R.string.routine_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.routine_channel_description)
                setShowBadge(false)
                setSound(null, null)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                REMINDER_CHANNEL_ID,
                getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.reminder_channel_description)
                setBypassDnd(true)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                FOCUS_MODE_CHANNEL_ID,
                getString(R.string.focus_mode_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.focus_mode_channel_description)
                setBypassDnd(true)
            }
        )
    }

    fun syncRoutineNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val activeNames = RoutineSessionManager.getActiveRoutineNames()

        val context = ContextThemeWrapper(context, R.style.Theme_Reef)

        if (activeNames.isEmpty()) {
            manager.cancel(ROUTINE_STATUS_NOTIFICATION_ID)
            return
        }

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                putExtra(
                    "navigate_to_routines",
                    true
                )
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (activeNames.size == 1) {
            context.getString(R.string.routine_active_single, activeNames[0])
        } else {
            context.getString(R.string.active_routines_list, activeNames.joinToString(", "))
        }

        val notification = NotificationCompat.Builder(context, ROUTINE_STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.routines))
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        manager.notify(ROUTINE_STATUS_NOTIFICATION_ID, notification)
    }

    fun showReminderNotification(context: Context, packageName: String, timeRemaining: Long) {
        val appName = try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            )
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }

        val totalSeconds = (timeRemaining / 1000).toInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        val timeText = when {
            minutes > 0 -> context.resources.getQuantityString(
                R.plurals.app_will_be_blocked_in, minutes, appName, minutes
            )
            else -> context.getString(R.string.app_will_be_blocked_in_seconds, appName, seconds)
        }

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notifId = REMINDER_NOTIFICATION_ID + packageName.hashCode()

        val builder = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.time_limit_reminder))
            .setContentText(timeText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        }
    }

    fun cancelReminderNotification(context: Context, packageName: String) {
        NotificationManagerCompat.from(context)
            .cancel(REMINDER_NOTIFICATION_ID + packageName.hashCode())
    }
}
