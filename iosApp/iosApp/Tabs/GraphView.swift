// SPDX-License-Identifier: MIT
//
// Graph tab — Phase 3 ships a hop-count grouped list rather than the
// force-directed Canvas the Android side renders. The Android
// GraphTopology builder lives in `androidApp/.../ui/graph/`; a real
// iOS Canvas renderer + a commonMain extraction of the topology code
// is Phase 4 work.
//
// What you get for now: every observed destination grouped by its
// hop count (0 = directly attached, 1+ = behind that many transit
// hops), sorted within each group by lastSeen DESC. Same data the
// graph would render, just as a list.

import Shared
import SwiftUI

struct GraphView: View {
    @EnvironmentObject private var store: ReticulumStore

    var body: some View {
        NavigationStack {
            List {
                if store.allDestinations.isEmpty {
                    Section {
                        Text("No destinations seen yet — connect a transport on Settings.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                ForEach(grouped, id: \.0) { entry in
                    let hops = entry.0
                    let dests = entry.1
                    Section(headerText(forHops: hops)) {
                        ForEach(dests, id: \.hash) { d in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(displayName(d))
                                    .font(.body)
                                Text("\(d.appName ?? "unknown") · \(d.hash)")
                                    .font(.caption.monospaced())
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                                if let r = d.rssi {
                                    Text("RSSI \(Int(truncating: r)) dBm")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Graph")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Text("\(store.allDestinations.count) nodes")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    /// Group destinations by hop count, ascending; within each group
    /// sort by lastSeen DESC. Tuple-list keeps a stable identity for
    /// SwiftUI's ForEach while preserving order.
    private var grouped: [(Int32, [StoredDestination])] {
        let buckets = Dictionary(grouping: store.allDestinations, by: { Int32($0.hopCount) })
        return buckets
            .map { (key, value) in (key, value.sorted { $0.lastSeen > $1.lastSeen }) }
            .sorted { $0.0 < $1.0 }
    }

    private func headerText(forHops hops: Int32) -> String {
        if hops == 0 { return "Direct (0 hops)" }
        if hops == 1 { return "1 hop" }
        return "\(hops) hops"
    }

    private func displayName(_ d: StoredDestination) -> String {
        let name = d.effectiveDisplayName
        if !name.isEmpty { return name }
        return d.appLabel ?? "(unnamed)"
    }
}
