import Foundation
import Combine

// Persists all block lists (and the adult-filter / strict-mode flags) in the
// shared App Group so the extensions can read them too.
final class BlockStore: ObservableObject {
    static let shared = BlockStore()

    private let listsKey = "block_lists"
    private let adultKey = "adult_filter_enabled"

    @Published var lists: [BlockList] = []
    @Published var adultFilterEnabled: Bool = false

    private init() {
        load()
    }

    func load() {
        let d = AppGroup.defaults
        if let data = d.data(forKey: listsKey),
           let decoded = try? JSONDecoder().decode([BlockList].self, from: data) {
            lists = decoded
        }
        adultFilterEnabled = d.bool(forKey: adultKey)
    }

    func save() {
        let d = AppGroup.defaults
        if let data = try? JSONEncoder().encode(lists) {
            d.set(data, forKey: listsKey)
        }
        d.set(adultFilterEnabled, forKey: adultKey)
    }

    func upsert(_ list: BlockList) {
        if let i = lists.firstIndex(where: { $0.id == list.id }) {
            lists[i] = list
        } else {
            lists.append(list)
        }
        save()
        ShieldController.shared.refresh()
        ScheduleController.shared.reschedule()
    }

    func delete(_ list: BlockList) {
        lists.removeAll { $0.id == list.id }
        save()
        ShieldController.shared.refresh()
        ScheduleController.shared.reschedule()
    }

    func setEnabled(_ list: BlockList, _ enabled: Bool) {
        guard let i = lists.firstIndex(where: { $0.id == list.id }) else { return }
        lists[i].enabled = enabled
        save()
        ShieldController.shared.refresh()
        ScheduleController.shared.reschedule()
    }

    func setAdultFilter(_ on: Bool) {
        adultFilterEnabled = on
        save()
        ShieldController.shared.refresh()
    }
}
