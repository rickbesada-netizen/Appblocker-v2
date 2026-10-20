import Foundation
import ManagedSettings
import FamilyControls

// Applies / clears the actual blocks using ManagedSettings "shields". This is
// iOS's only sanctioned blocking mechanism: you hand it opaque tokens and it
// shows Apple's shield screen when those apps / sites are opened.
final class ShieldController {
    static let shared = ShieldController()
    private let store = ManagedSettingsStore(named: .appBlocker)

    private init() {}

    // Rebuild the shield from every list that should be blocking *right now*.
    // Lists with a schedule or a daily limit are handled by the DeviceActivity
    // monitor instead (it shields/unshields them as intervals and thresholds
    // fire), so here we only apply lists that are "always on".
    func refresh() {
        let s = BlockStore.shared

        var apps = Set<ApplicationToken>()
        var categories = Set<ActivityCategoryToken>()
        var webDomains = Set<WebDomainToken>()

        for list in s.lists where list.enabled && !list.isEmpty {
            // Scheduled / limited lists are applied by the monitor extension.
            if list.scheduleEnabled || list.dailyLimitMinutes > 0 { continue }
            let sel = list.selection
            apps.formUnion(sel.applicationTokens)
            categories.formUnion(sel.categoryTokens)
            webDomains.formUnion(sel.webDomainTokens)
        }

        store.shield.applications = apps.isEmpty ? nil : apps
        store.shield.applicationCategories = categories.isEmpty
            ? nil : .specific(categories)
        store.shield.webDomains = webDomains.isEmpty ? nil : webDomains

        // Adult-content filter: Apple's built-in "auto" web filter (the same
        // engine as Screen Time's "Limit Adult Websites"). Blocks known adult
        // sites in Safari and blocks unrated apps' web content.
        if s.adultFilterEnabled {
            store.webContent.blockedByFilter = .auto()
        } else {
            store.webContent.blockedByFilter = nil
        }
    }

    // Used by the monitor extension to shield a specific list's tokens.
    func shield(list: BlockList) {
        let sel = list.selection
        var apps = store.shield.applications ?? []
        apps.formUnion(sel.applicationTokens)
        store.shield.applications = apps

        var cats: Set<ActivityCategoryToken> = []
        if case let .specific(existing) = store.shield.applicationCategories {
            cats = existing
        }
        cats.formUnion(sel.categoryTokens)
        if !cats.isEmpty { store.shield.applicationCategories = .specific(cats) }

        var web = store.shield.webDomains ?? []
        web.formUnion(sel.webDomainTokens)
        if !web.isEmpty { store.shield.webDomains = web }
    }

    func clearAll() {
        store.shield.applications = nil
        store.shield.applicationCategories = nil
        store.shield.webDomains = nil
        store.webContent.blockedByFilter = nil
    }
}
