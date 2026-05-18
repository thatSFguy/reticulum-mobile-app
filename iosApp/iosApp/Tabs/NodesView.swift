// SPDX-License-Identifier: MIT
//
// Nodes tab — every observed destination, filterable + searchable,
// with per-row star (favorite) and pencil (set userLabel). Toolbar
// "+" opens the Add-by-hash dialog. Three panes: Nodes (the list),
// Graph (the adjacency view), and Map (geolocated destinations on a
// MapKit map — the iOS counterpart of Android's osmdroid MapBlock).
// Mirrors the Android NodesScreen minus the BLE + QR scanners.

import MapKit
import Shared
import SwiftUI

struct NodesView: View {
    @EnvironmentObject private var store: ReticulumStore

    enum Filter: String, CaseIterable, Identifiable {
        case messagable = "Messagable"
        case all        = "All"
        case telemetry  = "Telemetry"
        case favorites  = "Favorites"
        var id: String { rawValue }
    }

    /// Nodes ⇄ Graph ⇄ Map pane. Graph folded in from its former
    /// standalone tab to free a bottom-tab slot for RRC — matches
    /// Android, whose NodesScreen carries the same three panes.
    enum Pane { case nodes, graph, map }

    @State private var pane: Pane = .nodes
    @State private var filter: Filter = .messagable
    @State private var search: String = ""
    @State private var showAdd: Bool = false
    @State private var renameTarget: StoredDestination? = nil
    @State private var pendingDelete: StoredDestination? = nil

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Nodes ⇄ Graph pane switch — Graph folded in from its
                // former standalone tab to free a bottom-tab slot for RRC.
                Picker("View", selection: $pane) {
                    Text("Nodes").tag(Pane.nodes)
                    Text("Graph").tag(Pane.graph)
                    Text("Map").tag(Pane.map)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.top, 8)

