package dev.pranav.reef.util

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import java.util.Calendar

/**
 * Usage tracking using UsageStatsManager.queryAndAggregateUsageStats() —
 * the official convenience method built specifically to return one merged
 * total per package for an arbitrary time range. This is what Digital
 * Wellbeing and reputable third-party screen-time apps use.
 *
 * Two prior approaches were tried and rejected:
 * 1. INTERVAL_BEST — silently picks whatever bucket size (daily/weekly/
 *    monthly) the OS has data for, not guaranteed to match the query
 *    window. Caused single-day totals to show 30+ hours.
 * 2. Manual queryEvents() RESUMED/PAUSED parsing — fragile across OEM
 *    ROMs; undercounted real usage by roughly half in testing.
 * 3. Manual queryUsageStats(INTERVAL_DAILY, ...) summed with +=  — the OS
 *    can return MULTIPLE UsageStats entries for the SAME package within
 *    one day (internal sub-day flush boundaries), and each entry's
 *    totalTimeInForeground/totalTimeVisible is already a running total,
 *    not a delta. Summing them with += multiplied real usage several
 *    times over (e.g. Instagram showing 2h28m for an actual 32m).
 *
 * queryAndAggregateUsageStats() merges those duplicate/overlapping
 * fragments correctly internally, returning exactly one UsageStats per
 * package — no manual merge logic needed.
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
        runCatching {
            val statsMap = usm.queryAndAggregateUsageStats(start, end)
            statsMap.forEach { (pkg, stat) ->
                if (targetPackage != null && pkg != targetPackage) return@forEach
                // totalTimeVisible (API 29+) avoids double-counting overlapping
                // windows/activities — same field Digital Wellbeing reads.
                val time = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    stat.totalTimeVisible else stat.totalTimeInForeground
                if (time > 0) result[pkg] = time
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
