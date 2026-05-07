// SPDX-License-Identifier: MIT
//
// Messages tab — favorited destinations + inbox of senders we've heard
// from. Tap a row to drill into the conversation. Mirrors the Android
// `MessagesScreen.kt` shape: pinned section for favorites, separate
// inbox section, both rendered with `effectiveDisplayName`. Each row
// also has a star toggle to move it between Favorites and Inbox in
// place.

import Shared
import SwiftUI

struct MessagesView: View {
    @EnvironmentObject private var store: ReticulumStore
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if store.favorites.isEmpty && store.inbox.isEmpty {
                    Section {
                        Text("No conversations yet — connect on Settings, then star a destination on Nodes (or wait for someone to message you).")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                // `as String` on dest.hash disambiguates against
                // NSObject's inherited `hash: Int` — Kotlin/Native
                // exports StoredDestination as an NSObject subclass,
                // so Swift sees both the Kotlin String field and the
                // NSObject Int property. path.append accepts any
                // Hashable so both candidates type-check, hence the
                // explicit cast.
                if !store.favorites.isEmpty {
                    Section("Favorites") {
                        ForEach(store.favorites, id: \.id) { dest in
                            ThreadRow(
                                dest: dest,
                                onPick: { path.append(dest.hash as String) },
                                onToggleFavorite: { fav in store.toggleFavorite(hash: dest.hash, favorite: fav) }
                            )
                        }
                    }
                }
                if !store.inbox.isEmpty {
                    Section("Inbox") {
                        ForEach(store.inbox, id: \.id) { dest in
                            ThreadRow(
                                dest: dest,
                                onPick: { path.append(dest.hash as String) },
                                onToggleFavorite: { fav in store.toggleFavorite(hash: dest.hash, favorite: fav) }
                            )
                        }
                    }
                }
            }
            .navigationTitle("Messages")
            // Conversation is keyed on the hash so deep-links from the
            // Nodes tab can push without needing the full
            // StoredDestination in hand. We resolve the row at render
            // time by looking up across favorites / inbox / all.
            .navigationDestination(for: String.self) { hash in
                if let dest = resolve(hash) {
                    ConversationView(contact: dest)
                } else {
                    ContentUnavailableView(
                        "Destination not found",
                        systemImage: "questionmark.circle",
                        description: Text("This destination is no longer in the local store.")
                    )
                }
            }
        }
        // Tap-to-message deep-link from the Nodes tab. ContentView
        // already switched the tab; we just push the conversation.
        .onChange(of: store.openContactEvent) { _, new in
            guard let event = new else { return }
            // Replace any prior conversation so back-stack stays sane.
            if !path.isEmpty { path.removeLast(path.count) }
            path.append(event.hash)
        }
    }

    private func resolve(_ hash: String) -> StoredDestination? {
        store.favorites.first(where: { $0.hash == hash })
            ?? store.inbox.first(where: { $0.hash == hash })
            ?? store.allDestinations.first(where: { $0.hash == hash })
    }
}

private struct ThreadRow: View {
    let dest: StoredDestination
    let onPick: () -> Void
    let onToggleFavorite: (Bool) -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onPick) {
                HStack(spacing: 12) {
                    Avatar(label: name)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name)
                            .font(.body)
                            .foregroundStyle(.primary)
                        Text(dest.hash)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button { onToggleFavorite(!dest.favorite) } label: {
                Image(systemName: dest.favorite ? "star.fill" : "star")
                    .foregroundStyle(dest.favorite ? Color.accentColor : .secondary)
            }
            .buttonStyle(.borderless)
        }
    }

    private var name: String {
        // effectiveDisplayName is the Kotlin extension we added in
        // v0.1.83 — userLabel ?: displayName, with blank fallthrough.
        let value = dest.effectiveDisplayName
        return value.isEmpty ? "(unnamed)" : value
    }
}

struct Avatar: View {
    let label: String

    var body: some View {
        let initials = String(label.prefix(2)).uppercased()
        return ZStack {
            Circle()
                .fill(Color.accentColor.opacity(0.18))
            Text(initials)
                .font(.caption.bold())
                .foregroundStyle(Color.accentColor)
        }
        .frame(width: 34, height: 34)
    }
}
