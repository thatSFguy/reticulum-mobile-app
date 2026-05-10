// SPDX-License-Identifier: MIT
//
// Nodes tab — every observed destination, filterable + searchable,
// with per-row star (favorite) and pencil (set userLabel). Toolbar
// "+" opens the Add-by-hash dialog. Mirrors the Android NodesScreen
// minus the BLE scanner + QR scanner + osmdroid map (Phase 4 work).

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

    @State private var filter: Filter = .messagable
    @State private var search: String = ""
    @State private var showAdd: Bool = false
    @State private var renameTarget: StoredDestination? = nil
    @State private var pendingDelete: StoredDestination? = nil

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
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
            }
            .navigationTitle("Nodes")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAdd = true } label: { Image(systemName: "plus") }
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
                    Text("\(row.appName ?? "unknown") · \(row.hash)")
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
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
