import SwiftUI

struct StrictModeView: View {
    @EnvironmentObject var strict: StrictMode
    @EnvironmentObject var store: BlockStore
    @State private var unlockAt: Date?
    @State private var now = Date()
    @State private var weeklyEnabled = false
    @State private var weeklyDay = 0
    @State private var weeklyStart = Date()
    @State private var weeklyMinutes = 1
    @State private var sealAlert = false
    private let dayNames = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
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
                    .disabled(strict.active || weeklyEnabled)
                    if weeklyEnabled {
                        Text("Ignored while a weekly window is on — the window is the unlock.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }

                Section("Weekly unlock window") {
                    Toggle("Only unlock during one weekly window", isOn: $weeklyEnabled)
                        .disabled(strict.active && strict.weeklyEnabled && !strict.withinWindow())
                    if weeklyEnabled {
                        Picker("Day", selection: $weeklyDay) {
                            ForEach(0..<7, id: \.self) { d in Text(dayNames[d]).tag(d) }
                        }
                        DatePicker("Start time", selection: $weeklyStart, displayedComponents: .hourAndMinute)
                        Stepper(value: $weeklyMinutes, in: 1...60) { Text("Length: \(weeklyMinutes) min") }
                    }
                    Text(weeklyEnabled
                         ? "Strict Mode can only be turned off \(dayNames[weeklyDay]) at the time above. It's your guaranteed way out, so your schedules may cover the whole day."
                         : "Off — Strict Mode uses the unlock wait instead.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Button("Save window") { saveWindow() }
                        .disabled(strict.active && strict.weeklyEnabled && !strict.withinWindow())
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
            .onAppear { loadWindow() }
            .alert("Can't turn the window off", isPresented: $sealAlert) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("One of your schedules covers the whole day with no free minute, and this window is the only time you could unlock. End that range a minute early (for example 00:00–23:58) first, or leave the window on.")
            }
        }
    }

    private func loadWindow() {
        weeklyEnabled = strict.weeklyEnabled
        weeklyDay = strict.weeklyDay
        weeklyMinutes = strict.weeklyMinutes
        let p = strict.weeklyStart.split(separator: ":")
        var c = DateComponents()
        c.hour = Int(p.first ?? "3") ?? 3
        c.minute = p.count > 1 ? (Int(p[1]) ?? 0) : 0
        weeklyStart = Calendar.current.date(from: c) ?? Date()
    }

    private func saveWindow() {
        // Don't let turning the window OFF seal a 24/7 schedule with no free minute.
        if !weeklyEnabled {
            var ranges: [TimeRange] = []
            for l in store.lists where l.scheduleEnabled { ranges += l.ranges }
            if !ranges.isEmpty && !ScheduleGuard.leavesWeeklyGap(ranges) {
                weeklyEnabled = true
                sealAlert = true
                return
            }
        }
        let c = Calendar.current.dateComponents([.hour, .minute], from: weeklyStart)
        strict.weeklyStart = String(format: "%02d:%02d", c.hour ?? 3, c.minute ?? 0)
        strict.weeklyDay = weeklyDay
        strict.weeklyMinutes = weeklyMinutes
        strict.weeklyEnabled = weeklyEnabled
        strict.objectWillChange.send()
    }
}
