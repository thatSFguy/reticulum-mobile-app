// SPDX-License-Identifier: MIT
//
// Nodes tab — the raw mesh-discovery view: every observed destination,
// filterable + searchable. After the UI redesign (docs/REDESIGN.md §6)
// the header is a single decluttered row — the Nodes/Graph/Map pane
// switch, a search icon that expands to a field, and an overflow menu
// carrying the rare Add actions plus the filter presets. Each row is
// name-led with a round per-type avatar; tapping a row opens the shared
// destination detail sheet rather than firing an inline action.

import MapKit
import Shared
import SwiftUI

struct NodesView: View {
    @EnvironmentObject private var store: ReticulumStore
    /// Drives the "Open in Relay Chat" detail-sheet action + the RRC
    /// filter preset; both hidden when the experimental feature is off.
    @AppStorage("experimental.rrc") private var experimentalRrc: Bool = false

    enum Filter: String, CaseIterable, Identifiable, Hashable {
        case contacts   = "Contacts"
        case messagable = "Messagable"
        case all        = "All"
        case telemetry  = "Telemetry"
        case rrc        = "RRC"
        var id: String { rawValue }
    }

    /// Nodes ⇄ Graph ⇄ Map pane. Graph + Map are pane switches inside
    /// the Nodes tab, not bottom-bar tabs (docs/REDESIGN.md §5).
    enum Pane { case nodes, graph, map }

    @State private var pane: Pane = .nodes
    @State private var filter: Filter = .messagable
    @State private var search: String = ""
    @State private var searchActive: Bool = false
    @State private var showAdd: Bool = false
    @State private var showScanner: Bool = false
    @State private var detailRow: StoredDestination? = nil
    @State private var renameTarget: StoredDestination? = nil
    @State private var pendingDelete: StoredDestination? = nil

