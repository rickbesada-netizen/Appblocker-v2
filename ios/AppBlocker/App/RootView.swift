import SwiftUI
import FamilyControls

struct RootView: View {
    @State private var authorized = AuthorizationCenter.shared.authorizationStatus == .approved

    var body: some View {
        Group {
            if authorized {
                TabView {
                    BlockingView()
                        .tabItem { Label("Blocking", systemImage: "shield.lefthalf.filled") }
                    StrictModeView()
                        .tabItem { Label("Strict", systemImage: "lock.fill") }
                    InsightsView()
                        .tabItem { Label("Insights", systemImage: "chart.bar.fill") }
                    SettingsView()
                        .tabItem { Label("Settings", systemImage: "gearshape.fill") }
                }
                .tint(.blue)
            } else {
                AuthGateView(authorized: $authorized)
            }
        }
        .onAppear {
            authorized = AuthorizationCenter.shared.authorizationStatus == .approved
        }
    }
}

struct AuthGateView: View {
    @Binding var authorized: Bool

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 64)).foregroundStyle(.blue)
            Text("Screen Time access needed")
                .font(.title2.bold())
            Text("AppBlocker uses Apple's Screen Time to block apps and websites. Grant access to continue.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 32)
            Button("Grant access") {
                Task {
                    try? await AuthorizationCenter.shared.requestAuthorization(for: .individual)
                    authorized = AuthorizationCenter.shared.authorizationStatus == .approved
                }
            }
            .buttonStyle(.borderedProminent)
        }
    }
}
