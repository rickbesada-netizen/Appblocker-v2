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

    func activate() {
        active = true
        AppGroup.defaults.set(true, forKey: activeKey)
        AppGroup.defaults.removeObject(forKey: untilKey)
    }

    // Returns nil if it can be turned off now, else the unlock date.
    func requestDeactivate() -> Date? {
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
