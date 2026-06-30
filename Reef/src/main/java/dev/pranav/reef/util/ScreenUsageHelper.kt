package dev.pranav.reef.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import java.util.Calendar

/**
 * Usage tracking using UsageStatsManager.queryEvents()
 * * Accurately calculates screen time by pairing MOVE_TO_FOREGROUND (1) 
 * and MOVE_TO_BACKGROUND (2) events. It strictly ignores orphan background 
 * events caused by the OS killing inactive processes, matching the 
 * exact tracking behavior of Digital Wellbeing.
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
        usageStatsManager: UsageStatsManager,
        startTime: Long,
        endTime: Long,
        targetPackage: String? = null
    ): Map<String, Long> {
        val usageMap = mutableMapOf<String, Long>()
        val events = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        
        val lastEventTimes = mutableMapOf<String, Long>()
        val isForeground = mutableMapOf<String, Boolean>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            
            if (targetPackage != null && pkg != targetPackage) continue

            val type = event.eventType
            val timestamp = event.timeStamp

            // 1 = MOVE_TO_FOREGROUND
            if (type == 1) { 
                lastEventTimes[pkg] = timestamp
                isForeground[pkg] = true
                
            // 2 = MOVE_TO_BACKGROUND, 26 = DEVICE_SHUTDOWN
            } else if (type == 2 || type == 26) { 
                // ONLY process if previously marked as in foreground
                if (isForeground[pkg] == true) {
                    val start = lastEventTimes[pkg] ?: startTime
                    usageMap[pkg] = (usageMap[pkg] ?: 0L) + (timestamp - start)
                    isForeground[pkg] = false
                }
            }
        }

        // Ongoing session: still in foreground at endTime
        for ((pkg, inForeground) in isForeground) {
            if (inForeground) {
                val start = lastEventTimes[pkg] ?: startTime
                usageMap[pkg] = (usageMap[pkg] ?: 0L) + (endTime - start)
            }
        }

        return usageMap
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
