import DeviceActivity
import ManagedSettings
import Foundation

// Runs in the background when the system fires schedule interval boundaries and
// usage-limit thresholds. It reads the list id out of the activity name and
// shields / unshields that list's tokens.
final class DeviceActivityMonitorExtension: DeviceActivityMonitor {

    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        // Scheduled window opened -> shield this list.
        if let list = list(for: activity.rawValue) {
            ShieldController.shared.shield(list: list)
        }
    }

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        // Window closed -> rebuild shields from scratch (drops this list).
        ShieldController.shared.refresh()
    }

    override func eventDidReachThreshold(_ event: DeviceActivityEvent.Name,
                                         activity: DeviceActivityName) {
        super.eventDidReachThreshold(event, activity: activity)
        // Daily limit reached -> shield this list for the rest of the day.
        if let list = list(for: activity.rawValue) {
            ShieldController.shared.shield(list: list)
        }
    }

    // Activity names look like "win.<uuid>.<day>.<i>" or "limit.<uuid>".
    private func list(for activityName: String) -> BlockList? {
        let parts = activityName.split(separator: ".")
        guard parts.count >= 2, let uuid = UUID(uuidString: String(parts[1])) else { return nil }
        BlockStore.shared.load()
        return BlockStore.shared.lists.first { $0.id == uuid }
    }
}
