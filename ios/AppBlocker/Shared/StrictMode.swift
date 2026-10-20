import Foundation

// In-app Strict Mode: makes it harder to turn blocking off from inside the app
// (a wait timer). See README for the honest limits - without the user setting a
// Screen Time passcode, iOS Settings can still override restrictions.
final class StrictMode: ObservableObject {
    static let shared = StrictMode()

    private let activeKey = "strict_active"
    private let untilKey = "strict_until"

    @Published var active: Bool = false

    private init() {
        active = AppGroup.defaults.bool(forKey: activeKey)
    }

    // How long the user must wait before Strict Mode can be turned off.
    var waitMinutes: Int {
        get { AppGroup.defaults.integer(forKey: "strict_wait_minutes") }
        set { AppGroup.defaults.set(newValue, forKey: "strict_wait_minutes") }
    }

    // ----- Weekly unlock window -----
    // The one time each week Strict Mode can be turned off. When enabled it is
    // the sole unlock gate and grants schedule immunity (a 24/7 schedule is OK
    // because this window is the guaranteed way out). day 0 = Sunday .. 6 = Sat.
    var weeklyEnabled: Bool {
        get { AppGroup.defaults.bool(forKey: "ww_enabled") }
        set { AppGroup.defaults.set(newValue, forKey: "ww_enabled") }
    }
    var weeklyDay: Int {
        get { AppGroup.defaults.integer(forKey: "ww_day") }
        set { AppGroup.defaults.set(min(6, max(0, newValue)), forKey: "ww_day") }
    }
    var weeklyStart: String {
        get { AppGroup.defaults.string(forKey: "ww_start") ?? "03:00" }
        set { AppGroup.defaults.set(newValue, forKey: "ww_start") }
    }
    var weeklyMinutes: Int {
        get { max(1, AppGroup.defaults.integer(forKey: "ww_minutes")) }
        set { AppGroup.defaults.set(max(1, newValue), forKey: "ww_minutes") }
    }

    var hasGrace: Bool { weeklyEnabled && weeklyMinutes >= 1 }

    func withinWindow(_ nowDate: Date = Date()) -> Bool {
        guard weeklyEnabled else { return false }
        let cal = Calendar.current
        let c = cal.dateComponents([.weekday, .hour, .minute], from: nowDate)
        let dow = (c.weekday ?? 1) - 1 // Sunday = 1 -> 0
        let t = dow * 1440 + (c.hour ?? 0) * 60 + (c.minute ?? 0)
        let p = weeklyStart.split(separator: ":")
        let h = Int(p.first ?? "3") ?? 3
        let m = p.count > 1 ? (Int(p[1]) ?? 0) : 0
        let s = weeklyDay * 1440 + h * 60 + m
        let e = s + max(1, weeklyMinutes)
        let week = 7 * 1440
        if e <= week { return t >= s && t < e }
        return t >= s || t < (e - week) // wraps past Saturday midnight
    }

    func nextWindowDate(_ nowDate: Date = Date()) -> Date {
        let cal = Calendar.current
        let p = weeklyStart.split(separator: ":")
        let h = Int(p.first ?? "3") ?? 3
        let m = p.count > 1 ? (Int(p[1]) ?? 0) : 0
        let todayDow = cal.component(.weekday, from: nowDate) - 1
        let delta = ((weeklyDay - todayDow) + 7) % 7
        var target = cal.date(bySettingHour: h, minute: m, second: 0, of: nowDate) ?? nowDate
        target = cal.date(byAdding: .day, value: delta, to: target) ?? target
        if target <= nowDate { target = cal.date(byAdding: .day, value: 7, to: target) ?? target }
        return target
    }

    func activate() {
        active = true
        AppGroup.defaults.set(true, forKey: activeKey)
        AppGroup.defaults.removeObject(forKey: untilKey)
    }

    // Returns nil if it can be turned off now, else the date it unlocks.
    func requestDeactivate() -> Date? {
        // When a weekly window is set, it is the ONLY gate: off during the
        // window, otherwise locked until the next one (the wait timer is ignored).
        if weeklyEnabled {
            if withinWindow() { deactivate(); return nil }
            return nextWindowDate()
        }
        if waitMinutes <= 0 {
            deactivate()
            return nil
        }
        let d = AppGroup.defaults
        if let until = d.object(forKey: untilKey) as? Date {
            if Date() >= until {
                deactivate()
                return nil
            }
            return until
        }
        let until = Date().addingTimeInterval(TimeInterval(waitMinutes * 60))
        d.set(until, forKey: untilKey)
        return until
    }

    private func deactivate() {
        active = false
        AppGroup.defaults.set(false, forKey: activeKey)
        AppGroup.defaults.removeObject(forKey: untilKey)
    }
}
