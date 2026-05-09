// SPDX-License-Identifier: MIT
//
// Nomad tab — list nomadnetwork.node destinations, fetch
// /page/index.mu via the engine when a node is tapped, render a
// SIMPLIFIED text-only view of the resulting micron document.
//
// v1.0 scope: list browsing + plain-text rendering with
// history-aware Back, per-row favorite toggle, per-row meta line
// (hops/RSSI/age), page-level identify toggle, and per-page clear-
// cache. The rich micron renderer (bold / italic / colors / tables
// / form inputs) is the v1.1 follow-up — porting MicronView.kt is
// ~500 lines of Compose-to-SwiftUI work. Cross-node link follow
// and lxmf@ deep-link also live in v1.1 alongside the renderer.

import Shared
import SwiftUI

struct NomadView: View {
    @EnvironmentObject private var store: ReticulumStore

    enum Filter: String, CaseIterable, Identifiable {
        case all = "All"
        case favorites = "Favorites"
        var id: String { rawValue }
    }

    @State private var filter: Filter = .all
    @State private var search: String = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                filterBar
                searchField
                List(filtered, id: \.id) { node in
                    NavigationLink {
                        NomadPageView(node: node)
                    } label: {
                        NomadRow(
                            node: node,
                            onToggleFavorite: { fav in
                                store.toggleFavorite(hash: node.hash, favorite: fav)
                            }
                        )
                    }
                }
                .listStyle(.plain)
                .overlay {
                    if filtered.isEmpty {
                        ContentUnavailableView(
                            "No NomadNet nodes",
                            systemImage: "doc.text.magnifyingglass",
                            description: Text(emptyMessage)
                        )
                    }
                }
            }
            .navigationTitle("Nomad")
        }
    }

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
            TextField("Search by name or hash", text: $search)
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

    private var filtered: [StoredDestination] {
        let nomadNodes = store.allDestinations.filter { $0.appName == "nomadnetwork.node" }
        let byFilter: [StoredDestination] = {
            switch filter {
            case .all: return nomadNodes
            case .favorites: return nomadNodes.filter { $0.favorite }
            }
        }()
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return byFilter }
        return byFilter.filter { d in
            d.effectiveDisplayName.lowercased().contains(q) ||
                d.displayName.lowercased().contains(q) ||
                (d.appLabel?.lowercased().contains(q) ?? false) ||
                d.hash.lowercased().contains(q)
        }
    }

    private var emptyMessage: String {
        if !search.isEmpty { return "Nothing matches “\(search)”." }
        switch filter {
        case .all: return "Connect a transport on Settings; nodes appear here as their announces arrive."
        case .favorites: return "Star a NomadNet node from this tab to bring it here."
        }
    }
}

// MARK: - Row

private struct NomadRow: View {
    let node: StoredDestination
    let onToggleFavorite: (Bool) -> Void

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(displayName)
                    .font(.body)
                Text(node.hash)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if !meta.isEmpty {
                    Text(meta)
                        .font(.caption2)
                        .foregroundStyle(metaTint)
                }
            }
            Spacer()
            // NavigationLink swallows tap on the whole row; wrap the
            // star in a Button with .buttonStyle(.borderless) so iOS
            // routes the tap to the button only, not the row.
            Button { onToggleFavorite(!node.favorite) } label: {
                Image(systemName: node.favorite ? "star.fill" : "star")
                    .foregroundStyle(node.favorite ? Color.accentColor : .secondary)
            }
            .buttonStyle(.borderless)
        }
    }

    private var displayName: String {
        let name = node.effectiveDisplayName
        if !name.isEmpty { return name }
        return node.appLabel ?? "(unnamed)"
    }

    /// `<hops> hop(s) · RSSI <X> dBm · seen Xm ago · stale/far flags`.
    /// Mirrors the same shape NodesView.NodeRow uses.
    private var meta: String {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let ageMs = max(0, now - node.lastSeen)
        var parts: [String] = []
        if node.hopCount > 0 { parts.append("\(node.hopCount) hop\(node.hopCount == 1 ? "" : "s")") }
        if let r = node.rssi { parts.append("RSSI \(Int(truncating: r)) dBm") }
        if node.lastSeen > 0 { parts.append("seen \(formatAge(ageMs))") }
        if isStale(ageMs: ageMs) { parts.append("stale") }
        else if node.hopCount >= 4 { parts.append("far — link may be slow") }
        return parts.joined(separator: " · ")
    }

    private var metaTint: Color {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let ageMs = max(0, now - node.lastSeen)
        if isStale(ageMs: ageMs) { return .red }
        if node.hopCount >= 4 { return .orange }
        return .secondary
    }

    private func isStale(ageMs: Int64) -> Bool { node.lastSeen > 0 && ageMs > 30 * 60_000 }

    private func formatAge(_ ageMs: Int64) -> String {
        let s = ageMs / 1000
        if s < 60 { return "\(s)s ago" }
        if s < 3600 { return "\(s / 60)m ago" }
        if s < 86_400 { return "\(s / 3600)h ago" }
        return "\(s / 86_400)d ago"
    }
}

