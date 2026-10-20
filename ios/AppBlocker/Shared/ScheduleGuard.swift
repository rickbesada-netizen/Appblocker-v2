import Foundation

/// The one hard rule behind the "can't lock yourself out" design: a blocking
/// schedule can NEVER cover a full week. At least one minute somewhere in the
/// 7-day week must stay free, so there is always a window to change settings or
/// turn Strict Mode off. Mirrors the Android ScheduleGuard and the desktop
/// shared/scheduleGuard.js.
///
/// TimeRange.days use Calendar day numbers (Sunday = 1 ... Saturday = 7); here
/// they map to week index 0...6 (0 = Sunday).
enum ScheduleGuard {
    static let minutesPerDay = 1440
    static let minutesPerWeek = 7 * 1440 // 10080

    private static func mark(_ blocked: inout [Bool], _ r: TimeRange) {
        if r.days.isEmpty { return }
        let s = r.startHour * 60 + r.startMinute
        let e = r.endHour * 60 + r.endMinute
        for d in r.days {
            let idx = max(0, min(6, d - 1)) // Calendar Sunday=1 -> 0
            let base = idx * minutesPerDay
            if s == e {
                for m in 0..<minutesPerDay { blocked[base + m] = true } // all day
            } else if s < e {
                for m in s..<e { blocked[base + m] = true }
            } else {
                // crosses midnight: [s,24:00) today, [0,e) next day
                for m in s..<minutesPerDay { blocked[base + m] = true }
                let nextBase = ((idx + 1) % 7) * minutesPerDay
                for m in 0..<e { blocked[nextBase + m] = true }
            }
        }
    }

    static func weeklyFreeMinutes(_ ranges: [TimeRange]) -> Int {
        let active = ranges.filter { !$0.days.isEmpty }
        if active.isEmpty { return minutesPerWeek }
        var blocked = [Bool](repeating: false, count: minutesPerWeek)
        for r in active { mark(&blocked, r) }
        return blocked.lazy.filter { !$0 }.count
    }

    static func leavesWeeklyGap(_ ranges: [TimeRange]) -> Bool {
        weeklyFreeMinutes(ranges) >= 1
    }

    /// Returns an error message if the schedule would seal the whole week, else nil.
    static func scheduleError(_ scheduleEnabled: Bool, _ ranges: [TimeRange]) -> String? {
        if !scheduleEnabled { return nil }
        let active = ranges.filter { !$0.days.isEmpty }
        if active.isEmpty { return nil }
        if !leavesWeeklyGap(active) {
            return "This schedule would block all day, every day. Leave at least one free "
                + "minute in the week (for example end a day at 23:58) - that minute is the only "
                + "time you can turn things off."
        }
        return nil
    }
}
