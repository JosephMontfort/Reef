package dev.pranav.reef.util

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

        // queryUsageStats(INTERVAL_BEST) already reflects live foreground time
        // for the currently-open app on modern Android (this is what Digital
        // Wellbeing reads from too) — no separate event-based overlay needed.
        // Adding one on top double-counts the in-progress session.
        runCatching {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
            stats?.forEach { stat ->
                if (targetPackage != null && stat.packageName != targetPackage) return@forEach
                val time = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    stat.totalTimeVisible else stat.totalTimeInForeground
                if (time > 0) result[stat.packageName] = (result[stat.packageName] ?: 0L) + time
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
