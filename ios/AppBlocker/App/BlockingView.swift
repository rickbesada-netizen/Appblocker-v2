import SwiftUI

struct BlockingView: View {
    @EnvironmentObject var store: BlockStore
    @EnvironmentObject var strict: StrictMode
    @State private var editing: BlockList?
    @State private var showingNew = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("Adult content filter", isOn: Binding(
                        get: { store.adultFilterEnabled },
                        set: { newValue in
                            if !newValue && strict.active { return } // stricter-only
                            store.setAdultFilter(newValue)
                        }
                    ))
                    Text("Blocks known adult websites in Safari using Apple's built-in filter.")
                        .font(.footnote).foregroundStyle(.secondary)
                } header: { Text("Always on") }

                Section {
                    ForEach(store.lists) { list in
                        Button {
                            if strict.active { return } // add-only handled in editor
                            editing = list
                        } label: {
                            row(for: list)
                        }
                        .swipeActions {
                            if !strict.active {
                                Button(role: .destructive) { store.delete(list) } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                        }
                    }
                    Button {
                        showingNew = true
                    } label: {
                        Label("New block list", systemImage: "plus.circle.fill")
                    }
                } header: { Text("Block lists") }
            }
            .navigationTitle("AppBlocker")
            .sheet(item: $editing) { list in
                BlockListEditView(list: list)
            }
            .sheet(isPresented: $showingNew) {
                BlockListEditView(list: BlockList())
            }
        }
    }

    @ViewBuilder private func row(for list: BlockList) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(list.displayName()).font(.headline)
                Text(summary(list)).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            Toggle("", isOn: Binding(
                get: { list.enabled },
                set: { newValue in
                    if !newValue && strict.active { return }
                    store.setEnabled(list, newValue)
                }
            )).labelsHidden()
        }
    }

    private func summary(_ list: BlockList) -> String {
        let s = list.selection
        let apps = s.applicationTokens.count + s.categoryTokens.count
        var parts = ["\(apps) app\(apps == 1 ? "" : "s")"]
        if !s.webDomainTokens.isEmpty { parts.append("\(s.webDomainTokens.count) sites") }
        if list.scheduleEnabled { parts.append("scheduled") }
        if list.dailyLimitMinutes > 0 { parts.append("\(list.dailyLimitMinutes)m/day") }
        return parts.joined(separator: " · ")
    }
}