// MARK: - Per-page fetch + render

private struct NomadPageView: View {
    let node: StoredDestination
    @EnvironmentObject private var store: ReticulumStore

    @State private var pageState: PageState = .loading
    @State private var path: String = "/page/index.mu"
    /// Stack of previously-visited paths on this same node. Each
    /// in-page link follow pushes the current path before navigating;
    /// the toolbar Back button pops it. Mirrors the Android same-node
    /// navigation history.
    @State private var pathHistory: [String] = []
    /// Opt-in LINKIDENTIFY before REQUEST. Required for ALLOW_LIST
    /// pages whose handler keys auth on the remote identity hash.
    /// Off by default — identifying reveals the user's long-term
    /// identity hash to the page operator (SPEC.md §11.6.6 privacy
    /// note). Persisted to the server's view of "this user" only;
    /// stored locally just in @State for this page session.
    @State private var identify: Bool = false
    @State private var showClearCacheConfirm: Bool = false

    enum PageState {
        case loading
        case loaded(String)
        case error(String)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(path)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)

                switch pageState {
                case .loading:
                    HStack {
                        ProgressView()
                        Text("Establishing link and requesting \(path)…")
                            .font(.callout)
                    }
                    .padding(.vertical)
                case .loaded(let source):
                    NomadPlainText(source: source) { target in
                        // Same-node link follow: push current path
                        // onto history, navigate. Cross-node and
                        // lxmf@ deferred to v1.1.
                        if target.hasPrefix("/") {
                            pathHistory.append(path)
                            path = target
                            fetch()
                        }
                    }
                case .error(let msg):
                    Text(msg)
                        .font(.callout)
                        .foregroundStyle(.red)
                }
            }
            .padding()
        }
        .navigationTitle(node.effectiveDisplayName.isEmpty ? "(unnamed)" : node.effectiveDisplayName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                // History-aware page Back button. Disabled until the
                // user has followed at least one in-page link.
                Button {
                    guard let prior = pathHistory.popLast() else { return }
                    path = prior
                    fetch()
                } label: {
                    Image(systemName: "arrow.uturn.backward")
                }
                .disabled(pathHistory.isEmpty)

                Button { fetch() } label: { Image(systemName: "arrow.clockwise") }

                // Favorite toggle — parity with the Android Nomad page
                // toolbar. Reads the live favorite state out of
                // store.allDestinations so the glyph updates when the
                // store re-emits after toggleFavorite persists.
                Button {
                    store.toggleFavorite(hash: node.hash, favorite: !liveFavorite)
                } label: {
                    Image(systemName: liveFavorite ? "star.fill" : "star")
                        .foregroundStyle(liveFavorite ? Color.accentColor : .secondary)
                }

                // Identify toggle (closed = will identify on next
                // fetch, open = anonymous). Toggling triggers a re-fetch
                // since auth state changes the response.
                Button {
                    identify.toggle()
                    fetch()
                } label: {
                    Image(systemName: identify ? "lock.fill" : "lock.open")
                        .foregroundStyle(identify ? Color.accentColor : .secondary)
                }

                Button {
                    showClearCacheConfirm = true
                } label: {
                    Image(systemName: "tray.and.arrow.down")
                }
            }
        }
        .alert("Clear cached pages?", isPresented: $showClearCacheConfirm) {
            Button("Clear", role: .destructive) {
                store.clearNomadCache(destHash: node.hash)
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Removes every cached page from \(node.effectiveDisplayName.isEmpty ? "this node" : node.effectiveDisplayName) on this device. Next fetch will hit the network. The cache is local only.")
        }
        .task { fetch() }
    }

    /// Live favorite flag for this node — re-derived on every render
    /// from the store's published destinations so the toolbar star
    /// updates immediately after toggleFavorite persists. Falls back
    /// to the initial `node.favorite` if the row isn't in the live
    /// list yet (e.g. straight after a deletion-undo).
    private var liveFavorite: Bool {
        store.allDestinations.first(where: { $0.hash == node.hash })?.favorite ?? node.favorite
    }

    private func fetch() {
        pageState = .loading
        Task {
            do {
                let r = try await IosEngineFactoryKt.fetchNomadPageBridge(
                    engine: store.engine,
                    destinationHash: node.hash,
                    path: path,
                    identify: identify
                )
                if let src = r.source {
                    pageState = .loaded(src)
                } else {
                    pageState = .error(r.errorMessage ?? "Unknown error")
                }
            } catch {
                pageState = .error("\(error)")
            }
        }
    }
}

