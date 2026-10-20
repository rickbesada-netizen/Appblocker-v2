import SwiftUI

struct StrictModeView: View {
    @EnvironmentObject var strict: StrictMode
    @State private var unlockAt: Date?
    @State private var now = Date()
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Strict Mode", isOn: Binding(
                        get: { strict.active },
                        set: { newValue in
                            if newValue { strict.activate() }
                            else { unlockAt = strict.requestDeactivate() }
                        }
                    ))
                    Text(strict.active
                         ? "On - lists can't be edited or turned off from here."
                         : "Off - turn on to lock your blocking.")
                        .font(.footnote).foregroundStyle(.secondary)
                }

                Section("Unlock wait") {
                    Stepper(value: Binding(
                        get: { strict.waitMinutes },
                        set: { strict.waitMinutes = $0 }
                    ), in: 0...240, step: 5) {
                        Text(strict.waitMinutes == 0
                             ? "Off immediately"
                             : "Wait \(strict.waitMinutes) min to turn off")
                    }
                    .disabled(strict.active)
                }

                if strict.active, let until = unlockAt, until > now {
                    Section {
                        Text("Unlocks in \(Int(until.timeIntervalSince(now)) / 60)m \(Int(until.timeIntervalSince(now)) % 60)s")
                            .font(.title3.monospacedDigit().bold())
                            .foregroundStyle(.blue)
                        Button("Turn off now (if the wait is over)") {
                            unlockAt = strict.requestDeactivate()
                        }
                    }
                }
            }
            .navigationTitle("Strict Mode")
            .onReceive(tick) { now = $0 }
        }
    }
}
