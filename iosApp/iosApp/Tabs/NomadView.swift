// SPDX-License-Identifier: MIT
//
// Nomad tab — list nomadnetwork.node destinations, fetch
// /page/index.mu via the engine when a node is tapped, render the
// resulting micron document with the full MicronView renderer.
//
// Scope: list browsing + rich micron rendering (MicronView.swift),
// history-aware Back across same-node AND cross-node nav, per-row
// favorite toggle, per-row meta line (hops/RSSI/age), page-level
// identify toggle, per-page clear-cache, and /file/ downloads.
// In-page links are dispatched through the shared `parseLinkTarget`
// (commonMain) so same-node, cross-node `<hex>:/path`, and `lxmf@`
// links all route the same way Android's NomadScreen does.

import Shared
import SwiftUI
import UniformTypeIdentifiers

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
                .scrollDismissesKeyboard(.immediately)
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

/// One entry on the Nomad page-history stack — enough to restore a
/// cross-node hop, not just a same-node path change.
private struct NomadHistoryEntry {
    let hash: String
    let title: String
    let path: String
}

private struct NomadPageView: View {
    /// The node this view was opened on — kept only to seed the
    /// initial `currentHash` / `currentTitle` and as a favorite
    /// fallback. Cross-node link follow swaps `currentHash` away
    /// from it.
    let node: StoredDestination
    @EnvironmentObject private var store: ReticulumStore

    /// Destination + title currently being browsed. Starts as `node`
    /// and is reassigned in place when a cross-node link is followed
    /// (mirrors the Android `selected` @State on NomadScreen).
    @State private var currentHash: String
    @State private var currentTitle: String

    @State private var pageState: PageState = .loading
    @State private var path: String = "/page/index.mu"
    /// Stack of previously-visited (node, path) tuples. Each in-page
    /// link follow pushes the current location before navigating; the
    /// toolbar Back button pops it. Covers same-node AND cross-node nav.
    @State private var history: [NomadHistoryEntry] = []
    /// Opt-in LINKIDENTIFY before REQUEST. Required for ALLOW_LIST
    /// pages whose handler keys auth on the remote identity hash.
    /// Off by default — identifying reveals the user's long-term
    /// identity hash to the page operator (SPEC.md §11.6.6 privacy
    /// note). Persisted to the server's view of "this user" only;
    /// stored locally just in @State for this page session.
    @State private var identify: Bool = false
    @State private var showClearCacheConfirm: Bool = false

    // /file/ download state — mirrors NomadScreen.kt's pendingFile +
    // fileInFlight pair. The .fileExporter sheet only opens once we
    // have the bytes in memory (can't pre-launch with placeholder
    // content because SwiftUI needs the FileDocument).
    @State private var fileInFlightPath: String? = nil
    @State private var pendingDownload: NomadFileDocument? = nil
    @State private var fileError: String? = nil

