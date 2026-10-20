import SwiftUI
import FamilyControls

struct BlockListEditView: View {
    @EnvironmentObject var store: BlockStore
    @EnvironmentObject var strict: StrictMode
    @Environment(\.dismiss) private var dismiss

    @State private var draft: BlockList
    @State private var pickerShown = false
    @State private var selection = FamilyActivitySelection()
    @State private var original: BlockList
    @State private var scheduleAlert: String?
    @State private var confirmDiscard = false

    init(list: BlockList) {
        _draft = State(initialValue: list)
        _original = State(initialValue: list)
        _selection = State(initialValue: list.selection)
    }

    private var addOnly: Bool { strict.active }
    private var hasChanges: Bool { draft != original || selection != original.selection }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("e.g. Social media", text: $draft.name)
                }

                Section("Apps & websites") {
                    Button {
                        pickerShown = true
                    } label: {
                        let count = selection.applicationTokens.count
                            + selection.categoryTokens.count
                            + selection.webDomainTokens.count
                        Label(count == 0 ? "Choose apps & sites" : "\(count) selected",
                              systemImage: "square.grid.2x2")
                    }
                    Text("Apps and websites are picked through Apple's private picker. You (and this app) never see which ones - iOS keeps them anonymous.")
                        .font(.footnote).foregroundStyle(.secondary)
                }

                Section("Schedule") {
                    Toggle("Only block on a schedule", isOn: $draft.scheduleEnabled)
                    if draft.scheduleEnabled {
                        ForEach(draft.ranges.indices, id: \.self) { i in
                            RangeEditor(range: $draft.ranges[i])
                        }
                        Button {
                            draft.ranges.append(TimeRange())
                        } label: { Label("Add another time", systemImage: "plus") }
                    }
                }

                Section("Daily limit") {
                    Stepper(value: $draft.dailyLimitMinutes, in: 0...600, step: 15) {
                        Text(draft.dailyLimitMinutes == 0
                             ? "No limit"
                             : "\(draft.dailyLimitMinutes) min per day")
                    }
                    Text("With a limit, these apps work until the daily total is reached, then they're blocked until tomorrow.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(draft.name.isEmpty ? "New list" : "Edit list")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { if hasChanges { confirmDiscard = true } else { dismiss() } }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                }
            }
            .familyActivityPicker(isPresented: $pickerShown, selection: $selection)
            // Unsaved-changes guard: a swipe-down can't silently drop edits.
            .interactiveDismissDisabled(hasChanges)
            .alert("Leave a free minute", isPresented: Binding(
                get: { scheduleAlert != nil }, set: { if !$0 { scheduleAlert = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(scheduleAlert ?? "") }
            .confirmationDialog("You have unsaved changes", isPresented: $confirmDiscard, titleVisibility: .visible) {
                Button("Save") { save() }
                Button("Discard changes", role: .destructive) { dismiss() }
                Button("Keep editing", role: .cancel) {}
            }
        }
    }

    private func save() {
        draft.selection = selection
        if draft.isEmpty {
            return
        }
        // Guaranteed free window: never let a schedule seal the whole week.
        if let err = ScheduleGuard.scheduleError(draft.scheduleEnabled, draft.ranges) {
            scheduleAlert = err
            return
        }
        store.upsert(draft)
        dismiss()
    }
}

struct RangeEditor: View {
    @Binding var range: TimeRange
    private let dayLetters = [(1,"S"),(2,"M"),(3,"T"),(4,"W"),(5,"T"),(6,"F"),(7,"S")]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                ForEach(dayLetters, id: \.0) { (day, letter) in
                    let on = range.days.contains(day)
                    Text(letter)
                        .frame(maxWidth: .infinity, minHeight: 34)
                        .background(on ? Color.blue : Color.clear)
                        .clipShape(Circle())
                        .overlay(Circle().stroke(Color.secondary.opacity(on ? 0 : 0.4)))
                        .foregroundStyle(on ? .white : .secondary)
                        .onTapGesture {
                            if on { range.days.remove(day) } else { range.days.insert(day) }
                        }
                }
            }
            HStack {
                timeField("From", hour: $range.startHour, minute: $range.startMinute)
                timeField("To", hour: $range.endHour, minute: $range.endMinute)
            }
        }
    }

    private func timeField(_ label: String, hour: Binding<Int>, minute: Binding<Int>) -> some View {
        DatePicker(
            label,
            selection: Binding(
                get: {
                    Calendar.current.date(from: DateComponents(hour: hour.wrappedValue, minute: minute.wrappedValue)) ?? Date()
                },
                set: { newDate in
                    let c = Calendar.current.dateComponents([.hour, .minute], from: newDate)
                    hour.wrappedValue = c.hour ?? 0
                    minute.wrappedValue = c.minute ?? 0
                }
            ),
            displayedComponents: .hourAndMinute
        )
        .labelsHidden()
    }
}