    private var availableFilters: [Filter] {
        experimentalRrc ? Filter.allCases : Filter.allCases.filter { $0 != .rrc }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                header
                switch pane {
                case .nodes:
                    if searchActive { searchField }
                    Divider()
                    nodeList
                case .graph:
                    GraphView()
                case .map:
                    NodeMapView()
                }
            }
            .navigationTitle(paneTitle)
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
            .sheet(isPresented: $showScanner) {
                QrScannerSheet { payload in
                    switch payload {
                    case .bareHash(let h):
                        store.addManualDestination(hashHex: h, label: "")
                    case .identityCard(let card):
                        store.applyIdentityCard(card)
                    }
                }
            }
            .sheet(item: $detailRow) { dest in
                DestinationDetailSheet(
                    dest: dest,
                    onMessage: { hash in
                        detailRow = nil
                        store.openContact(hash: hash)
                    },
                    onOpenAsRrcHub: experimentalRrc ? { d in
                        detailRow = nil
                        // Idempotent upsert — if the hub is already
                        // in the rrc_hubs table this is a no-op merge.
                        // Without it, freshly-discovered hubs from the
                        // Nodes list have no StoredRrcHub row yet and
                        // the navigationDestination lookup in
                        // RoomsView would render "Hub not found".
                        store.addRrcHub(
                            destHash: d.hash,
                            displayName: d.effectiveDisplayName.isEmpty
                                ? (d.appLabel ?? "RRC hub") : d.effectiveDisplayName,
                            nick: nil
                        )
                        // Fire the deep-link event — ContentView
                        // switches the tab to Rooms and RoomsView
                        // pushes the hub onto its NavigationStack.
                        // Pre-fix this was missing, so the button
                        // silently added a row to the Rooms tab list
                        // without taking the user there — tester
                        // report: "Open in RRC button didn't work for
                        // him from the slide out".
                        store.openRrcHub(hash: d.hash)
                    } : nil,
                    onRename: { d in
                        detailRow = nil
                        presentAfterDismiss { renameTarget = d }
                    },
                    onToggleFavorite: { hash, fav in
                        detailRow = nil
                        store.toggleFavorite(hash: hash, favorite: fav)
                    },
                    onDelete: { d in
                        detailRow = nil
                        presentAfterDismiss { pendingDelete = d }
                    }
                )
            }
            .sheet(item: $renameTarget) { dest in
                NicknameEditSheet(target: dest) { newLabel in
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
            // SPEC §4.5 destHash↔publicKey binding refusal from
            // engine.applyIdentityCard (security fix shipped in
            // android-v1.2.18; first iOS release with it is
            // ios-v1.0.81). Pre-v1.0.81 the rejection only populated
            // lastConnectError, which is rendered solely in Settings →
            // TCP — invisible to a user scanning a QR from this tab.
            .alert(
                "QR import rejected",
                isPresented: Binding(
                    get: { store.lastQrImportError != nil },
                    set: { if !$0 { store.clearQrImportError() } }
                ),
                presenting: store.lastQrImportError
            ) { _ in
                Button("OK", role: .cancel) { store.clearQrImportError() }
            } message: { msg in
                Text(msg)
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

    // ---- Header — one decluttered row --------------------------------

    private var header: some View {
        HStack(spacing: 8) {
            Picker("View", selection: $pane) {
                Text("Nodes").tag(Pane.nodes)
                Text("Graph").tag(Pane.graph)
                Text("Map").tag(Pane.map)
            }
            .pickerStyle(.segmented)

            if pane == .nodes {
                Button {
                    searchActive.toggle()
                    if !searchActive { search = "" }
                } label: {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(searchActive || !search.isEmpty
                                         ? Color.accentColor : Color.secondary)
                }

                // "+" — platform-standard "add" affordance, split
                // out from the ellipsis menu so add actions are
                // discoverable without a tap-explore. Tester
                // request (2026-05-21): "separate the filter (3
                // dots) from the add functionality, by adding a +,
                // since that seems to be the standard to add
                // something."
                Menu {
                    Button {
                        showAdd = true
                    } label: { Label("Add by hash", systemImage: "number") }
                    Button {
                        showScanner = true
                    } label: { Label("Scan QR code", systemImage: "qrcode.viewfinder") }
                } label: {
                    Image(systemName: "plus")
                }

                // Ellipsis — filter only, post split.
                Menu {
                    Picker("Filter", selection: $filter) {
                        ForEach(availableFilters) { f in
                            Text(f.rawValue).tag(f)
                        }
                    }
                    .pickerStyle(.inline)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 6)
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
        .padding(.horizontal, 12)
        .padding(.bottom, 6)
    }

    // ---- Node list ----------------------------------------------------

    @ViewBuilder
    private var nodeList: some View {
        if filtered.isEmpty {
            emptyState
        } else {
            List(filtered, id: \.id) { row in
                NodeRow(row: row)
                    .contentShape(Rectangle())
                    .onTapGesture { detailRow = row }
                    .swipeActions(edge: .leading, allowsFullSwipe: true) {
                        Button {
                            store.toggleFavorite(hash: row.hash, favorite: !row.favorite)
                        } label: {
                            Label(row.favorite ? "Remove" : "Contact",
                                  systemImage: row.favorite ? "person.badge.minus" : "person.badge.plus")
                        }
                        .tint(.accentColor)
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            pendingDelete = row
                        } label: { Label("Delete", systemImage: "trash") }
                    }
            }
            .listStyle(.plain)
            .scrollDismissesKeyboard(.immediately)
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label(emptyTitle, systemImage: emptyIcon)
        } description: {
            Text(emptyMessage)
        }
    }

    private var emptyTitle: String {
        if !search.isEmpty { return "No matches" }
        return "No destinations"
    }

    private var emptyIcon: String {
        if !search.isEmpty { return "magnifyingglass" }
        switch filter {
        case .contacts:   return "person.crop.circle.badge.plus"
        case .rrc:        return "list.bullet"
        default:          return "antenna.radiowaves.left.and.right"
        }
    }

    private var emptyMessage: String {
        if !search.isEmpty { return "Nothing matches “\(search)”." }
        switch filter {
        case .contacts:   return "No contacts yet — open a node and tap Add to Contacts."
        case .messagable: return "No messagable destinations seen yet — connect a transport in Settings."
        case .all:        return "No destinations seen yet — connect a transport in Settings."
        case .telemetry:  return "No non-LXMF nodes seen yet."
        case .rrc:        return "No RRC hubs seen yet — hubs announce on the rrc.hub aspect."
        }
    }

    // ---- Filtering ----------------------------------------------------

    private var filtered: [StoredDestination] {
        let byFilter: [StoredDestination] = store.allDestinations.filter { d in
            switch filter {
            case .contacts:   return d.favorite
            case .messagable: return d.isMessagable || (d.publicKey.size == 0 && d.appName == nil)
            case .all:        return true
            case .telemetry:  return d.appName != "lxmf.delivery"
            case .rrc:        return d.appName == "rrc.hub"
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

    /// Run `work` after the current sheet has had time to dismiss —
    /// SwiftUI can't present a second sheet in the same runloop tick
    /// the first is dismissing.
    private func presentAfterDismiss(_ work: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: work)
    }
}

// ---- Row ----------------------------------------------------------------

private struct NodeRow: View {
    let row: StoredDestination

    var body: some View {
        HStack(spacing: 12) {
            NodeAvatar(appName: row.appName, seed: row.hash)
            VStack(alignment: .leading, spacing: 2) {
                Text(displayName)
                    .font(.body)
                Text("\(row.appName ?? "unknown") · \(shortHash(row.hash))")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                if !meta.isEmpty {
                    Text(meta).font(.caption).foregroundStyle(.secondary)
                }
                if let telemetry = row.telemetry, !telemetry.isEmpty {
                    Text(telemetryLine(telemetry))
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 2)
    }

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

    private var meta: String {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let ageMs = max(0, now - row.lastSeen)
        var parts: [String] = []
        if row.hopCount > 0 { parts.append("\(row.hopCount) hop\(row.hopCount == 1 ? "" : "s")") }
        if let r = row.rssi { parts.append("RSSI \(Int(truncating: r)) dBm") }
        if row.lastSeen > 0 { parts.append("seen \(relativeAge(ageMs))") }
        if row.source != "announce" { parts.append("source=\(row.source)") }
        if !row.isMessagable && row.appName == "lxmf.delivery" { parts.append("waiting for announce") }
        return parts.joined(separator: " · ")
    }
}

/// Round per-type avatar at the head of each Nodes row — a person for
/// messagable (lxmf.delivery) destinations, distinct glyphs for the
/// other node kinds (docs/REDESIGN.md §10).
private struct NodeAvatar: View {
    let appName: String?
    /// Hex hash used to derive the avatar's background colour
    /// (Meshtastic-parity — see `shared/.../util/AvatarColors.kt`).
    let seed: String

    var body: some View {
        let icon: String
        switch appName {
        case "lxmf.delivery":     icon = "person.fill"
        case "rrc.hub":           icon = "list.bullet"
        case "nomadnetwork.node": icon = "info.circle.fill"
        default:                  icon = "mappin"
        }
        let colors = AvatarColorsKt.avatarColors(seed: seed)
        let bg = swiftColor(fromArgb: Int(colors.backgroundArgb))
        let tint: Color = colors.useDarkText ? .black : .white
        return ZStack {
            Circle().fill(bg)
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundStyle(tint)
        }
        .frame(width: 40, height: 40)
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