    init(node: StoredDestination) {
        self.node = node
        _currentHash = State(initialValue: node.hash)
        let name = node.effectiveDisplayName
        _currentTitle = State(initialValue: name.isEmpty ? "(unnamed)" : name)
    }

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
                    MicronView(
                        source: source,
                        onLinkClick: { target in handleLinkClick(target) },
                        onLinkClickWithFields: { target, data in
                            // Form-submit link tap: same as a plain GET
                            // but the request envelope carries the
                            // collected `field_<name>` / `var_<k>`
                            // values. `resolveSubmitPath` (commonMain)
                            // handles `/path`, the legacy `:/path`, and
                            // an empty/`:`-only self-submit target —
                            // cross-node POSTs fall back to the current
                            // page, exactly as Android's handler does.
                            let resolved = LinkTargetKt.resolveSubmitPath(
                                currentPath: path, target: target)
                            pushHistory()
                            path = resolved
                            submit(data: data)
                        }
                    )
                case .error(let msg):
                    Text(msg)
                        .font(.callout)
                        .foregroundStyle(.red)
                }

                // /file/ download status banner. Sits above the page
                // body so the user retains reading context while the
                // download progresses. The .fileExporter sheet pops
                // once we have the bytes; success is implicit (sheet
                // appears). Failure surfaces here with a dismiss
                // button.
                if let inflight = fileInFlightPath {
                    HStack(spacing: 8) {
                        ProgressView().scaleEffect(0.7)
                        Text("Downloading \(inflight.components(separatedBy: "/").last ?? "file")…")
                            .font(.caption)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.gray.opacity(0.15))
                } else if let err = fileError {
                    HStack(spacing: 8) {
                        Text(err)
                            .font(.caption)
                            .foregroundStyle(.red)
                        Spacer()
                        Button {
                            fileError = nil
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.red.opacity(0.08))
                }
            }
            .padding()
        }
        // .fileExporter pops once pendingDownload is set with the
        // bytes + filename returned from fetchNomadFileBridge. User
        // picks a destination (Files app, iCloud Drive, Dropbox via
        // extension, etc.), iOS writes via FileDocument.fileWrapper.
        .fileExporter(
            isPresented: Binding(
                get: { pendingDownload != nil },
                set: { if !$0 { pendingDownload = nil; fileInFlightPath = nil } }
            ),
            document: pendingDownload,
            contentType: .data,
            defaultFilename: pendingDownload?.filename ?? "download"
        ) { result in
            pendingDownload = nil
            fileInFlightPath = nil
            if case .failure(let err) = result {
                fileError = "Couldn't save file: \(err.localizedDescription)"
            }
        }
        // Scrolling the page (or any rich-Micron form input list)
        // dismisses the keyboard. .interactively so the keyboard
        // tracks the swipe — feels less abrupt than .immediately
        // when the user is reading a long page mid-typing.
        .scrollDismissesKeyboard(.interactively)
        .keyboardDoneToolbar()
        .navigationTitle(currentTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                // History-aware page Back button. Disabled until the
                // user has followed at least one in-page link.
                Button {
                    guard let prior = history.popLast() else { return }
                    currentHash = prior.hash
                    currentTitle = prior.title
                    path = prior.path
                    fetch()
                } label: {
                    Image(systemName: "arrow.uturn.backward")
                }
                .disabled(history.isEmpty)

                Button { fetch() } label: { Image(systemName: "arrow.clockwise") }

                // Favorite toggle — parity with the Android Nomad page
                // toolbar. Reads the live favorite state out of
                // store.allDestinations so the glyph updates when the
                // store re-emits after toggleFavorite persists.
                Button {
                    store.toggleFavorite(hash: currentHash, favorite: !liveFavorite)
                } label: {
                    Image(systemName: liveFavorite ? "star.fill" : "star")
                        .foregroundStyle(liveFavorite ? Color.accentColor : .secondary)
                }

                // Identify toggle. Same convention as Android's
                // NomadScreen.kt:629 — always a closed-padlock glyph,
                // tint-only state (accent = identifying, muted =
                // anonymous). The closed-lock-by-default reads as
                // "your identity is sealed unless you explicitly
                // unseal it" instead of the "open=safe" inversion the
                // earlier iOS rendering implied. Toggling triggers a
                // re-fetch since auth state changes the response.
                Button {
                    identify.toggle()
                    fetch()
                } label: {
                    Image(systemName: "lock.fill")
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
                store.clearNomadCache(destHash: currentHash)
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Removes every cached page from \(currentTitle) on this device. Next fetch will hit the network. The cache is local only.")
        }
        .task { fetch() }
    }

    /// Live favorite flag for this node — re-derived on every render
    /// from the store's published destinations so the toolbar star
    /// updates immediately after toggleFavorite persists. Falls back
    /// to the initial `node.favorite` if the row isn't in the live
    /// list yet (e.g. straight after a deletion-undo).
    ///
    /// `as String` disambiguates against NSObject's inherited `hash:
    /// Int` — Kotlin/Native exports StoredDestination as an NSObject
    /// subclass, so the closure sees both the Kotlin `hash: String`
    /// field and the inherited `hash: Int` and the closure body
    /// becomes ambiguous. Same fix MessagesView.swift uses on the
    /// `path.append(dest.hash as String)` call site.
    private var liveFavorite: Bool {
        let target = currentHash
        if let d = store.allDestinations.first(where: { ($0.hash as String) == target }) {
            return d.favorite
        }
        // A cross-node hop to a node not yet in the live list — fall
        // back to the entry node's flag only when we're still on it.
        return target == (node.hash as String) ? node.favorite : false
    }

    /// Dispatch an in-page micron link tap through the shared
    /// `parseLinkTarget` (commonMain) — the same routing Android's
    /// NomadScreen uses. Same-node paths navigate (or download for
    /// `/file/`), cross-node links swap the browsed node, `lxmf@`
    /// links open a conversation, and anything unparseable surfaces
    /// as an error rather than a silent no-op.
    private func handleLinkClick(_ target: String) {
        let parsed = LinkTargetKt.parseLinkTarget(raw: target)
        if let same = parsed as? LinkTarget.SameNode {
            if same.path.hasPrefix("/file/") {
                downloadFile(path: same.path)
            } else {
                pushHistory()
                path = same.path
                fetch()
            }
        } else if let cross = parsed as? LinkTarget.CrossNode {
            followCrossNode(hash: cross.destHashHex, path: cross.path)
        } else if let lxmf = parsed as? LinkTarget.Lxmf {
            // Resolve / create the contact so it shows in Messages,
            // then route through openContact (the same deep-link
            // signal a notification tap uses). Mirrors Android's
            // LinkTarget.Lxmf branch.
            if !store.allDestinations.contains(where: { ($0.hash as String) == lxmf.destHashHex }) {
                store.addManualDestination(hashHex: lxmf.destHashHex, label: "(via nomad link)")
            }
            store.toggleFavorite(hash: lxmf.destHashHex, favorite: true)
            store.openContact(hash: lxmf.destHashHex)
        } else {
            pageState = .error("Unrecognized link: \(target)")
        }
    }

    /// Push the current (node, title, path) onto the history stack
    /// before an in-page navigation, so toolbar Back can walk it.
    private func pushHistory() {
        history.append(NomadHistoryEntry(hash: currentHash, title: currentTitle, path: path))
    }

    /// Follow a cross-node link: swap the browsed destination to
    /// [hash] and load [newPath]. If the target node isn't known
    /// yet, add a manual stub so it lands in the Nodes list and the
    /// engine can path-discover it; `fetchNomadPageBridge` re-primes
    /// the path before LINKREQ regardless. Mirrors Android's
    /// resolveOrPrepareDestination + CrossNode branch.
    private func followCrossNode(hash: String, path newPath: String) {
        pushHistory()
        let known = store.allDestinations.first { ($0.hash as String) == hash }
        if known == nil {
            store.addManualDestination(hashHex: hash, label: "(via cross-node link)")
        }
        currentHash = hash
        if let name = known?.effectiveDisplayName, !name.isEmpty {
            currentTitle = name
        } else {
            currentTitle = String(hash.prefix(8)) + "…"
        }
        path = newPath
        fetch()
    }

    private func fetch() {
        pageState = .loading
        Task {
            do {
                let r = try await IosEngineFactoryKt.fetchNomadPageBridge(
                    engine: store.engine,
                    destinationHash: currentHash,
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

    /// Tap on a `/file/<...>` link — fetches the file bytes + server-
    /// supplied filename via fetchNomadFileBridge, then surfaces a
    /// .fileExporter sheet so the user picks a save destination.
    /// Concurrent taps are ignored while one's in flight (mirrors
    /// the Android NomadScreen fileInFlight gate).
    private func downloadFile(path filePath: String) {
        if fileInFlightPath != nil { return }  // serialize taps
        fileInFlightPath = filePath
        fileError = nil
        Task {
            do {
                let r = try await IosEngineFactoryKt.fetchNomadFileBridge(
                    engine: store.engine,
                    destinationHash: currentHash,
                    path: filePath,
                    identify: identify
                )
                if let bytes = r.bytes, let filename = r.filename {
                    // Copy KotlinByteArray → Data, same byte-by-byte
                    // pattern as identity export / image send. K/N
                    // doesn't expose a fast bulk copy through the
                    // Swift bridge.
                    let count = Int(bytes.size)
                    var data = Data(count: count)
                    for i in 0..<count {
                        data[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
                    }
                    pendingDownload = NomadFileDocument(data: data, filename: filename)
                } else {
                    fileError = r.errorMessage ?? "File fetch failed"
                    fileInFlightPath = nil
                }
            } catch {
                fileError = "File fetch threw: \(error)"
                fileInFlightPath = nil
            }
        }
    }

    /// POST a form-submit dict (`field_<name>` / `var_<k>` entries
    /// collected from the user's input fields by the MicronView link-
    /// tap handler) and render the response. Same engine call as
    /// fetch() but routed through the with-data bridge so the
    /// envelope's [2] element carries the dict instead of `nil`.
    private func submit(data: [String: String]) {
        pageState = .loading
        Task {
            do {
                let r = try await IosEngineFactoryKt.fetchNomadPageWithDataBridge(
                    engine: store.engine,
                    destinationHash: currentHash,
                    path: path,
                    identify: identify,
                    data: data
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

// (Plain-text micron stripper retired — full MicronView now lives in
// MicronView.swift and renders headings, paragraphs, fields, tables,
// partials, and form-submit links to parity with Android.)

/// FileDocument wrapper for a NomadNet `/file/` download. Carries
/// the bytes + the server-supplied filename through SwiftUI's
/// `.fileExporter` so the user picks a save destination (Files app,
/// iCloud Drive, Dropbox, etc.) and iOS writes the bytes via
/// `fileWrapper(configuration:)`. Same shape as `RmidDocument` in
/// SettingsView for identity export.
///
/// The `filename` field is the suggested default in the exporter
/// dialog; the user can rename before saving. `data` is the raw
/// file bytes (metadata prefix already stripped by
/// `Resource.assemble`).
struct NomadFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var data: Data
    var filename: String

    init(data: Data, filename: String) {
        self.data = data
        self.filename = filename
    }

    init(configuration: ReadConfiguration) throws {
        // /file/ downloads only flow one direction (server → us); we
        // never read FileDocuments back from disk via this path.
        // Throw so SwiftUI surfaces a clear error if someone wires
        // read-mode by mistake.
        throw CocoaError(.fileReadUnsupportedScheme)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
