package dev.pranav.reef.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/**
 * Usage tracking via raw queryEvents() — the same primitive Digital Wellbeing
 * uses internally. queryUsageStats(INTERVAL_*) is NOT used here: it returns
 * cumulative totals for whatever bucket (daily/weekly/monthly) the system
 * has on hand, and that bucket is NOT clipped to the [start, end] you pass
 * in — querying "today" can silently return a whole week's total. The only
 * way to get an accurate, precisely-windowed duration is to walk the raw
 * ACTIVITY_RESUMED / ACTIVITY_PAUSED (and STOPPED) event pairs ourselves and
 * sum the time each package was actually in the foreground within the
 * requested window.
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
        if (end <= start) return emptyMap()

        val result = mutableMapOf<String, Long>()
        // Tracks the resume timestamp for each package currently in foreground,
        // clamped to `start` so a session that began before the window still
        // only counts the portion inside [start, end].
        val openSessions = mutableMapOf<String, Long>()

        runCatching {
            val events = usm.queryEvents(start, end)
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                if (targetPackage != null && pkg != targetPackage) continue

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        openSessions[pkg] = event.timeStamp.coerceAtLeast(start)
                    }

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        val resumeTime = openSessions.remove(pkg)
                        if (resumeTime != null) {
                            val duration = (event.timeStamp.coerceAtMost(end) - resumeTime)
                            if (duration > 0) {
                                result[pkg] = (result[pkg] ?: 0L) + duration
                            }
                        }
                    }
                }
            }

            // Any package still "open" at the end of the window (app is
            // currently in foreground, hasn't paused yet) — count up to `end`.
            openSessions.forEach { (pkg, resumeTime) ->
                val duration = end - resumeTime
                if (duration > 0) {
                    result[pkg] = (result[pkg] ?: 0L) + duration
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
