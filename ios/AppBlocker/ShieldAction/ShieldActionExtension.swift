import ManagedSettings
import UIKit

// Handles taps on the shield's buttons. We simply close the shield (the app
// stays blocked); iOS returns the user to the Home Screen.
final class ShieldActionExtension: ShieldActionDelegate {
    override func handle(action: ShieldAction, for application: ApplicationToken,
                         completionHandler: @escaping (ShieldActionResponse) -> Void) {
        completionHandler(action == .primaryButtonPressed ? .close : .none)
    }
    override func handle(action: ShieldAction, for webDomain: WebDomainToken,
                         completionHandler: @escaping (ShieldActionResponse) -> Void) {
        completionHandler(action == .primaryButtonPressed ? .close : .none)
    }
    override func handle(action: ShieldAction, for category: ActivityCategoryToken,
                         completionHandler: @escaping (ShieldActionResponse) -> Void) {
        completionHandler(action == .primaryButtonPressed ? .close : .none)
    }
}
