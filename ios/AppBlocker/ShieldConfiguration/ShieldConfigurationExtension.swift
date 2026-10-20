import ManagedSettings
import ManagedSettingsUI
import UIKit

// Customises the block screen iOS shows over a shielded app or website. This is
// the only "block screen" customisation Apple allows: an icon, title, subtitle
// and up to two buttons - not a full custom overlay like on Android.
final class ShieldConfigurationExtension: ShieldConfigurationDataSource {

    private func config(_ subtitle: String) -> ShieldConfiguration {
        ShieldConfiguration(
            backgroundBlurStyle: .systemMaterialDark,
            backgroundColor: UIColor(red: 0.04, green: 0.04, blue: 0.07, alpha: 1),
            icon: UIImage(systemName: "lock.shield.fill"),
            title: ShieldConfiguration.Label(text: "Blocked", color: .white),
            subtitle: ShieldConfiguration.Label(text: subtitle, color: .lightGray),
            primaryButtonLabel: ShieldConfiguration.Label(text: "OK", color: .white),
            primaryButtonBackgroundColor: UIColor(red: 0.24, green: 0.48, blue: 0.98, alpha: 1)
        )
    }

    override func configuration(shielding application: Application) -> ShieldConfiguration {
        config("This app is blocked by AppBlocker.")
    }

    override func configuration(shielding application: Application,
                                in category: ActivityCategory) -> ShieldConfiguration {
        config("This category is blocked by AppBlocker.")
    }

    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration {
        config("This website is blocked by AppBlocker.")
    }

    override func configuration(shielding webDomain: WebDomain,
                                in category: ActivityCategory) -> ShieldConfiguration {
        config("This website is blocked by AppBlocker.")
    }
}
