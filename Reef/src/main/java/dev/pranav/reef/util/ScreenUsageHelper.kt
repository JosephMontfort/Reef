package dev.pranav.reef.util

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import java.util.Calendar

/**
 * Usage tracking using UsageStatsManager.queryUsageStats(INTERVAL_DAILY, ...) —
 * the same approach used by Digital Wellbeing and virtually every reputable
 * third-party screen-time app. This is the official Android API for this
 * exact purpose.
 *
 * Two prior approaches were tried and rejected:
 * 1. INTERVAL_BEST — silently picks whatever bucket size (daily/weekly/
 *    monthly) the OS has data for, which is NOT guaranteed to match your
 *    query window. This caused single-day totals to show 30+ hours.
 * 2. Manual queryEvents() RESUMED/PAUSED parsing — fragile across OEM
 *    ROMs, multi-window/split-screen, and long-running sessions; measured
 *    to undercount real usage by roughly half in testing.
 *
 * INTERVAL_DAILY explicitly requests the smallest standard bucket, and the
 * OS guarantees returned stats are a subset of the requested time range.
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
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            stats?.forEach { stat ->
                if (targetPackage != null && stat.packageName != targetPackage) return@forEach
                // totalTimeVisible (API 29+) avoids double-counting overlapping
                // windows/activities — same field Digital Wellbeing reads.
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
