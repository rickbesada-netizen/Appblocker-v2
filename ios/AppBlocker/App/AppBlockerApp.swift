import SwiftUI
import FamilyControls

@main
struct AppBlockerApp: App {
    @StateObject private var store = BlockStore.shared
    @StateObject private var strict = StrictMode.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(strict)
                .preferredColorScheme(.dark)
                .task { await requestAuthIfNeeded() }
        }
    }

    // Family Controls needs a one-time authorization from the user.
    private func requestAuthIfNeeded() async {
        do {
            try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
            ShieldController.shared.refresh()
            ScheduleController.shared.reschedule()
        } catch {
            // User declined or unavailable - the UI shows a prompt to enable it.
        }
    }
}
