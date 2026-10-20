import SwiftUI
import FamilyControls

struct SettingsView: View {
    @EnvironmentObject var store: BlockStore

    var body: some View {
        NavigationStack {
            Form {
                Section("Permissions") {
                    LabeledContent("Screen Time access") {
                        Text(AuthorizationCenter.shared.authorizationStatus == .approved ? "On" : "Off")
                            .foregroundStyle(AuthorizationCenter.shared.authorizationStatus == .approved ? .green : .secondary)
                    }
                }
                Section("About") {
                    LabeledContent("Blocking engine", value: "Apple Screen Time")
                    Text("No accounts, no ads. Blocking is enforced by iOS itself.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Not available on iPhone") {
                    Text("Some Android features can't exist on iOS: closing/killing apps or clearing the app switcher, blocking sites inside non-Safari browsers, split screen, and uninstall protection. See the README for details.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
        }
    }
}
