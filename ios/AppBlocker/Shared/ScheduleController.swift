import Foundation
import DeviceActivity
import FamilyControls

// Sets up the DeviceActivity monitoring that drives schedules and daily limits.
// The system wakes our DeviceActivityMonitor extension at interval start/end
// and when a usage threshold is reached, and the extension applies/removes the
// shields accordingly.
final class ScheduleController {
    static let shared = ScheduleController()
    private let center = DeviceActivityCenter()

    private init() {}

    func reschedule() {
        center.stopMonitoring()

        for list in BlockStore.shared.lists where list.enabled && !list.isEmpty {
            if list.scheduleEnabled {
                scheduleWindows(for: list)
            } else if list.dailyLimitMinutes > 0 {
                scheduleDailyLimit(for: list)
            }
        }
    }

    // One monitoring activity per (list, weekday, range). The monitor extension
    // reads the list id from the activity name to know what to shield.
    private func scheduleWindows(for list: BlockList) {
        for (ri, range) in list.ranges.enumerated() {
            for day in range.days {
                let name = DeviceActivityName("win.\(list.id.uuidString).\(day).\(ri)")
                let schedule = DeviceActivitySchedule(
                    intervalStart: DateComponents(hour: range.startHour, minute: range.startMinute, weekday: day),
                    intervalEnd: DateComponents(hour: range.endHour, minute: range.endMinute, weekday: day),
                    repeats: true
                )
                try? center.startMonitoring(name, during: schedule)
            }
        }
    }

    // A daily-limit list is monitored all day; a threshold event fires once the
    // apps have been used for the limit, and the extension shields them.
    private func scheduleDailyLimit(for list: BlockList) {
        let name = DeviceActivityName("limit.\(list.id.uuidString)")
        let schedule = DeviceActivitySchedule(
            intervalStart: DateComponents(hour: 0, minute: 0),
            intervalEnd: DateComponents(hour: 23, minute: 59),
            repeats: true
        )
        let sel = list.selection
        let event = DeviceActivityEvent(
            applications: sel.applicationTokens,
            categories: sel.categoryTokens,
            webDomains: sel.webDomainTokens,
            threshold: DateComponents(minute: list.dailyLimitMinutes)
        )
        let eventName = DeviceActivityEvent.Name("reached.\(list.id.uuidString)")
        try? center.startMonitoring(name, during: schedule, events: [eventName: event])
    }
}
