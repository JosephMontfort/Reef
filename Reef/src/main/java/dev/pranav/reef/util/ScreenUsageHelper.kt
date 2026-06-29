package dev.pranav.reef.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import java.util.Calendar

/**
 * Usage tracking using the same approach as Digital Wellbeing:
 * queryUsageStats(INTERVAL_BEST) for aggregate totals, with an event-based
 * overlay to catch the currently-open app that hasn't received a PAUSE yet.
 */
object ScreenUsageHelper {

    fun calculateUsage(
        @Suppress("UNUSED_PARAMETER") context: Context,
        usageStatsManager: UsageStatsManager,
        startTime: Long,
        endTime: Long,
        targetPackage: String? = null
    ): Map<String, Long> = fetchUsageInMs(usageStatsManager, startTime, endTime, targetPackage)

    fun fetchUsageInMs(
        usm: UsageStatsManager,
        start: Long,
        end: Long,
        targetPackage: String? = null
    ): Map<String, Long> {
        val result = mutableMapOf<String, Long>()

        // Step 1: aggregate totals from queryUsageStats (what DW uses)
        runCatching {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
            stats?.forEach { stat ->
                if (targetPackage != null && stat.packageName != targetPackage) return@forEach
                val time = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    stat.totalTimeVisible else stat.totalTimeInForeground
                if (time > 0) result[stat.packageName] = (result[stat.packageName] ?: 0L) + time
            }
        }

        // Step 2: event-based overlay — credit currently-open app up to `end`
        // This catches the foreground app that DW also adds live time to.
        runCatching {
            val lookback = maxOf(start, end - 2 * 60 * 60 * 1000L) // max 2h back
            val events = usm.queryEvents(lookback, end)
            val event = UsageEvents.Event()
            val resumeMap = mutableMapOf<String, Long>()
            while (events.hasNextEvent() && events.getNextEvent(event)) {
                if (targetPackage != null && event.packageName != targetPackage) continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED ->
                        resumeMap[event.packageName] = event.timeStamp
                    UsageEvents.Event.ACTIVITY_PAUSED ->
                        resumeMap.remove(event.packageName)
                }
            }
            // Any app still in resumeMap is currently in foreground — add live time
            resumeMap.forEach { (pkg, resumeTime) ->
                val liveMs = end - maxOf(resumeTime, start)
                if (liveMs > 0) {
                    // Only add if not already counted by queryUsageStats (avoid double-count)
                    // queryUsageStats already includes time up to the last PAUSE, so we only
                    // add the delta since the last resume that hasn't been paused yet.
                    result[pkg] = (result[pkg] ?: 0L) + liveMs
                }
            }
        }

        return result.filterValues { it > 0L }
    }

    fun fetchAppUsageTodayTillNow(usm: UsageStatsManager): Map<String, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Returns seconds (divide ms by 1000) — home screen then divides by 60 for minutes
        return fetchUsageInMs(usm, cal.timeInMillis, System.currentTimeMillis())
            .mapValues { it.value / 1000L }
    }
}