                switch pane {
                case .nodes:
                    filterBar
                    searchField
                    List(filtered, id: \.id) { row in
                        NodeRow(
                            row: row,
                            onToggleFavorite: { fav in store.toggleFavorite(hash: row.hash, favorite: fav) },
                            onRequestRename: { renameTarget = row },
                            onOpenConversation: { store.openContact(hash: row.hash) }
                        )
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                pendingDelete = row
                            } label: { Label("Delete", systemImage: "trash") }
                        }
                    }
                    .listStyle(.plain)
                    .scrollDismissesKeyboard(.immediately)
                    .overlay {
                        if filtered.isEmpty {
                            ContentUnavailableView(
                                "No destinations",
                                systemImage: "antenna.radiowaves.left.and.right",
                                description: Text(emptyMessage)
                            )
                        }
                    }
                case .graph:
                    GraphView()
                case .map:
                    NodeMapView()
                }
            }
            .navigationTitle(paneTitle)
            .toolbar {
                if pane == .nodes {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { showAdd = true } label: { Image(systemName: "plus") }
                    }
                }
            }
            .sheet(isPresented: $showAdd) {
                AddDestinationSheet(
                    onAddManual: { hash, label in
                        store.addManualDestination(hashHex: hash, label: label)
                        showAdd = false
                    },
                    onApplyCard: { card in
                        store.applyIdentityCard(card)
                        showAdd = false
                    }
                )
            }
            .sheet(item: $renameTarget) { dest in
                RenameSheet(target: dest) { newLabel in
                    store.setUserLabel(hash: dest.hash, label: newLabel)
                    renameTarget = nil
                }
            }
            .alert(
                "Delete this destination?",
                isPresented: Binding(
                    get: { pendingDelete != nil },
                    set: { if !$0 { pendingDelete = nil } }
                ),
                presenting: pendingDelete
            ) { dest in
                Button("Delete", role: .destructive) {
                    store.deleteDestinationAndMessages(hash: dest.hash)
                    pendingDelete = nil
                }
                Button("Cancel", role: .cancel) { pendingDelete = nil }
            } message: { dest in
                let name = dest.effectiveDisplayName.isEmpty ? "(unnamed)" : dest.effectiveDisplayName
                Text("Removes \(name) and any message history. If they announce again later they'll reappear (without prior history).")
            }
        }
    }

    private var paneTitle: String {
        switch pane {
        case .nodes: return "Nodes"
        case .graph: return "Graph"
        case .map:   return "Map"
        }
    }

    // ---- Filtering -----------------------------------------------------

    private var filtered: [StoredDestination] {
        let byFilter: [StoredDestination] = store.allDestinations.filter { d in
            switch filter {
            case .messagable: return d.isMessagable || (d.publicKey.size == 0 && d.appName == nil)
            case .all:        return true
            case .telemetry:  return d.appName != "lxmf.delivery"
            case .favorites:  return d.favorite
            }
        }
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return byFilter }
        return byFilter.filter { d in
            d.effectiveDisplayName.lowercased().contains(q) ||
                d.displayName.lowercased().contains(q) ||
                (d.appLabel?.lowercased().contains(q) ?? false) ||
                (d.appName?.lowercased().contains(q) ?? false) ||
                d.hash.lowercased().contains(q)
        }
    }

    private var emptyMessage: String {
        if !search.isEmpty { return "Nothing matches “\(search)”." }
        switch filter {
        case .favorites:  return "Star a destination to bring it here."
        case .messagable: return "No messagable destinations seen yet — connect a transport on Settings."
        case .all:        return "No destinations seen yet — connect a transport on Settings."
        case .telemetry:  return "No non-LXMF nodes seen yet."
        }
    }

    // ---- Filter chips + search ----------------------------------------

    private var filterBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(Filter.allCases) { f in
                    let selected = filter == f
                    Button { filter = f } label: { Text(f.rawValue) }
                        .buttonStyle(.bordered)
                        .tint(selected ? Color.accentColor : Color.secondary)
                }
            }
            .padding(.horizontal)
            .padding(.top, 8)
        }
    }

    private var searchField: some View {
        HStack {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("Search by name, app, or hash", text: $search)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            if !search.isEmpty {
                Button { search = "" } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(8)
        .background(Color.secondary.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .padding(.horizontal)
        .padding(.vertical, 6)
    }
}

// ---- Row ----------------------------------------------------------------

private struct NodeRow: View {
    let row: StoredDestination
    let onToggleFavorite: (Bool) -> Void
    let onRequestRename: () -> Void
    let onOpenConversation: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(displayName)
                        .font(.body)
                    Text("\(row.appName ?? "unknown") · \(shortHash(row.hash))")
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .contentShape(Rectangle())
                .onTapGesture { if showStar { onOpenConversation() } }
                Spacer()
                Button { onRequestRename() } label: {
                    Image(systemName: row.userLabel?.isEmpty == false ? "pencil.circle.fill" : "pencil.circle")
                        .foregroundStyle(row.userLabel?.isEmpty == false ? Color.accentColor : .secondary)
                }
                .buttonStyle(.borderless)
                if showStar {
                    // (The explicit envelope Button that lived here
                    // in 1.0.41 was removed in 1.0.47 — the row's
                    // name area is already tap-to-open-conversation,
                    // and the extra icon was crowding the rename +
                    // favorite buttons on phones with narrower
                    // rows. Mirrors NodesScreen.kt.)
                    Button { onToggleFavorite(!row.favorite) } label: {
                        Image(systemName: row.favorite ? "star.fill" : "star")
                            .foregroundStyle(row.favorite ? Color.accentColor : .secondary)
                    }
                    .buttonStyle(.borderless)
                }
            }
            if !meta.isEmpty {
                Text(meta).font(.caption).foregroundStyle(metaTint)
            }
            // Telemetry sub-line — appears for non-LXMF rows that
            // carry parsed key=value telemetry (e.g. RLR repeater
            // beacons). Mirrors NodesScreen.kt:309-315 on Android.
            if let telemetry = row.telemetry, !telemetry.isEmpty {
                Text(telemetryLine(telemetry))
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
    }

    /// Render the telemetry map as `k1=v1 · k2=v2`. Sorted keys so
    /// the order is stable across rerenders (Kotlin's
    /// LinkedHashMap insertion-order would otherwise flicker).
    private func telemetryLine(_ telemetry: [String: String]) -> String {
        telemetry.keys.sorted()
            .compactMap { key in telemetry[key].map { "\(key)=\($0)" } }
            .joined(separator: " · ")
    }

    private var displayName: String {
        let name = row.effectiveDisplayName
        if !name.isEmpty { return name }
        return row.appLabel ?? "(unnamed)"
    }

    private var showStar: Bool {
        row.appName == "lxmf.delivery" || row.publicKey.size == 0
    }

    private var meta: String {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let ageMs = max(0, now - row.lastSeen)
        var parts: [String] = []
        if row.hopCount > 0 { parts.append("\(row.hopCount) hop\(row.hopCount == 1 ? "" : "s")") }
        if let r = row.rssi { parts.append("RSSI \(Int(truncating: r)) dBm") }
        if row.lastSeen > 0 { parts.append("seen \(formatAge(ageMs))") }
        if row.source != "announce" { parts.append("source=\(row.source)") }
        if !row.isMessagable && row.appName == "lxmf.delivery" { parts.append("waiting for announce") }
        if isStale(ageMs: ageMs) { parts.append("stale") }
        else if row.hopCount >= 4 { parts.append("far — link may be slow") }
        return parts.joined(separator: " · ")
    }

    private var metaTint: Color {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let ageMs = max(0, now - row.lastSeen)
        if isStale(ageMs: ageMs) { return .red }
        if row.hopCount >= 4 { return .orange }
        return .secondary
    }

    private func isStale(ageMs: Int64) -> Bool { row.lastSeen > 0 && ageMs > 30 * 60_000 }

    private func formatAge(_ ageMs: Int64) -> String {
        let s = ageMs / 1000
        if s < 60 { return "\(s)s ago" }
        if s < 3600 { return "\(s / 60)m ago" }
        if s < 86_400 { return "\(s / 3600)h ago" }
        return "\(s / 86_400)d ago"
    }
}

// ---- Add-by-hash sheet -------------------------------------------------

private struct AddDestinationSheet: View {
    @State private var hash: String = ""
    @State private var label: String = ""
    @State private var showScanner: Bool = false
    @Environment(\.dismiss) private var dismiss
    let onAddManual: (String, String) -> Void
    let onApplyCard: (IdentityCard.Payload) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("Destination hash") {
                    TextField("32 hex chars", text: $hash)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.body.monospaced())
                }
                Section("Nickname (optional)") {
                    TextField("e.g. Bob's Phone", text: $label)
                }
                Section {
                    Button {
                        showScanner = true
                    } label: {
                        Label("Scan QR", systemImage: "qrcode.viewfinder")
                    }
                }
                Section {
                    Text("Stored locally only — never sent on the wire. Manual entries can't be messaged until an announce arrives carrying the public key. QR scans of an IdentityCard register the public key immediately.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Add by hash")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        let cleaned = hash.lowercased().filter { $0.isHexDigit }
                        guard cleaned.count == 32 else { return }
                        onAddManual(cleaned, label)
                    }
                    .disabled(!hashLooksValid)
                }
            }
            .sheet(isPresented: $showScanner) {
                QrScannerSheet { payload in
                    switch payload {
                    case .bareHash(let h):
                        onAddManual(h, label)
                    case .identityCard(let card):
                        onApplyCard(card)
                    }
                }
            }
        }
    }

    private var hashLooksValid: Bool {
        hash.lowercased().filter { $0.isHexDigit }.count == 32
    }
}

