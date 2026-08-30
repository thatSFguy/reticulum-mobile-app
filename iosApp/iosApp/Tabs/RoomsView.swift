// SPDX-License-Identifier: MIT
//
// Rooms tab — the (experimental) Reticulum Relay Chat client. Mirrors
// the Android `RoomsScreen.kt`: a three-level drill-down of
//   hub list → hub detail (rooms + browse) → room chat.
//
// State split, matching every other tab:
//  - the hub LIST is a repo flow on the store (`store.rrcHubs`);
//  - per-hub volatile session state (connecting / welcomed / notices /
//    topics / the /list result) lives in `store.rrcHubStates`, folded
//    from the engine's RrcActivity event stream;
//  - per-hub room lists and per-room message history are repo flows,
//    observed by small per-screen observers (same shape as
//    `ConversationObserver`).
//
// The Rooms tab only appears when the `experimental.rrc` toggle is on
// (see ContentView). The shared RRC engine is wired on iOS via
// IosEngineFactory; the Kotlin↔Swift bridge is in IosEngineFactory.kt.

import Shared
import SwiftUI

/// Navigation value for a room-chat push. A plain struct (not the
/// Kotlin StoredRrcRoom) so it is cleanly Hashable for NavigationPath.
struct RoomRef: Hashable {
    let hubHash: String
    let room: String
}

// ---- hub list ---------------------------------------------------------

struct RoomsView: View {
    @EnvironmentObject private var store: ReticulumStore
    @State private var path = NavigationPath()
    @State private var showAddHub = false
    @State private var pendingDelete: StoredRrcHub?

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if store.rrcHubs.isEmpty {
                    ContentUnavailableView {
                        Label("No RRC hubs", systemImage: "bubble.left.and.bubble.right")
                    } description: {
                        Text("Tap + to add a hub by its destination hash, or promote an rrc.hub from the Nodes tab.")
                    }
                } else {
                    List {
                        // Tap opens the hub; long-press deletes it
                        // (→ confirm dialog) — no inline trash button
                        // (docs/REDESIGN.md §6).
                        ForEach(store.rrcHubs, id: \.destHash) { hub in
                            RrcHubRow(
                                hub: hub,
                                state: store.rrcHubStates[hub.destHash],
                                unread: store.rrcUnreadForHub(hub.destHash)
                            )
                                .contentShape(Rectangle())
                                .onTapGesture { path.append(hub.destHash as String) }
                                .onLongPressGesture(minimumDuration: 0.4) { pendingDelete = hub }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Rooms")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAddHub = true } label: { Image(systemName: "plus") }
                }
            }
            .navigationDestination(for: String.self) { hubHash in
                if let hub = store.rrcHubs.first(where: { $0.destHash == hubHash }) {
                    RrcHubDetailView(hub: hub, path: $path)
                } else {
                    ContentUnavailableView(
                        "Hub not found",
                        systemImage: "questionmark.circle",
                        description: Text("This hub is no longer in the local store.")
                    )
                }
            }
            .navigationDestination(for: RoomRef.self) { ref in
                if let hub = store.rrcHubs.first(where: { $0.destHash == ref.hubHash }) {
                    RrcRoomChatView(hub: hub, room: ref.room)
                } else {
                    ContentUnavailableView(
                        "Hub not found",
                        systemImage: "questionmark.circle",
                        description: Text("This hub is no longer in the local store.")
                    )
                }
            }
        }
        .sheet(isPresented: $showAddHub) {
            AddRrcHubSheet { hash, name, nick in
                store.addRrcHub(destHash: hash, displayName: name, nick: nick)
            }
        }
        // Open-RRC-hub deep-link from a destination detail sheet or
        // any other path that hands a hub hash to the store. ContentView
        // already switched the tab; we push the hub onto our stack.
        .onChange(of: store.openRrcHubEvent) { _, new in
            guard let event = new else { return }
            if !path.isEmpty { path.removeLast(path.count) }
            path.append(event.hash)
        }
        .alert(
            "Delete this hub?",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            presenting: pendingDelete
        ) { hub in
            Button("Delete", role: .destructive) {
                store.deleteRrcHub(hubHash: hub.destHash)
                pendingDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: { hub in
            Text("Removes \(hub.displayName.isEmpty ? "this hub" : hub.displayName) and all its room history from this device.")
        }
    }
}

private struct RrcHubRow: View {
    let hub: StoredRrcHub
    let state: RrcHubState?
    /// Everything unread anywhere on this hub — the hub row is the only
    /// place it shows before the user drills in, and it goes red if any
    /// room under it holds a mention.
    var unread: (total: Int, mentions: Int) = (0, 0)

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(dotColor)
                .frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text(hub.displayName.isEmpty ? "(unnamed hub)" : hub.displayName)
                    .font(.body)
                    .foregroundStyle(.primary)
                Text(shortHash(hub.destHash))
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            RrcUnreadPill(total: unread.total, mentions: unread.mentions)
            Text(statusLabel)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private var dotColor: Color {
        if state?.welcomed == true { return .green }
        if state?.connecting == true { return .orange }
        return .secondary
    }