// MARK: - Plain-text micron stripper

private struct NomadPlainText: View {
    let source: String
    let onLinkTap: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                renderLine(line)
            }
        }
    }

    private var lines: [String] { source.components(separatedBy: .newlines) }

    @ViewBuilder
    private func renderLine(_ raw: String) -> some View {
        let stripped = stripMicron(raw)
        if let link = matchInlineLink(stripped) {
            (Text(link.before).foregroundStyle(.primary) +
             Text(link.label).foregroundStyle(Color.accentColor).underline() +
             Text(link.after).foregroundStyle(.primary))
                .font(.body)
                .onTapGesture { onLinkTap(link.url) }
        } else if stripped.hasPrefix(">") {
            Text(stripped.dropFirst()).font(.title3.bold())
        } else if stripped.hasPrefix(">>") {
            Text(stripped.dropFirst(2)).font(.headline)
        } else if stripped.isEmpty {
            Spacer().frame(height: 4)
        } else {
            Text(stripped).font(.body)
        }
    }
}

private struct InlineLink {
    let before: String
    let label: String
    let url: String
    let after: String
}

private func matchInlineLink(_ s: String) -> InlineLink? {
    guard let openIdx = s.firstIndex(of: "[") else { return nil }
    guard let backtickIdx = s[openIdx...].firstIndex(of: "`") else { return nil }
    guard let closeIdx = s[backtickIdx...].firstIndex(of: "]") else { return nil }
    let before = String(s[..<openIdx])
    let labelStart = s.index(after: openIdx)
    let label = String(s[labelStart..<backtickIdx])
    let urlStart = s.index(after: backtickIdx)
    let url = String(s[urlStart..<closeIdx])
    let after = String(s[s.index(after: closeIdx)...])
    return InlineLink(before: before, label: label, url: url, after: after)
}

private func stripMicron(_ s: String) -> String {
    var out = ""
    var i = s.startIndex
    while i < s.endIndex {
        let c = s[i]
        if c == "`" {
            let next = s.index(after: i)
            if next >= s.endIndex { i = s.endIndex; continue }
            let marker = s[next]
            i = s.index(after: next)
            if marker == "F" || marker == "B" || marker == "f" || marker == "b" {
                var count = 0
                while i < s.endIndex && count < 3 && s[i].isHexDigit {
                    i = s.index(after: i)
                    count += 1
                }
            }
        } else {
            out.append(c)
            i = s.index(after: i)
        }
    }
    return out
}
