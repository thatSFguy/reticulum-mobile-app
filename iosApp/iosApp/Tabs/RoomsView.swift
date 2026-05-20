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
                            RrcHubRow(hub: hub, state: store.rrcHubStates[hub.destHash])
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
    let onOpen: () -> Void
    let onJoin: () -> Void
    let onLeave: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("#\(room.name)")
                    .font(.body)
                    .foregroundStyle(.primary)
                Text(room.joined ? "Joined" : "Not joined")
                    .font(.caption2)
                    .foregroundStyle(room.joined ? Color.accentColor : .secondary)
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)
            Spacer()
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
    @State private var draft = ""

    private var state: RrcHubState? { store.rrcHubStates[hub.destHash] }

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
                    RrcMessageBubble(msg: msg)
                        .listRowSeparator(.hidden)
                        .id(msg.id)
                }
                .listStyle(.plain)
                .scrollDismissesKeyboard(.immediately)
                .onChange(of: observer.messages.count) { _, _ in
                    if let last = observer.messages.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
            }
            Divider()
            HStack {
                TextField("Message #\(room)", text: $draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)
                Button {
                    store.sendRrcMessage(hubHash: hub.destHash, room: room, text: draft)
                    draft = ""
                    dismissKeyboard()
                } label: {
                    Image(systemName: "paperplane.fill")
                }
                .disabled(
                    state?.welcomed != true
                    || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
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
        }
        .onAppear {
            observer.start(repos: store.repos, scope: store.scope, hubHash: hub.destHash, room: room)
        }
        .onDisappear { observer.stop() }
    }
}

private struct RrcMessageBubble: View {
    let msg: StoredRrcMessage

    private var outgoing: Bool { msg.direction == "outgoing" }
    private var system: Bool { msg.direction == "system" }

    var body: some View {
        if system {
            // A /-command the user ran, or the hub's reply to one —
            // a centred italic line, not a chat bubble.
            Text(msg.text)
                .font(.caption)
                .italic()
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .center)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
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
                if !outgoing {
                    Text(senderLabel)
                        .font(.caption2.bold())
                        .foregroundStyle(Color.accentColor)
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
                    .fill(outgoing ? Color.accentColor.opacity(0.85) : Color.gray.opacity(0.18))
            )
            if !outgoing { Spacer(minLength: 40) }
        }
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