    private var statusLabel: String {
        if state?.welcomed == true { return "Connected" }
        if state?.connecting == true { return "Connecting…" }
        return "Offline"
    }
}

// ---- add-hub sheet ----------------------------------------------------

private struct AddRrcHubSheet: View {
    /// (destHash, displayName, nick?) — nick is nil when left blank.
    let onAdd: (String, String, String?) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var hash = ""
    @State private var name = ""
    @State private var nick = ""

    /// Lower-cased, separator-stripped hash candidate — same cleaning
    /// the Nodes-tab manual-add does.
    private var cleanedHash: String {
        hash.lowercased().filter { $0 != ":" && $0 != " " && $0 != "-" }
    }
    private var validHash: Bool {
        cleanedHash.count == 32 && cleanedHash.allSatisfy { $0.isHexDigit }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Hub destination hash") {
                    TextField("32 hex characters", text: $hash)
                        .font(.body.monospaced())
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section("Display") {
                    TextField("Hub name (optional)", text: $name)
                    TextField("Your nick (optional)", text: $nick)
                }
                Section {
                    Text("The nick is the name shown next to your messages on this hub. You can change it later from the hub screen.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Add RRC hub")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        let display = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        onAdd(
                            cleanedHash,
                            display.isEmpty ? "RRC hub" : display,
                            nick.isEmpty ? nil : nick,
                        )
                        dismiss()
                    }
                    .disabled(!validHash)
                }
            }
        }
    }
}

// ---- hub detail -------------------------------------------------------

struct RrcHubDetailView: View {
    let hub: StoredRrcHub
    @Binding var path: NavigationPath
    @EnvironmentObject private var store: ReticulumStore

    @StateObject private var rooms = RrcRoomsObserver()
    @State private var joinName = ""
    @State private var showBrowse = false
    @State private var showEditNick = false
    @State private var nickDraft = ""
    @State private var pendingRoomDelete: StoredRrcRoom?

    private var state: RrcHubState? { store.rrcHubStates[hub.destHash] }
    private var welcomed: Bool { state?.welcomed == true }

