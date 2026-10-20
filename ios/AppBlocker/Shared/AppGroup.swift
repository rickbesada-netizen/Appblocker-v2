import Foundation
import ManagedSettings

// Shared identifiers used by the app and its extensions. Replace the group id
// and store name with your own once you create the App Group in Xcode.
enum AppGroup {
    // Must match the App Group capability added to the app + all 3 extensions.
    static let id = "group.com.yourcompany.appblocker"

    static var defaults: UserDefaults {
        UserDefaults(suiteName: id) ?? .standard
    }
}

extension ManagedSettingsStore.Name {
    // One named store, shared across the app and extensions via the App Group.
    static let appBlocker = Self("AppBlockerStore")
}
