package com.example.appblocker

import android.content.Context
import android.os.BatteryManager
import java.util.concurrent.TimeUnit

// Result for a single unlock method: whether it currently permits
// deactivating the lock, plus a human-readable reason to show when it doesn't.
data class UnlockCheckResult(
    val method: UnlockMethod,
    val satisfied: Boolean,
    val reason: String
)

object UnlockChecker {

    // Methods that need something more than a simple state check
    // (PIN needs a dialog, Cooldown needs a running countdown) are
    // handled separately by the caller. This only covers the
    // "ambient condition" methods. `scope` selects which lock's config to use.
    fun checkAmbientMethods(context: Context, scope: String = ""): List<UnlockCheckResult> {
        val results = mutableListOf<UnlockCheckResult>()
        val selected = UnlockMethodStore.getSelectedMethods(context, scope)

        if (UnlockMethod.TIMER in selected) {
            val activatedAt = UnlockMethodStore.getActivatedAt(context, scope)
            val requiredMs = TimeUnit.MINUTES.toMillis(UnlockMethodStore.getTimerMinutes(context, scope).toLong())
            val elapsed = System.currentTimeMillis() - activatedAt
            val remainingMs = requiredMs - elapsed
            results.add(
                if (remainingMs <= 0) {
                    UnlockCheckResult(UnlockMethod.TIMER, true, "Timer finished")
                } else {
                    val remainingMin = TimeUnit.MILLISECONDS.toMinutes(remainingMs) + 1
                    UnlockCheckResult(UnlockMethod.TIMER, false, "$remainingMin min left on the timer")
                }
            )
        }

        if (UnlockMethod.FOLLOW_SCHEDULES in selected) {
            // Use the dedicated unlock schedule if one is set; otherwise (master
            // only) fall back to the block lists' own schedules.
            val withinSchedule = when {
                StrictScheduleStore.hasSchedule(context, scope) -> StrictScheduleStore.isWithinSchedule(context, scope = scope)
                scope.isEmpty() -> BlockEngine.anyScheduledListBlocking(context)
                else -> false
            }
            results.add(
                if (!withinSchedule) {
                    UnlockCheckResult(UnlockMethod.FOLLOW_SCHEDULES, true, "You're outside your locked schedule")
                } else {
                    // Include the exact time it unlocks, when we can work it out.
                    val now = java.util.Calendar.getInstance()
                    val end = StrictScheduleStore.getRanges(context, scope)
                        .firstOrNull { it.contains(now) }?.endMillis(now)
                    val msg = if (end != null) "Locked until ${ScheduleFormat.moment(end, System.currentTimeMillis())}"
                              else "Locked until your schedule ends"
                    UnlockCheckResult(UnlockMethod.FOLLOW_SCHEDULES, false, msg)
                }
            )
        }

        if (UnlockMethod.CHARGER in selected) {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val isCharging = batteryManager.isCharging
            results.add(
                if (isCharging) {
                    UnlockCheckResult(UnlockMethod.CHARGER, true, "Device is charging")
                } else {
                    UnlockCheckResult(UnlockMethod.CHARGER, false, "Plug in your charger first")
                }
            )
        }

        return results
    }

    private val BatteryManager.isCharging: Boolean
        get() {
            val status = this.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
}