    var body: some View {
        VStack(spacing: 0) {
            connectionRow
            nickRow
            if let notice = state?.lastNotice {
                Text(notice)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 4)
            }
            Divider()

            if welcomed {
                joinRow
                Divider()
            }

            if rooms.rooms.isEmpty {
                Spacer()
                Text(welcomed
                     ? "Connected. Join a room above, or browse what's available."
                     : "Connect to the hub to join rooms.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(24)
                Spacer()
            } else {
                List(rooms.rooms, id: \.name) { room in
                    // Tap opens the room chat; long-press removes it
                    // (→ confirm dialog). The inline Join/Leave button
                    // stays — see docs/REDESIGN.md §6.
                    RrcRoomRow(
                        room: room,
                        welcomed: welcomed,
                        unread: store.rrcUnread[ReticulumStore.rrcKey(hub.destHash, room.name)],
                        topic: store.rrcHubStates[hub.destHash]?.roomTopics[room.name],
                        onOpen: { path.append(RoomRef(hubHash: hub.destHash, room: room.name)) },
                        onJoin: { store.joinRrcRoom(hubHash: hub.destHash, room: room.name) },
                        onLeave: { store.partRrcRoom(hubHash: hub.destHash, room: room.name) },
                    )
                    .contentShape(Rectangle())
                    .onLongPressGesture(minimumDuration: 0.4) { pendingRoomDelete = room }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle(state?.hubName ?? (hub.displayName.isEmpty ? "Hub" : hub.displayName))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { rooms.start(repos: store.repos, scope: store.scope, hubHash: hub.destHash) }
        .onDisappear { rooms.stop() }
        .sheet(isPresented: $showBrowse) {
            BrowseRoomsSheet(hubHash: hub.destHash)
        }
        .alert("Your nick on this hub", isPresented: $showEditNick) {
            TextField("Nick", text: $nickDraft)
            Button("Save") {
                store.setRrcHubNick(hubHash: hub.destHash, nick: nickDraft)
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("The name shown next to your messages. Leave empty to send unnamed. Takes effect the next time you connect.")
        }
        .alert(
            "Remove this room?",
            isPresented: Binding(
                get: { pendingRoomDelete != nil },
                set: { if !$0 { pendingRoomDelete = nil } }
            ),
            presenting: pendingRoomDelete
        ) { room in
            Button("Remove", role: .destructive) {
                store.deleteRrcRoom(hubHash: hub.destHash, room: room.name)
                pendingRoomDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingRoomDelete = nil }
        } message: { room in
            Text("Removes #\(room.name) and its message history from this device. If you're a member, you'll also leave it on the hub.")
        }
    }

    private var connectionRow: some View {
        HStack(spacing: 10) {
            Text(shortHash(hub.destHash))
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Spacer()
            if welcomed {
                Button("Disconnect") { store.closeRrcSession(hubHash: hub.destHash) }
                    .buttonStyle(.bordered)
            } else if state?.connecting == true {
                ProgressView()
            } else {
                Button("Connect") { store.openRrcSession(hubHash: hub.destHash) }
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }

    private var nickRow: some View {
        HStack {
            Text("Your nick: \(hub.nick ?? "(not set)")")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            Button("Edit") {
                nickDraft = hub.nick ?? ""
                showEditNick = true
            }
            .font(.caption)
        }
        .padding(.horizontal, 14)
        .padding(.bottom, 4)
    }

    private var joinRow: some View {
        VStack(spacing: 4) {
            HStack {
                TextField("Room name", text: $joinName)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Join") {
                    store.joinRrcRoom(hubHash: hub.destHash, room: joinName)
                    joinName = ""
                }
                .disabled(joinName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            Button("Browse available rooms") {
                store.browseRrcRooms(hubHash: hub.destHash)
                showBrowse = true
            }
            .font(.caption)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}

private struct RrcRoomRow: View {
    let room: StoredRrcRoom
    let welcomed: Bool
    var unread: RrcRoomUnread? = nil
    var topic: String? = nil
    let onOpen: () -> Void
    let onJoin: () -> Void
    let onLeave: () -> Void

    private var statusLabel: String {
        switch (room.joined, room.notifyMode) {
        case (true, "none"): return "Joined · muted"
        case (true, "mentions"): return "Joined · mentions only"
        case (true, _): return "Joined"
        default: return "Not joined"
        }
    }

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("#\(room.name)")
                    .font(.body)
                    .fontWeight((unread?.total ?? 0) > 0 ? .bold : .regular)
                    .foregroundStyle(.primary)
                if let topic, !topic.isEmpty {
                    Text(topic)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Text(statusLabel)
                    .font(.caption2)
                    .foregroundStyle(room.joined ? Color.accentColor : .secondary)
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)
            Spacer()
            if let unread {
                RrcUnreadPill(total: Int(unread.total), mentions: Int(unread.mentions))
            }
            if welcomed {
                if room.joined {
                    // Even when the local DB says we're joined, the hub
                    // may have forgotten our membership across a
                    // session bounce (kline, hub restart, link timeout
                    // we didn't notice). The engine's on-Welcome auto-
                    // rejoin handles the common case, but when it
                    // misses, the user is stuck: Leave is the only
                    // visible action and that's destructive. The
                    // "Rejoin" overflow menu re-issues a JOIN against
                    // the hub idempotently — no state mutation, no
                    // history loss. Tester report: "messages going out
                    // but nothing arriving" turned out to be exactly
                    // this drift; without Rejoin the only recovery
                    // was Leave-then-Join, which clears local row +
                    // re-creates it.
                    Menu {
                        Button("Rejoin", action: onJoin)
                        Button("Leave", role: .destructive, action: onLeave)
                    } label: {
                        Text("Joined")
                    }
                    .buttonStyle(.borderless)
                } else {
                    Button("Join", action: onJoin).buttonStyle(.borderless)
                }
            }
        }
    }
}

// ---- browse-rooms sheet ----------------------------------------------

private struct BrowseRoomsSheet: View {
    let hubHash: String
    @EnvironmentObject private var store: ReticulumStore
    @Environment(\.dismiss) private var dismiss

    private var rooms: [RrcRoomListing]? { store.rrcHubStates[hubHash]?.availableRooms }

    var body: some View {
        NavigationStack {
            Group {
                if let rooms = rooms {
                    if rooms.isEmpty {
                        ContentUnavailableView(
                            "No public rooms",
                            systemImage: "tray",
                            description: Text("This hub has no registered public rooms. You can still join a room directly by name.")
                        )
                    } else {
                        List(rooms, id: \.name) { room in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("#\(room.name)").font(.body)
                                    if let topic = room.topic, !topic.isEmpty {
                                        Text(topic)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                Button("Join") {
                                    store.joinRrcRoom(hubHash: hubHash, room: room.name)
                                }
                                .buttonStyle(.borderless)
                            }
                        }
                    }
                } else {
                    VStack(spacing: 10) {
                        ProgressView()
                        Text("Asking the hub…").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Available rooms")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}

// ---- room chat --------------------------------------------------------

struct RrcRoomChatView: View {
    let hub: StoredRrcHub
    let room: String
    @EnvironmentObject private var store: ReticulumStore

    @StateObject private var observer = RrcRoomMessagesObserver()
    @State private var showMembers = false

    private var state: RrcHubState? { store.rrcHubStates[hub.destHash] }

    /// The draft lives in the store, not in @State: this view is pushed
    /// and popped by the navigation stack, so anything it owns dies the
    /// moment the user steps back — including a half-written message.
    private var draft: Binding<String> {
        Binding(
            get: { store.rrcDraft(hubHash: hub.destHash, room: room) },
            set: { store.setRrcDraft(hubHash: hub.destHash, room: room, text: $0) }
        )
    }

    /// Reply anchors resolve within THIS room only — a K_ID is 8
    /// sender-chosen random bytes with no uniqueness guarantee, so a
    /// wider lookup could point a reply at an unrelated message
    /// (rrc-extensions.md §5).
    private var byMsgId: [String: StoredRrcMessage] {
        Dictionary(
            observer.messages.compactMap { m in m.msgId.map { ($0, m) } },
            uniquingKeysWith: { _, last in last }
        )
    }

    /// Last nick seen per identity — what resolves a reactor to a name.
    private var nickByHash: [String: String] {
        var out: [String: String] = [:]
        for m in observer.messages {
            if let n = m.nick, !n.isEmpty, !m.senderIdHash.isEmpty {
                out[m.senderIdHash] = n
            }
        }
        return out
    }

    private var replyTargetId: String? {
        store.rrcReplyTargets[ReticulumStore.rrcKey(hub.destHash, room)]
    }

    var body: some View {
        VStack(spacing: 0) {
            if let topic = state?.roomTopics[room], !topic.isEmpty {
                Text(topic)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(Color.gray.opacity(0.12))
            }
            ScrollViewReader { proxy in
                List(observer.messages, id: \.id) { msg in
                    RrcMessageBubble(
                        msg: msg,
                        quoted: msg.replyToMsgId.flatMap { byMsgId[$0] },
                        ourIdentityHex: store.ourIdentityHash ?? "",
                        nicks: nickByHash,
                        onReply: {
                            if let id = msg.msgId {
                                store.setRrcReplyTarget(hubHash: hub.destHash, room: room, msgId: id)
                            }
                        },
                        onReact: { emoji in
                            guard let id = msg.msgId else { return }
                            let holders = IosEngineFactoryKt
                                .decodeRrcReactions(json: msg.reactionsJson)[emoji] ?? []
                            let mine = store.ourIdentityHash.map { holders.contains($0) } ?? false
                            store.sendRrcReaction(
                                hubHash: hub.destHash, room: room,
                                targetMsgId: id, emoji: emoji, retract: mine
                            )
                        }
                    )
                    .listRowSeparator(.hidden)
                    .id(msg.id)
                }
                .listStyle(.plain)
                .scrollDismissesKeyboard(.immediately)
                .onChange(of: observer.messages.count) { _, _ in
                    if let last = observer.messages.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                    // Anything on screen is read.
                    store.markRrcRoomRead(hubHash: hub.destHash, room: room)
                }
            }
            Divider()
            if let anchor = replyTargetId {
                let target = byMsgId[anchor]
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Replying to \(target?.nick ?? target.map { String($0.senderIdHash.prefix(8)) } ?? "a message")")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        if let target {
                            Text(target.text)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    Spacer()
                    Button {
                        store.setRrcReplyTarget(hubHash: hub.destHash, room: room, msgId: nil)
                    } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(Color.gray.opacity(0.12))
            }
            HStack {
                TextField("Message #\(room)  ·  / for commands", text: draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)
                Button {
                    store.sendRrcMessage(hubHash: hub.destHash, room: room, text: draft.wrappedValue)
                    store.setRrcDraft(hubHash: hub.destHash, room: room, text: "")
                    dismissKeyboard()
                } label: {
                    Image(systemName: "paperplane.fill")
                }
                .disabled(
                    state?.welcomed != true
                    || draft.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                )
            }
            .padding(8)
        }
        .navigationTitle("#\(room)")
        .navigationBarTitleDisplayMode(.inline)
        .keyboardDoneToolbar()
        .toolbar {
            // Rejoin escape hatch — re-sends a JOIN against the hub
            // without leaving the room first. Useful when the engine's
            // on-Welcome auto-rejoin missed a state-drift and inbound
            // messages stopped flowing despite the local row showing
            // joined=true. Disabled until the hub session is welcomed
            // so it can't fire into a half-open link.
            ToolbarItem(placement: .topBarTrailing) {
                Button("Rejoin") {
                    store.joinRrcRoom(hubHash: hub.destHash, room: room)
                }
                .disabled(state?.welcomed != true)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showMembers = true
                } label: {
                    Image(systemName: "person.2")
                }
                .disabled((state?.roomMembers[room]?.isEmpty ?? true)
                          && (state?.roomRoster[room]?.isEmpty ?? true))
            }
        }
        .sheet(isPresented: $showMembers) {
            RrcMembersSheet(
                room: room,
                members: state?.roomMembers[room] ?? [],
                roster: state?.roomRoster[room] ?? []
            )
        }
        .onAppear {
            observer.start(repos: store.repos, scope: store.scope, hubHash: hub.destHash, room: room)
            store.markRrcRoomRead(hubHash: hub.destHash, room: room)
        }
        .onDisappear { observer.stop() }
    }
}

/// Signal-style tap-back palette — the same six the Android bubbles
/// offer, in the same order.
private let rrcReactionPalette = ["\u{1F44D}", "\u{2764}\u{FE0F}", "\u{1F602}",
                                  "\u{1F62E}", "\u{1F622}", "\u{1F64F}"]

private struct RrcMessageBubble: View {
    let msg: StoredRrcMessage
    var quoted: StoredRrcMessage? = nil
    var ourIdentityHex: String = ""
    var nicks: [String: String] = [:]
    var onReply: () -> Void = {}
    var onReact: (String) -> Void = { _ in }
    @State private var showReactors = false

    private var outgoing: Bool { msg.direction == "outgoing" }
    private var system: Bool { msg.direction == "system" }
    private var isError: Bool { msg.direction == "error" }
    /// A `/me`, carried verbatim with its prefix (the hub does not
    /// command-dispatch an ACTION, which is why the slash survives).
    private var action: Bool { msg.text.hasPrefix("/me ") || msg.text == "/me" }
    /// Reactions need a target the hub can address — our own envelope
    /// id. A row stored before this shipped has none.
    private var canAnchor: Bool { !(msg.msgId ?? "").isEmpty }
    /// Not your own messages — the same rule the direct-message bubbles
    /// follow. Every reaction is a message on a shared mesh, and a
    /// self-reaction is a UX foot-gun with no clear use case. Reactions
    /// OTHERS left on your message still render.
    private var canReact: Bool { canAnchor && !outgoing }

    private var reactions: [String: [String]] {
        IosEngineFactoryKt.decodeRrcReactions(json: msg.reactionsJson)
    }

    var body: some View {
        if system || isError {
            // A /-command the user ran, or the hub's reply to one. An
            // `error` row is the hub refusing something, so it reads as
            // a refusal rather than as information.
            Text(msg.text)
                .font(.caption)
                .italic()
                .foregroundStyle(isError ? Color.red : Color.secondary)
                .frame(maxWidth: .infinity, alignment: .center)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
                .padding(.vertical, 3)
                .textSelection(.enabled)
        } else if action {
            // "* alice waves" — about the room, not addressed to it,
            // so it gets no bubble and no side.
            Text("* \(senderLabel) \(msg.text.dropFirst(3).trimmingCharacters(in: .whitespaces))")
                .font(.callout)
                .italic()
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 3)
                .textSelection(.enabled)
        } else {
            bubble
        }
    }

    private var bubble: some View {
        HStack {
            if outgoing { Spacer(minLength: 40) }
            VStack(alignment: outgoing ? .trailing : .leading, spacing: 3) {
                VStack(alignment: outgoing ? .trailing : .leading, spacing: 3) {
                    if !outgoing {
                        Text(senderLabel)
                            .font(.caption2.bold())
                            .foregroundStyle(Color.accentColor)
                    }
                    // The line this replies to. A reply whose target we
                    // don't hold still renders — as an ordinary message
                    // with a note, which is what §3 asks for.
                    if let quoted {
                        HStack(spacing: 6) {
                            Rectangle()
                                .fill(Color.secondary.opacity(0.6))
                                .frame(width: 2)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(quoted.nick ?? String(quoted.senderIdHash.prefix(8)))
                                    .font(.caption2.bold())
                                Text(quoted.text)
                                    .font(.caption2)
                                    .lineLimit(2)
                            }
                            .foregroundStyle(.secondary)
                        }
                        .fixedSize(horizontal: false, vertical: true)
                    } else if msg.replyToMsgId != nil {
                        Text("\u{21A9} replying to an earlier message")
                            .font(.caption2)
                            .italic()
                            .foregroundStyle(.secondary)
                    }
                    Text(msg.text)
                        .textSelection(.enabled)
                        .foregroundStyle(outgoing ? .white : .primary)
                    Text(timeLabel)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 14)
                        .fill(bubbleFill)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(msg.mention ? Color.orange : Color.clear, lineWidth: 1)
                )
                .contextMenu {
                    if canReact {
                        ForEach(rrcReactionPalette, id: \.self) { emoji in
                            Button(emoji) { onReact(emoji) }
                        }
                    }
                    if canAnchor {
                        Button("Reply") { onReply() }
                    }
                    if !reactions.isEmpty {
                        Button("Who reacted") { showReactors = true }
                    }
                    Button("Copy text") { UIPasteboard.general.string = msg.text }
                }

                // Aggregated reaction chips. Tapping one toggles OUR
                // entry: apply if we're not in it, retract if we are —
                // the two idempotent operations the wire format
                // defines, never a blind toggle.
                let chips = reactions
                if !chips.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(chips.keys.sorted(), id: \.self) { emoji in
                            let holders = chips[emoji] ?? []
                            let mine = !ourIdentityHex.isEmpty && holders.contains(ourIdentityHex)
                            Button {
                                if canReact { onReact(emoji) }
                            } label: {
                                HStack(spacing: 3) {
                                    Text(emoji).font(.caption)
                                    if holders.count > 1 {
                                        Text("\(holders.count)")
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .padding(.horizontal, 6)
                                .padding(.vertical, 1)
                                .background(
                                    Capsule().fill(
                                        mine ? Color.accentColor.opacity(0.25)
                                             : Color.gray.opacity(0.18)
                                    )
                                )
                            }
                            .buttonStyle(.plain)
                            .onLongPressGesture { showReactors = true }
                        }
                    }
                }
            }
            if !outgoing { Spacer(minLength: 40) }
        }
        .sheet(isPresented: $showReactors) {
            RrcReactorsSheet(
                reactions: reactions,
                nicks: nicks,
                ourIdentityHex: ourIdentityHex
            )
        }
    }

    private var bubbleFill: Color {
        if msg.mention { return Color.orange.opacity(0.22) }
        return outgoing ? Color.accentColor.opacity(0.85) : Color.gray.opacity(0.18)
    }

    /// Incoming sender label — the nick if the envelope carried one,
    /// else a short slice of the verified identity hash.
    private var senderLabel: String {
        if let n = msg.nick, !n.isEmpty { return n }
        return String(msg.senderIdHash.prefix(8))
    }

    private var timeLabel: String {
        let date = Date(timeIntervalSince1970: TimeInterval(msg.timestamp) / 1000)
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
}

/// Who is in the room. Identity hashes are what a JOINED member list
/// carries; nicknames only come from a `/who` reply, which is why both
/// are shown and why the hash is the one to trust — RRC nicknames are
/// advisory and not unique.
struct RrcMembersSheet: View {
    let room: String
    let members: [String]
    let roster: [String]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if !roster.isEmpty {
                    Section("Named (/who)") {
                        ForEach(roster, id: \.self) { name in
                            Text(name)
                        }
                    }
                }
                if !members.isEmpty {
                    Section("Identity hashes") {
                        ForEach(members, id: \.self) { hash in
                            Text(String(hash.prefix(16)))
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                Section {
                    Text("RRC nicknames are advisory and not unique — the hash is "
                         + "the identity. Name someone with @nick, or @ plus 6+ "
                         + "characters of their hash to be certain.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("In #\(room)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}

/// Who reacted, per emoji.
///
/// RRC can answer this precisely, which is worth saying out loud: a
/// reaction's `K_SRC` is rewritten by the hub to the *link-verified*
/// identity before fan-out, so attribution is exactly as trustworthy as
/// message authorship (`rrc-extensions.md` §3) — stronger than most
/// chat systems give it, and stronger than the LXMF side, where a
/// re-originating relay had to have attribution restored.
struct RrcReactorsSheet: View {
    let reactions: [String: [String]]
    let nicks: [String: String]
    let ourIdentityHex: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(reactions.keys.sorted(), id: \.self) { emoji in
                    Section("\(emoji)  \(reactions[emoji]?.count ?? 0)") {
                        ForEach(reactions[emoji] ?? [], id: \.self) { hash in
                            VStack(alignment: .leading, spacing: 1) {
                                Text(hash == ourIdentityHex ? "You"
                                     : (nicks[hash] ?? "(no nick seen)"))
                                Text(String(hash.prefix(16)))
                                    .font(.caption.monospaced())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Section {
                    Text("A reaction is attributed to the identity the hub verified "
                         + "on the link, so who reacted is as trustworthy as who "
                         + "sent the message. Nicknames are advisory; the hash is "
                         + "the identity.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Reactions")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}

/// The one unread badge in the Rooms tab, with the whole colour rule:
/// red means somebody named you (`@nick` / `@hashprefix`), and nothing
/// else does — ordinary unread traffic stays muted. Red stops meaning
/// anything the moment ordinary traffic can turn it red.
struct RrcUnreadPill: View {
    let total: Int
    let mentions: Int

    var body: some View {
        if total > 0 {
            Text(total > 99 ? "99+" : "\(total)")
                .font(.caption2.bold())
                .foregroundStyle(mentions > 0 ? Color.white : Color(.systemBackground))
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(
                    Capsule().fill(mentions > 0 ? Color.red : Color.secondary)
                )
        }
    }
}

// ---- per-screen flow observers ---------------------------------------

/// Subscribes to `repos.observeRrcRooms(hubHash)` while a hub-detail
/// view is on screen. Same shape as `ConversationObserver`.
@MainActor
final class RrcRoomsObserver: ObservableObject {
    @Published var rooms: [StoredRrcRoom] = []
    private var subscription: FlowSubscription?

    func start(repos: IosRepositories, scope: Kotlinx_coroutines_coreCoroutineScope, hubHash: String) {
        guard subscription == nil else { return }
        subscription = IosEngineFactoryKt.subscribe(
            repos.observeRrcRooms(hubHash: hubHash),
            scope: scope
        ) { [weak self] list in
            Task { @MainActor in
                self?.rooms = list as! [StoredRrcRoom]
            }
        }
    }

    func stop() {
        subscription?.cancel()
        subscription = nil
    }

    deinit { subscription?.cancel() }
}

/// Subscribes to `repos.observeRrcMessages(hubHash, room)` while a
/// room-chat view is on screen.
@MainActor
final class RrcRoomMessagesObserver: ObservableObject {
    @Published var messages: [StoredRrcMessage] = []
    private var subscription: FlowSubscription?

    func start(
        repos: IosRepositories,
        scope: Kotlinx_coroutines_coreCoroutineScope,
        hubHash: String,
        room: String,
    ) {
        guard subscription == nil else { return }
        subscription = IosEngineFactoryKt.subscribe(
            repos.observeRrcMessages(hubHash: hubHash, room: room),
            scope: scope
        ) { [weak self] list in
            Task { @MainActor in
                self?.messages = list as! [StoredRrcMessage]
            }
        }
    }

    func stop() {
        subscription?.cancel()
        subscription = nil
    }

    deinit { subscription?.cancel() }
}
