// SPDX-License-Identifier: MIT
//
// Messages tab — one unified, Signal-style conversation list (the UI
// redesign replaced the old Contacts/Inbox split — docs/REDESIGN.md
// §6). Conversations are recency-sorted; pinned ones stick to the top
// under a "Pinned" header, the rest fall under "Recent". A search bar
// filters by name/hash; the refresh icon beside it runs the
// propagation auto-sync. Tap a row to open the conversation;
// long-press opens the shared destination detail sheet.

import Shared
import SwiftUI

struct MessagesView: View {
    @EnvironmentObject private var store: ReticulumStore
    @State private var path = NavigationPath()
    @State private var search: String = ""
    @State private var detailDest: StoredDestination?
    @State private var renameTarget: StoredDestination?
    @State private var pendingDelete: StoredDestination?

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                searchHeader
                if let result = store.propagationSyncResult {
                    Text(result)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 6)
                }
                Divider()
                content
            }
            .navigationTitle("Messages")
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
            .sheet(item: $detailDest) { dest in
                DestinationDetailSheet(
                    dest: dest,
                    onMessage: { hash in
                        detailDest = nil
                        path.append(hash)
                    },
                    onOpenAsRrcHub: nil,
                    onRename: { d in
                        detailDest = nil
                        presentAfterDismiss { renameTarget = d }
                    },
                    onToggleFavorite: { hash, fav in
                        detailDest = nil
                        store.toggleFavorite(hash: hash, favorite: fav)
                    },
                    onDelete: { d in
                        detailDest = nil
                        presentAfterDismiss { pendingDelete = d }
                    },
                    pinned: store.pinnedHashes.contains(dest.hash),
                    onTogglePin: { hash, p in
                        detailDest = nil
                        store.setPinned(hash: hash, pinned: p)
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
                Text("Removes \(name) from local storage along with all message history. If they announce again later they'll reappear in Nodes (without prior history).")
            }
        }
        // Tap-to-message deep-link from the Nodes tab. ContentView
        // already switched the tab; we just push the conversation.
        .onChange(of: store.openContactEvent) { _, new in
            guard let event = new else { return }
            if !path.isEmpty { path.removeLast(path.count) }
            path.append(event.hash)
        }
    }

    // ---- Header — search field + propagation refresh ------------------

    private var searchHeader: some View {
        HStack(spacing: 8) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Search conversations", text: $search)
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
            .clipShape(RoundedRectangle(cornerRadius: 20))

            Button {
                store.syncPropagationAuto()
            } label: {
                if store.propagationSyncing {
                    ProgressView()
                } else {
                    Image(systemName: "arrow.clockwise")
                        .foregroundStyle(Color.accentColor)
                }
            }
            .disabled(store.propagationSyncing)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    // ---- List ---------------------------------------------------------

    @ViewBuilder
    private var content: some View {
        let rows = filteredConversations
        if rows.isEmpty {
            if search.isEmpty {
                ContentUnavailableView(
                    "No conversations yet",
                    systemImage: "envelope",
                    description: Text("Open a node on the Nodes tab and tap Message to start a conversation.")
                )
            } else {
                ContentUnavailableView(
                    "No matches",
                    systemImage: "magnifyingglass",
                    description: Text("No conversations match “\(search)”.")
                )
            }
        } else {
            let pinned = rows.filter { store.pinnedHashes.contains($0.hash) }
            let recent = rows.filter { !store.pinnedHashes.contains($0.hash) }
            List {
                if pinned.isEmpty {
                    Section {
                        ForEach(recent, id: \.id) { dest in threadRow(dest) }
                    }
                } else {
                    Section("Pinned") {
                        ForEach(pinned, id: \.id) { dest in threadRow(dest) }
                    }
                    Section("Recent") {
                        ForEach(recent, id: \.id) { dest in threadRow(dest) }
                    }
                }
            }
            .listStyle(.plain)
            .scrollDismissesKeyboard(.immediately)
        }
    }

    private func threadRow(_ dest: StoredDestination) -> some View {
        ThreadRow(dest: dest)
            .contentShape(Rectangle())
            .onTapGesture { path.append(dest.hash as String) }
            .onLongPressGesture(minimumDuration: 0.4) { detailDest = dest }
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button(role: .destructive) {
                    pendingDelete = dest
                } label: { Label("Delete", systemImage: "trash") }
            }
    }

    // ---- Helpers ------------------------------------------------------

    private var filteredConversations: [StoredDestination] {
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return store.conversations }
        return store.conversations.filter { d in
            d.effectiveDisplayName.lowercased().contains(q) ||
                d.hash.lowercased().contains(q)
        }
    }

    private func resolve(_ hash: String) -> StoredDestination? {
        store.conversations.first(where: { $0.hash == hash })
            ?? store.allDestinations.first(where: { $0.hash == hash })
    }

    private func presentAfterDismiss(_ work: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: work)
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
                    .foregroundStyle(.primary)
                Text(shortHash(dest.hash))
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
        }
        .padding(.vertical, 2)
    }

    private var name: String {
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
