// SPDX-License-Identifier: MIT
//
// Messages tab — favorited destinations + inbox of senders we've heard
// from. Tap a row to drill into the conversation. Mirrors the Android
// `MessagesScreen.kt` shape: pinned section for favorites, separate
// inbox section, both rendered with `effectiveDisplayName`.

import Shared
import SwiftUI

struct MessagesView: View {
    @EnvironmentObject private var store: ReticulumStore

    var body: some View {
        NavigationStack {
            List {
                if store.favorites.isEmpty && store.inbox.isEmpty {
                    Section {
                        Text("No conversations yet — connect on Settings, then star a destination on Nodes (or wait for someone to message you).")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                if !store.favorites.isEmpty {
                    Section("Favorites") {
                        ForEach(store.favorites, id: \.id) { dest in
                            NavigationLink {
                                ConversationView(contact: dest)
                            } label: {
                                ThreadRow(dest: dest)
                            }
                        }
                    }
                }
                if !store.inbox.isEmpty {
                    Section("Inbox") {
                        ForEach(store.inbox, id: \.id) { dest in
                            NavigationLink {
                                ConversationView(contact: dest)
                            } label: {
                                ThreadRow(dest: dest)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Messages")
        }
    }
}

private struct ThreadRow: View {
    let dest: StoredDestination

    var body: some View {
        HStack(spacing: 12) {
            Avatar(label: name)
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(.body)
                Text(dest.hash)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
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
