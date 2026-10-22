package com.example.appblocker

import android.content.Context
import java.util.Calendar

/**
 * The weekly unlock window: the one short stretch of the week the user picks as
 * the ONLY time Strict Mode can be turned off. It is the guaranteed "way out",
 * so when it's enabled a block schedule may cover the whole day (the free-minute
 * rule is waived - see ScheduleGuard callers). Mirrors the desktop
 * shared/weekly.js (day 0 = Sunday .. 6 = Saturday).
 */
object WeeklyWindowStore {
    private const val PREFS = "app_blocker_prefs"
    private const val K_ENABLED = "ww_enabled"
    private const val K_DAY = "ww_day"       // 0=Sun .. 6=Sat
    private const val K_START = "ww_start"   // "HH:MM"
    private const val K_MINUTES = "ww_minutes"
    private const val MIN_PER_WEEK = 7 * 24 * 60

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(K_ENABLED, false)
    fun day(c: Context): Int = prefs(c).getInt(K_DAY, 0)
    fun start(c: Context): String = prefs(c).getString(K_START, "03:00") ?: "03:00"
    fun minutes(c: Context): Int = prefs(c).getInt(K_MINUTES, 1).coerceAtLeast(1)

    /** The weekly window gives "immunity": a free period is guaranteed each week. */
    fun hasGrace(c: Context): Boolean = isEnabled(c) && minutes(c) >= 1

    fun set(c: Context, enabled: Boolean, day: Int, start: String, minutes: Int) {
        prefs(c).edit()
            .putBoolean(K_ENABLED, enabled)
            .putInt(K_DAY, day.coerceIn(0, 6))
            .putString(K_START, start)
            .putInt(K_MINUTES, minutes.coerceAtLeast(1))
            .apply()
    }

    private fun minutesOfWeek(cal: Calendar): Int {
        val dow = cal.get(Calendar.DAY_OF_WEEK) - 1 // Calendar SUNDAY=1 -> 0
        return dow * 1440 + cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun bounds(c: Context): Pair<Int, Int> {
        val parts = start(c).split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 3
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val s = day(c) * 1440 + h * 60 + m
        return s to (s + minutes(c))
    }

    fun within(c: Context, now: Calendar = Calendar.getInstance()): Boolean {
        if (!isEnabled(c)) return false
        val (s, e) = bounds(c)
        val t = minutesOfWeek(now)
        return if (e <= MIN_PER_WEEK) t in s until e
        else t >= s || t < (e - MIN_PER_WEEK) // window wraps past Saturday midnight
    }

    fun dayName(d: Int): String =
        listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
            .getOrElse(d) { "Sunday" }

    /** Epoch millis of the next time the window opens (strictly in the future). */
    fun nextWindowStart(c: Context, now: Calendar = Calendar.getInstance()): Long {
        val parts = start(c).split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 3
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val target = now.clone() as Calendar
        // Calendar SUNDAY=1 -> our 0..6
        val nowDow = now.get(Calendar.DAY_OF_WEEK) - 1
        val delta = (day(c) - nowDow + 7) % 7
        target.set(Calendar.HOUR_OF_DAY, h)
        target.set(Calendar.MINUTE, m)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)
        target.add(Calendar.DAY_OF_YEAR, delta)
        if (target.timeInMillis <= now.timeInMillis) target.add(Calendar.DAY_OF_YEAR, 7)
        return target.timeInMillis
    }
}
