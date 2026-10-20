import Foundation
import FamilyControls

// A named group of apps / categories / sites the user selected through
// Apple's FamilyActivityPicker, with its own switch, schedule and daily limit.
//
// NOTE: Apple hands us opaque *tokens*, not bundle IDs or names. We store the
// whole FamilyActivitySelection (which is Codable) - we never learn which apps
// they are, which is by design on iOS.
struct BlockList: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var name: String = ""
    var enabled: Bool = true

    // Encoded FamilyActivitySelection (apps + categories + web domains).
    var selectionData: Data = Data()

    // Optional weekly schedule. Empty ranges = always on while enabled.
    var scheduleEnabled: Bool = false
    var ranges: [TimeRange] = [TimeRange()]

    // 0 = no limit. With a limit the apps are allowed until the daily total is
    // reached, then shielded for the rest of the day (via DeviceActivity).
    var dailyLimitMinutes: Int = 0

    var selection: FamilyActivitySelection {
        get {
            (try? JSONDecoder().decode(FamilyActivitySelection.self, from: selectionData))
                ?? FamilyActivitySelection()
        }
        set { selectionData = (try? JSONEncoder().encode(newValue)) ?? Data() }
    }

    var isEmpty: Bool {
        let s = selection
        return s.applicationTokens.isEmpty && s.categoryTokens.isEmpty && s.webDomainTokens.isEmpty
    }

    func displayName() -> String { name.isEmpty ? "Untitled list" : name }
}

// One "block on these days, between these times" rule. A window that crosses
// midnight (10pm-7am) is handled by the monitor as two schedule intervals.
struct TimeRange: Codable, Equatable, Hashable {
    // 1 = Sunday ... 7 = Saturday (matches Calendar).
    var days: Set<Int> = [2, 3, 4, 5, 6] // weekdays
    var startHour: Int = 9
    var startMinute: Int = 0
    var endHour: Int = 17
    var endMinute: Int = 0
}