// ---- Rename sheet -------------------------------------------------------

private struct RenameSheet: View {
    let target: StoredDestination
    let onSave: (String) -> Void

    @State private var draft: String = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Stored locally on this device only. Never sent on the wire.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    if !target.displayName.isEmpty {
                        Text("Announced: \(target.displayName)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    Text(target.hash)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
                Section("Nickname") {
                    TextField("Leave empty to clear", text: $draft)
                }
            }
            .navigationTitle("Set nickname")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onSave(draft) }
                }
            }
            .onAppear { draft = target.userLabel ?? "" }
        }
    }
}

// StoredDestination is the KMP type; SwiftUI's `.sheet(item:)` requires
// Identifiable. We extend it here rather than touching shared code so
// the Android side stays untouched.
extension StoredDestination: Swift.Identifiable {
    public var id: String { hash }
}

// MARK: - Map pane

/// MapKit map of every destination that has announced a location
/// (lat/lon populated — RLR telemetry beacons, Sideband location
/// shares, …). The iOS counterpart of Android's osmdroid `MapBlock`.
/// Tapping a marker selects it and raises a bottom info card with a
/// Message shortcut — same affordance as the osmdroid info window.
private struct NodeMapView: View {
    @EnvironmentObject private var store: ReticulumStore
    @State private var selectedHash: String?

