import SwiftUI
import DeviceActivity

// On iOS, per-app usage numbers can only be shown inside Apple's own
// DeviceActivityReport view (a privacy-preserving extension) - the app itself
// still never sees the raw figures. This hosts that report.
struct InsightsView: View {
    @State private var context = DeviceActivityReport.Context("Total Activity")
    @State private var filter = DeviceActivityFilter(
        segment: .daily(during: Calendar.current.dateInterval(of: .day, for: .now) ?? .init(start: .now, duration: 86400)),
        users: .all,
        devices: .init([.iPhone])
    )

    var body: some View {
        NavigationStack {
            DeviceActivityReport(context, filter: filter)
                .navigationTitle("Insights")
        }
    }
}
