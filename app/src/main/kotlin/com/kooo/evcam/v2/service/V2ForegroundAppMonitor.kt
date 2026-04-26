package com.kooo.evcam.v2.service

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

class V2ForegroundAppMonitor(private val context: Context) {
    fun findForegroundTarget(targets: List<String>): String? {
        if (targets.isEmpty()) return null
        return findByRunningTasks(targets) ?: findByUsageEvents(targets)
    }

    private fun findByRunningTasks(targets: List<String>): String? = runCatching {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val targetSet = targets.toSet()
        @Suppress("DEPRECATION")
        activityManager.getRunningTasks(10).orEmpty().firstNotNullOfOrNull { task ->
            val top = task.topActivity ?: return@firstNotNullOfOrNull null
            when {
                targetSet.contains(top.packageName) -> top.packageName
                targetSet.contains(top.className) -> top.className
                else -> null
            }
        }
    }.onFailure { V2AppLog.e("V2ForegroundAppMonitor", "running task foreground check failed", it) }.getOrNull()

    private fun findByUsageEvents(targets: List<String>): String? = runCatching {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val targetSet = targets.toSet()
        val lastStates = LinkedHashMap<String, Boolean>()
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - 300_000L, now) ?: return@runCatching null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val matched = when {
                targetSet.contains(event.packageName) -> event.packageName
                targetSet.contains(event.className) -> event.className
                else -> null
            } ?: continue
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> lastStates[matched] = true
                UsageEvents.Event.MOVE_TO_BACKGROUND -> lastStates[matched] = false
            }
        }
        targets.firstOrNull { lastStates[it] == true }
    }.onFailure { V2AppLog.e("V2ForegroundAppMonitor", "usage events foreground check failed", it) }.getOrNull()
}