    /// Destinations carrying a coordinate. Recomputed on every store
    /// emission, so a fresh telemetry fix moves the marker live.
    private var located: [StoredDestination] {
        store.allDestinations.filter { $0.lat != nil && $0.lon != nil }
    }

    var body: some View {
        if located.isEmpty {
            ContentUnavailableView(
                "No mapped nodes",
                systemImage: "map",
                description: Text("Destinations appear here once they announce a location — e.g. an RLR telemetry beacon or a Sideband location share.")
            )
        } else {
            Map(initialPosition: .automatic, selection: $selectedHash) {
                ForEach(located, id: \.id) { node in
                    Marker(
                        markerLabel(node),
                        systemImage: "antenna.radiowaves.left.and.right",
                        coordinate: coordinate(node)
                    )
                    .tag(node.hash as String)
                }
            }
            .mapStyle(.standard)
            .overlay(alignment: .bottom) {
                if let sel = selectedHash,
                   let node = located.first(where: { ($0.hash as String) == sel }) {
                    NodeMapCard(
                        node: node,
                        onMessage: { store.openContact(hash: node.hash) },
                        onDismiss: { selectedHash = nil }
                    )
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.2), value: selectedHash)
        }
    }

    private func coordinate(_ node: StoredDestination) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(
            latitude: node.lat?.doubleValue ?? 0,
            longitude: node.lon?.doubleValue ?? 0
        )
    }

    private func markerLabel(_ node: StoredDestination) -> String {
        let name = node.effectiveDisplayName
        if !name.isEmpty { return name }
        if let label = node.appLabel, !label.isEmpty { return label }
        return String((node.hash as String).prefix(8))
    }
}

/// Bottom info card for the tapped marker — name, hash, a meta line
/// (hops / RSSI / coordinate), and a Message shortcut for messagable
/// destinations. The iOS counterpart of the osmdroid info window.
private struct NodeMapCard: View {
    let node: StoredDestination
    let onMessage: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title)
                    .font(.headline)
                    .lineLimit(1)
                Spacer()
                Button { onDismiss() } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
            Text(node.hash)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
            if !meta.isEmpty {
                Text(meta)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            if node.isMessagable {
                Button {
                    onMessage()
                } label: {
                    Label("Message", systemImage: "bubble.left")
                        .font(.subheadline)
                }
                .buttonStyle(.bordered)
                .padding(.top, 2)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .shadow(radius: 4)
    }

    private var title: String {
        let name = node.effectiveDisplayName
        if !name.isEmpty { return name }
        if let label = node.appLabel, !label.isEmpty { return label }
        return "(unnamed)"
    }

    private var meta: String {
        var parts: [String] = []
        if node.hopCount > 0 {
            parts.append("\(node.hopCount) hop\(node.hopCount == 1 ? "" : "s")")
        }
        if let r = node.rssi {
            parts.append("RSSI \(Int(truncating: r)) dBm")
        }
        if let lat = node.lat?.doubleValue, let lon = node.lon?.doubleValue {
            parts.append(String(format: "%.5f, %.5f", lat, lon))
        }
        return parts.joined(separator: " · ")
    }
}
