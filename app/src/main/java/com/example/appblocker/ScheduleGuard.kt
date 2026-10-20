package com.example.appblocker

import java.util.Calendar

/**
 * The one hard rule behind the whole "can't lock yourself out" design: a
 * blocking schedule can NEVER cover a full week. At least one minute somewhere
 * in the 7-day week must stay free, so there is always a window to change
 * settings, turn Strict Mode off, or uninstall. Mirrors the desktop
 * shared/scheduleGuard.js.
 *
 * TimeRange.days use Calendar day numbers (SUNDAY=1 .. SATURDAY=7); here they map
 * to week index 0..6 (0 = Sunday).
 */
object ScheduleGuard {
    private const val MIN_PER_DAY = 1440
    private const val MIN_PER_WEEK = 7 * MIN_PER_DAY // 10080

    private fun mark(blocked: BooleanArray, r: TimeRange) {
        if (r.days.isEmpty()) return
        val s = r.startMinutes
        val e = r.endMinutes
        for (d in r.days) {
            val idx = (d - 1).coerceIn(0, 6) // Calendar SUNDAY=1 -> 0
            val base = idx * MIN_PER_DAY
            when {
                s == e -> for (m in 0 until MIN_PER_DAY) blocked[base + m] = true   // all day
                s < e -> for (m in s until e) blocked[base + m] = true
                else -> {
                    // crosses midnight: [s,24:00) today, [0,e) next day
                    for (m in s until MIN_PER_DAY) blocked[base + m] = true
                    val nextBase = ((idx + 1) % 7) * MIN_PER_DAY
                    for (m in 0 until e) blocked[nextBase + m] = true
                }
            }
        }
    }

    fun weeklyFreeMinutes(ranges: List<TimeRange>): Int {
        val active = ranges.filter { it.days.isNotEmpty() }
        if (active.isEmpty()) return MIN_PER_WEEK
        val blocked = BooleanArray(MIN_PER_WEEK)
        for (r in active) mark(blocked, r)
        return blocked.count { !it }
    }

    fun leavesWeeklyGap(ranges: List<TimeRange>): Boolean = weeklyFreeMinutes(ranges) >= 1

    /** Error string if the schedule would seal the whole week, else null. */
    fun scheduleError(scheduleEnabled: Boolean, ranges: List<TimeRange>): String? {
        if (!scheduleEnabled) return null
        val active = ranges.filter { it.days.isNotEmpty() }
        if (active.isEmpty()) return null
        if (!leavesWeeklyGap(active)) {
            return "This schedule would block all day, every day. Leave at least one free " +
                "minute in the week (for example end a day at 23:58) - that minute is the only " +
                "time you can turn things off or uninstall."
        }
        return null
    }
}
