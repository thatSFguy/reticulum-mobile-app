// SPDX-License-Identifier: MIT
//
// Nomad tab — list nomadnetwork.node destinations, fetch
// /page/index.mu via the engine when a node is tapped, render a
// SIMPLIFIED text-only view of the resulting micron document.
//
// Phase 3 scope: enough to load and read a real NomadNet page. The
// rich micron renderer (bold / italic / colors / tables / form
// inputs) is a Phase 4 follow-up — porting MicronView.kt is ~500
// lines of Compose-to-SwiftUI work. For now we strip the tags and
// show plain text plus tap-to-navigate links.

import Shared
import SwiftUI

struct NomadView: View {
    @EnvironmentObject private var store: ReticulumStore

    var body: some View {
        NavigationStack {
            List(nomadNodes, id: \.id) { node in
                NavigationLink {
                    NomadPageView(node: node)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(displayName(node))
                            .font(.body)
                        Text(node.hash)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        if node.hopCount > 0 {
                            Text("\(node.hopCount) hop\(node.hopCount == 1 ? "" : "s")")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .overlay {
                if nomadNodes.isEmpty {
                    ContentUnavailableView(
                        "No NomadNet nodes",
                        systemImage: "doc.text.magnifyingglass",
                        description: Text("Connect a transport on Settings; nodes appear here as their announces arrive.")
                    )
                }
            }
            .navigationTitle("Nomad")
        }
    }

    private var nomadNodes: [StoredDestination] {
        store.allDestinations.filter { $0.appName == "nomadnetwork.node" }
    }

    private func displayName(_ d: StoredDestination) -> String {
        let name = d.effectiveDisplayName
        if !name.isEmpty { return name }
        return d.appLabel ?? "(unnamed)"
    }
}

// ---- Per-page fetch + render ------------------------------------------

private struct NomadPageView: View {
    let node: StoredDestination
    @EnvironmentObject private var store: ReticulumStore

    @State private var pageState: PageState = .loading
    @State private var path: String = "/page/index.mu"

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
                        // Tap on a same-node link → re-fetch with the
                        // new path. Cross-node links and lxmf@ links
                        // are deferred to Phase 4 alongside the rich
                        // renderer.
                        if target.hasPrefix("/") {
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
            ToolbarItem(placement: .topBarTrailing) {
                Button { fetch() } label: { Image(systemName: "arrow.clockwise") }
            }
        }
        .task { fetch() }
    }

    private func fetch() {
        pageState = .loading
        Task {
            do {
                // fetchNomadPageBridge is a top-level Kotlin extension
                // declared in iosMain (IosEngineFactory.kt) that wraps
                // the engine's `Result<String>` return — Kotlin's
                // inline value classes don't cross the Swift bridge.
                let r = try await IosEngineFactoryKt.fetchNomadPageBridge(
                    engine: store.engine,
                    destinationHash: node.hash,
                    path: path,
                    identify: false
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

// ---- Plain-text micron stripper ---------------------------------------

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
        // Strip the most common micron escapes that would otherwise
        // render as gibberish: backtick-prefixed style markers (`!, `_,
        // `*), `f<rgb>, `b<rgb>, `c (clear), heading `>, alignment `c
        // / `r etc. We DON'T parse them — Phase 4's MicronView port
        // does — just remove the noise.
        let stripped = stripMicron(raw)

        // Linkify [label`url] inline syntax to a tappable button.
        // Anything that doesn't match falls through as plain Text.
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

/// Quick-and-dirty `[label\`url]` matcher. Real micron has more
/// variations; the Phase 4 MicronView port handles them all.
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
            // Skip the backtick + the next 1 char (e.g. `!, `_, `c) or
            // until a digit-run for color codes (`F308). Defensive: cap
            // the skip so a stray backtick at end-of-line doesn't loop.
            let next = s.index(after: i)
            if next >= s.endIndex { i = s.endIndex; continue }
            let marker = s[next]
            i = s.index(after: next)
            if marker == "F" || marker == "B" || marker == "f" || marker == "b" {
                // Skip up to 3 hex chars
                var count = 0
                while i < s.endIndex && count < 3 && s[i].isHexDigit {
                    i = s.index(after: i)
                    count += 1
                }
            }
            // Other markers consumed in the +2 above.
        } else {
            out.append(c)
            i = s.index(after: i)
        }
    }
    return out
}
