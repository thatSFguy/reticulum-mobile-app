// SPDX-License-Identifier: MIT
//
// SwiftUI-side state holder for the iOS Reticulum app. Wraps the KMP
// `ReticulumEngine` + `IosRepositories` + `IosCryptoProvider` triad
// behind an `ObservableObject` so SwiftUI views observe via the
// standard `@EnvironmentObject` / `@Published` plumbing.
//
// The KMP layer is fully reactive (StateFlow / SharedFlow), but Swift
// can't await Kotlin `suspend` functions directly. We use the new
// `Flow.subscribe(scope:onEach:)` Kotlin helper to pipe each emission
// into a Swift closure, then hop onto `@MainActor` before mutating
// `@Published` state.
//
// One store per app lifetime, injected via `.environmentObject` from
// the iOSApp `@StateObject`. Holds long-lived subscriptions; calls
// `cancel()` on each in `deinit`.

import Combine
import CoreBluetooth
import Foundation
import Shared
import SwiftUI

// UserDefaults keys for the connection-state persistence feature —
// the iOS counterpart of Android's Preferences `last_transport_kind`
// / `ble_address` / `auto_reconnect`. The kind tags ("ble" / "tcp")
// mirror `ConnectionMemory.KIND_*` in commonMain. iOS has no
// Bluetooth-Classic transport, so only BLE + TCP are persisted.
private let kConnAutoReconnect = "connectivity.autoReconnect"
private let kConnLastKind = "connectivity.lastTransportKind"
private let kConnBleUuid = "connectivity.bleUuid"
private let kConnBleName = "connectivity.bleName"
// Destination hashes of RRC hubs with a live session — re-opened on a
// cold start once a transport is up. Mirrors Android's `live_rrc_hubs`.
private let kConnLiveRrcHubs = "connectivity.liveRrcHubs"
// Destination hashes the user pinned to the top of the Messages list.
// Pinning is a local-only concept, separate from the `favorite`
// (Contact) flag — see docs/REDESIGN.md §6. Mirrors Android's
// `pinned_conversations` preference; no DB column.
private let kPinnedConversations = "messages.pinnedConversations"

@MainActor
final class ReticulumStore: ObservableObject {

    // ---- Engine + repos -------------------------------------------------

    private let factory: IosEngineFactory
    var engine: ReticulumEngine { factory.engine }
    var repos: IosRepositories { factory.repos }
    /// Off-row attachment store (docs/ATTACHMENT-STORE.md), shared
    /// with the engine. The conversation bubble reads it to decode an
    /// image / load a file payload from its on-row token.
    var attachmentStore: AttachmentStore { factory.attachmentStore }
    /// Exposed so per-screen ObservableObjects (e.g.
    /// ConversationObserver) can spawn their own Flow subscriptions
    /// against the same long-lived scope the engine uses.
    var scope: Kotlinx_coroutines_coreCoroutineScope { factory.scope }

    /// One transport per kind (TCP, BLE). The IosBleTransport needs the
    /// same CBCentralManager that scanned the peripheral — see
    /// IosBleScanManager.central — so we hold a reference to the
    /// central across the connect call.
    private var tcpTransport: TcpInterface?
    private var bleTransport: IosBleTransport?

    /// Eagerly-instantiated BLE scanner. The CBCentralManager inside
    /// is what's tagged with our state-restoration identifier, so it
    /// HAS to exist at app launch (not just when SettingsView appears)
    /// — otherwise iOS can't fire willRestoreState during a cold
    /// background relaunch and the BLE link can't be recovered.
    /// SettingsView reads this via @EnvironmentObject instead of
    /// owning its own @StateObject.
    let bleScanner: IosBleScanManager = IosBleScanManager()

    /// OS-level reachability monitor. Two roles: gate the cold-start
    /// TCP reconnect on actual network availability, and surface
    /// involuntary disconnects so the UI doesn't sit on a stale
    /// "Connected" indicator after the path drops. See
    /// `NetworkPathMonitor.swift` for the full rationale.
    let pathMonitor: NetworkPathMonitor = NetworkPathMonitor()

    /// Combine subscription on `pathMonitor.$isReachable`. Reacts to
    /// transitions (path-up → auto-reconnect TCP if we have a saved
    /// target and no live transport; path-down → tear down the dead
    /// TCP socket so the kernel can reclaim and the UI can flag
    /// "Disconnected").
    private var pathChangeCancellable: AnyCancellable?

    // ---- Published state ------------------------------------------------

    /// Per-kind connection list — drives the multi-line status display
    /// in Settings. Empty when nothing is attached.
    @Published var connections: [ReticulumEngine.ConnectionState] = []

    /// Our own destination hash, hex. Nil until the engine has loaded /
    /// generated an identity (lazy on first attach).
    @Published var ourDestHash: String?

    /// User-visible error from the most recent connect attempt. Cleared
    /// on next successful attach.
    @Published var lastConnectError: String?

    /// User-visible error from the most recent QR-import attempt — set
    /// when `applyIdentityCard` rejects a forged card (SPEC §4.5
    /// destHash↔publicKey binding check, landed in v1.0.81). NodesView
    /// presents this as a modal `.alert`; pre-v1.0.81 the rejection
    /// only landed in `lastConnectError`, which is rendered solely in
    /// Settings → TCP, so a refusal scanned from the Nodes tab was
    /// invisible to the user.
    @Published var lastQrImportError: String?
    func clearQrImportError() { lastQrImportError = nil }

    /// Starred messagable destinations. Pinned at the top of the
    /// Messages tab.
    @Published var favorites: [StoredDestination] = []

    /// Senders we've received from but haven't starred yet — drives
    /// the Messages tab's Inbox section.
    @Published var inbox: [StoredDestination] = []

    /// Destination hashes pinned to the top of the Messages list.
    /// Local-only, separate from the `favorite` flag (docs/REDESIGN.md
    /// §6) — persisted to UserDefaults, no DB migration. Seeded from
    /// UserDefaults in `init`.
    @Published var pinnedHashes: Set<String> = []

    /// True while a propagation auto-sync is running — drives the
    /// Messages-tab refresh-icon spinner. Mirrors the Android
    /// `propagationSyncing` flow.
    @Published var propagationSyncing: Bool = false

    /// Short result line from the most recent propagation sync
    /// ("Synced — N new" / "Synced — nothing new" / "Sync failed").
    /// Auto-clears ~6s after it's set. Mirrors `propagationSyncResult`.
    @Published var propagationSyncResult: String?

    /// The unified Messages-tab conversation list — favorites and
    /// inbox merged and de-duplicated, most-recently-seen first. The
    /// Signal-style single list that replaced the Contacts/Inbox split
    /// (docs/REDESIGN.md §6). iOS has no last-message-time projection,
    /// so `lastSeen` is the recency proxy.
    var conversations: [StoredDestination] {
        var seen = Set<String>()
        var out: [StoredDestination] = []
        for d in favorites + inbox where seen.insert(d.hash).inserted {
            out.append(d)
        }
        return out.sorted { $0.lastSeen > $1.lastSeen }
    }

    /// Every observed destination (favorites-first, lastSeen-DESC).
    /// Drives the Nodes / Nomad / Graph tabs.
    @Published var allDestinations: [StoredDestination] = []

    /// User-visible error from the most recent message send attempt.
    @Published var lastSendError: String?

    /// Engine event log — every Log line and MessageVerified event the
    /// engine emits, in arrival order. Drives the Diagnostics section
    /// of Settings so users can see exactly what's happening (parity
    /// with the Android log viewer; useful for debugging "messages
    /// don't send" without remote-attaching). Capped at 500 lines.
    @Published var logLines: [String] = []

    /// Verbose-log toggle. When false, wire-trace `rx ...` lines and
    /// other high-volume routine chatter are filtered out of
    /// [displayedLogLines]; the underlying [logLines] keeps everything
    /// so flipping the toggle on instantly reveals the verbose stream.
    /// Mirrors Android's `setVerboseLog`. Defaults to false — most
    /// users want the high-signal lines (`msg #N: ...`,
    /// `LINKREQUEST rejected: ...`, `tx failed on ...`) without the
    /// per-packet rx noise.
    @Published var verboseLog: Bool = false

    /// User-visible filtered slice of [logLines]. Updated whenever
    /// [logLines] or [verboseLog] changes; SwiftUI's @Published
    /// recomputes on either input.
    var displayedLogLines: [String] {
        if verboseLog { return logLines }
        return logLines.filter { !$0.hasPrefix("rx ") }
    }

    /// Live list of `lxmf.propagation` destinations seen in announces.
    /// Drives the Settings → Propagation section. Empty when no
    /// propagation node has announced — the UI surfaces an empty-state
    /// hint in that case.
    var propagationNodes: [StoredDestination] {
        allDestinations.filter { $0.appName == "lxmf.propagation" }
    }

    /// Fire-once "open this conversation" event. Drives tap-to-message
    /// from the Nodes tab → Messages tab navigation. UUID changes on
    /// every emit so observers re-trigger even when the user picks the
    /// same destination twice in a row. Mirrors the Android
    /// `pendingOpenContact` SharedFlow.
    struct OpenContactEvent: Equatable {
        let id: UUID
        let hash: String
    }
    @Published var openContactEvent: OpenContactEvent?

    /// Fire-once "open this RRC hub" event. Same shape as
    /// [OpenContactEvent] but for the Rooms tab — fired when a user
    /// taps "Open in Relay Chat" on a destination's detail sheet (or
    /// any other path that wants to deep-link into a hub). ContentView
    /// switches the tab; RoomsView pushes the hub onto its
    /// NavigationStack. Without this event the detail sheet only
    /// added the hub to the rrc_hubs table and left the user stranded
    /// on the originating tab — tester report: "Open in RRC button
    /// didn't work for him from the slide out".
    struct OpenRrcHubEvent: Equatable {
        let id: UUID
        let hash: String
    }
    @Published var openRrcHubEvent: OpenRrcHubEvent?

    /// Fire-once "open this Nomad page" event. Routed when the user
    /// taps a `<destHash>:/path` (or `nnn@<destHash>:/path`) link
    /// inside an LXMF message bubble — the LXMF linkifier converts
    /// the matched substring into a custom-scheme URL and the
    /// conversation view's `OpenURLAction` decodes + fires this
    /// event. ContentView switches the tab to Nomad (gated on
    /// `nomadEnabled`); NomadView observes and navigates to the
    /// destination + path. Mirrors the same pattern as
    /// [OpenContactEvent] and [OpenRrcHubEvent].
    struct OpenNomadPageEvent: Equatable {
        let id: UUID
        let hash: String
        let path: String
    }
    @Published var openNomadPageEvent: OpenNomadPageEvent?

    // ---- RRC (experimental Reticulum Relay Chat) ------------------------

    /// Known RRC hubs, most-recently-connected first. Backed by
    /// `repos.observeRrcHubs()`. Drives the Rooms tab's hub list.
    @Published var rrcHubs: [StoredRrcHub] = []

    /// Per-hub volatile session state, keyed by hub destination hash.
    /// Folded from the `EngineEvent.RrcActivity` stream — NOT persisted.
    /// Mirrors the Android `ReticulumViewModel._rrcHubStates`.
    @Published var rrcHubStates: [String: RrcHubState] = [:]

    // ---- KMP → SwiftUI subscriptions ------------------------------------

    private var subscriptions: [FlowSubscription] = []
    /// Combine cancellable for the willRestoreState → auto-reconnect
    /// handoff. Watches the scanner's restoredPeripherals @Published
    /// for its first non-empty emission and kicks off connectBle on
    /// the first peripheral. Single-shot via `.first()`.
    private var bleRestoreCancellable: AnyCancellable?

    init() {
        // Pass a Swift-side display-name provider that reads
        // UserDefaults each time the engine builds an announce. The
        // SettingsView's Display name TextField writes to the same
        // key (@AppStorage("displayName")), so an edit lands in the
        // next announce without restarting the engine. Mirrors the
        // Android pattern of `Preferences.getDisplayName()`.
        self.factory = IosEngineFactoryKt.createIosEngineFactoryWithProviders(
            displayName: {
                let raw = UserDefaults.standard.string(forKey: "displayName")?
                    .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                return raw.isEmpty ? "Reticulum Mobile" : raw
            },
            dropUnverified: {
                // Read live from UserDefaults — Settings toggle writes
                // to the same key (`security.dropUnverified`) so the
                // change applies to the very next inbound message
                // without restarting the engine. Audit reference:
                // 2026-05-13 MED-6.
                //
                // Boxed as `KotlinBoolean` because K/N exposes Kotlin
                // `() -> Boolean` function types to Swift as
                // `() -> KotlinBoolean` (primitives don't auto-unbox
                // through function-type returns the way they do
                // through direct method returns). Raw `Bool` returns
                // here fail compilation with "cannot convert value of
                // type 'Bool' to closure result type 'KotlinBoolean'".
                KotlinBoolean(bool: UserDefaults.standard.bool(forKey: "security.dropUnverified"))
            }
        )
        // Eagerly trim a bloated destinations table before the UI's
        // Flow observer subscribes. iOS uses SQLDelight rather than
        // Room so the Android-specific CursorWindow 2 MB crash
        // doesn't apply here — but capping the table is good hygiene
        // either way (faster Nodes-list renders, less memory). Audit
        // reference: 2026-05-13 MED-2 follow-up.
        Task { try? await factory.engine.evictDestinationsOnStartup() }

        // Orphan-GC the attachment store: delete any image / file
        // payload no message row still references — backstops a crash
        // between a conversation-delete and its file cleanup.
        // docs/ATTACHMENT-STORE.md §3.7.
        Task { try? await factory.engine.sweepAttachmentsOnStartup() }

        pinnedHashes = Set(UserDefaults.standard.stringArray(forKey: kPinnedConversations) ?? [])

        wireEngineSubscriptions()
        wireBleRestoration()
        wireNotificationDeepLinks()
        seedTcpDefaultsIfMissing()
        wireNetworkPathChanges()
        // Cold-start TCP restore is deferred to ContentView's first-
        // frame `.task` via `performStartupRestore()` so the iOS launch
        // watchdog can't fire if the saved hostname stalls in DNS
        // (or worse, in the kernel's getaddrinfo when the device is
        // offline). The path monitor's first emission settles before
        // ContentView's .task runs.
        scheduleRrcRestore()
    }

    /// Pin / unpin a conversation. Pure local state — separate from the
    /// `favorite` (Contact) flag (docs/REDESIGN.md §6). Persisted to
    /// UserDefaults so pins survive relaunch.
    func setPinned(hash: String, pinned: Bool) {
        if pinned { pinnedHashes.insert(hash) } else { pinnedHashes.remove(hash) }
        UserDefaults.standard.set(Array(pinnedHashes), forKey: kPinnedConversations)
    }

    /// First-launch seed for the TCP transport-node host/port. If
    /// nothing has been written to UserDefaults yet, pick a random
    /// entry from KnownTcpNodes.DEFAULTS (the curated, probe-verified
    /// rotation in commonMain) so each fresh install spreads attach
    /// load across operators instead of all hammering one. Subsequent
    /// launches keep whatever was persisted — the rotation only acts
    /// on truly-fresh state. User can re-roll explicitly via the
    /// Settings → TCP transport "Pick another" button. Mirrors the
    /// Android `Preferences.initialTcp` block exactly.
    private func seedTcpDefaultsIfMissing() {
        let d = UserDefaults.standard
        let existing = d.string(forKey: "tcp.host")?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard existing.isEmpty else { return }
        let pick = IosEngineFactoryKt.pickRandomTcpNode()
        d.set(pick.host, forKey: "tcp.host")
        d.set(Int(pick.port), forKey: "tcp.port")
    }

    /// Hook the IosNotifications helper into the store's
    /// openContactEvent flow. Two paths:
    /// - Cold launch from notification: the helper stashed the
    ///   contactHash on pendingDeepLink before the store existed;
    ///   we drain it now.
    /// - Warm tap (app already running): NotificationCenter posts
    ///   `.reticulumOpenContact`; we observe and route the same way.
    /// MessagesView's @onChange(of: store.openContactEvent) responds
    /// to either path identically.
    private func wireNotificationDeepLinks() {
        IosNotifications.shared.install()
        if let hash = IosNotifications.shared.consumePendingDeepLink() {
            self.openContactEvent = OpenContactEvent(id: UUID(), hash: hash)
        }
        NotificationCenter.default.addObserver(
            forName: .reticulumOpenContact,
            object: nil,
            queue: .main,
        ) { [weak self] note in
            guard let self,
                  let hash = note.userInfo?[IosNotifications.userInfoContactHash] as? String else { return }
            self.openContactEvent = OpenContactEvent(id: UUID(), hash: hash)
        }
    }

    /// Wire the willRestoreState handoff. When iOS relaunches us in
    /// the background to deliver a BLE event for a peripheral we were
    /// connected to before suspension, the scanner's
    /// CBCentralManagerDelegate.willRestoreState fires and stashes
    /// the peripheral list on `bleScanner.restoredPeripherals`. We
    /// observe that here and kick connectBle on the first peripheral
    /// so the LoRa link is back up before the user notices.
    /// First-non-empty only — the user can still pick a different
    /// device manually after that.
    private func wireBleRestoration() {
        bleRestoreCancellable = bleScanner.$restoredPeripherals
            .filter { !$0.isEmpty }
            .first()
            .sink { [weak self] peripherals in
                guard let self, self.bleTransport == nil, let p = peripherals.first else { return }
                let picked = DiscoveredPeripheral(peripheral: p, name: p.name, rssi: -127)
                self.connectBle(scanner: self.bleScanner, picked: picked)
            }
    }

    /// Persist a new display name and broadcast it via an immediate
    /// announce. Mirrors `ReticulumViewModel.setDisplayName` on
    /// Android, which calls `preferences.setDisplayName(name)` and
    /// then enqueues a `sendAnnounce()` on the engine scope so peers
    /// see the new label without waiting for the 5-min auto-announce.
    func setDisplayName(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        UserDefaults.standard.set(trimmed, forKey: "displayName")
        sendAnnounce()
    }

    deinit {
        subscriptions.forEach { $0.cancel() }
        factory.shutdown()
    }

    private func wireEngineSubscriptions() {
        // `subscribe` is a top-level Kotlin extension declared in
        // IosEngineFactory.kt; Kotlin/Native exports it as a static
        // method on `IosEngineFactoryKt`. Receiver becomes the first
        // arg; the closure runs on Dispatchers.Default so we hop to
        // @MainActor before mutating `@Published` state.
        let connSub = IosEngineFactoryKt.subscribe(
            engine.connections,
            scope: factory.scope
        ) { [weak self] list in
            Task { @MainActor in
                self?.connections = list as! [ReticulumEngine.ConnectionState]
            }
        }
        subscriptions.append(connSub)

        let favSub = IosEngineFactoryKt.subscribe(
            repos.observeFavorites(),
            scope: factory.scope
        ) { [weak self] list in
            Task { @MainActor in
                self?.favorites = list as! [StoredDestination]
            }
        }
        subscriptions.append(favSub)

        let inboxSub = IosEngineFactoryKt.subscribe(
            repos.observeInbox(),
            scope: factory.scope
        ) { [weak self] list in
            Task { @MainActor in
                self?.inbox = list as! [StoredDestination]
            }
        }
        subscriptions.append(inboxSub)

        // Engine event log — Log lines + MessageVerified events,
        // pattern-matched on the Kotlin side via engineEventToLogLine
        // so we don't have to guess at how K/N names sealed-class
        // subtypes in the generated Swift header (changes by compiler
        // version). Mirrors Android's ReticulumViewModel collector.
        // Keep last 500 entries so a misbehaving send loop doesn't
        // grow the array unboundedly.
        let logSub = IosEngineFactoryKt.subscribe(
            engine.events,
            scope: factory.scope
        ) { [weak self] event in
            guard let typed = event as? ReticulumEngine.EngineEvent else { return }
            // Two side-effects per event: (a) append to the in-app
            // diagnostic log if engineEventToLogLine surfaces a line
            // for it; (b) post a system notification if it's an
            // incoming-message event. Each branch goes through its
            // own Kotlin extractor so K/N's sealed-class subtype
            // mangling doesn't bite the Swift side.
            if let line = IosEngineFactoryKt.engineEventToLogLine(event: typed) {
                Task { @MainActor in
                    guard let self = self else { return }
                    var next = self.logLines
                    next.append(line)
                    if next.count > 500 {
                        next = Array(next.suffix(500))
                    }
                    self.logLines = next
                }
            }
            if let rrc = IosEngineFactoryKt.engineEventAsRrcActivity(event: typed) {
                Task { @MainActor in
                    self?.applyRrcActivity(rrc)
                }
            }
            if let info = IosEngineFactoryKt.engineEventAsIncomingMessage(event: typed) {
                Task { @MainActor in
                    IosNotifications.shared.post(info)
                    // Per-contact-aware badge recompute: count every
                    // incoming row whose timestamp is past the
                    // recipient contact's last-opened mark. The post
                    // above shows the banner; this updates the home-
                    // screen icon. The two are decoupled so a future
                    // change to the badge math (e.g. exclude
                    // unverified, weight by importance) can happen
                    // here without touching the notifier.
                    await self?.recomputeUnreadBadge()
                }
            }
        }
        subscriptions.append(logSub)

        let allSub = IosEngineFactoryKt.subscribe(
            repos.observeDestinations(),
            scope: factory.scope
        ) { [weak self] list in
            Task { @MainActor in
                self?.allDestinations = list as! [StoredDestination]
            }
        }
        subscriptions.append(allSub)

        let rrcHubsSub = IosEngineFactoryKt.subscribe(
            repos.observeRrcHubs(),
            scope: factory.scope
        ) { [weak self] list in
            Task { @MainActor in
                self?.rrcHubs = list as! [StoredRrcHub]
            }
        }
        subscriptions.append(rrcHubsSub)
    }

    // ---- TCP attach / detach -------------------------------------------

    /// Spin up a TCP transport against [host]:[port] and hand it to the
    /// engine. Mirrors what `ReticulumService.startTcp(...)` does on
    /// Android, minus the foreground-service plumbing — iOS uses the
    /// `bluetooth-central` background mode + state restoration in
    /// Phase 4 instead.
    func connectTcp(host: String, port: Int32) {
        Task {
            lastConnectError = nil
            do {
                // Tear down any prior TCP attachment first.
                if let prior = tcpTransport {
                    try await disconnectAsync(prior)
                }
                engine.logExternal(line: "TCP: connecting to \(host):\(port) (TCP handshake — DNS + 3-way ACK can take 30s+ on a slow path)")
                // Route every TcpInterface diagnostic line ("TCP:
                // socket ready", "TCP: read loop ended (remote closed)",
                // "TCP: read loop crashed: ...", "tx N B" for every
                // outbound packet) through the engine's event channel so
                // they land in the in-app Settings → Diagnostics log
                // alongside engine-originated entries. The previous
                // no-op closure (a leftover TODO) silently dropped every
                // TCP-layer line, which is exactly why the 2026-05-10
                // "TCP connects but no announces" investigation had
                // zero in-app log signal to work with.
                let transport = TcpInterface(
                    host: host,
                    port: port,
                    scope: factory.scope,
                    socketFactory: { h, p in TcpSocket(host: h, port: Int32(truncating: p)) },
                    txLogger: { [weak self] line in
                        self?.engine.logExternal(line: line)
                    }
                )
                try await transport.connect()
                engine.logExternal(line: "TCP: socket ready (keepalive on, NoDelay on)")
                tcpTransport = transport
                engine.attach(transport: transport, kind: .tcp)
                try await engine.ensureIdentity()
                await refreshOurDestHash()
                // Reached Connected — remember TCP as the cold-start
                // auto-reconnect target (host/port already mirror what
                // the user typed via the Settings @AppStorage fields,
                // but persist them here too so restore is consistent).
                let d = UserDefaults.standard
                d.set(host, forKey: "tcp.host")
                d.set(Int(port), forKey: "tcp.port")
                d.set("tcp", forKey: kConnLastKind)
            } catch {
                lastConnectError = "\(error)"
            }
        }
    }

    func disconnectTcp() {
        guard let t = tcpTransport else { return }
        // Explicit Disconnect — forget the auto-reconnect target so a
        // relaunch honours the user deliberately going offline.
        if UserDefaults.standard.string(forKey: kConnLastKind) == "tcp" {
            UserDefaults.standard.removeObject(forKey: kConnLastKind)
        }
        Task {
            engine.detach(kind: .tcp)
            try? await disconnectAsync(t)
            tcpTransport = nil
        }
    }

    private func disconnectAsync(_ transport: TcpInterface) async throws {
        try await transport.disconnect()
    }

    // ---- BLE attach / detach -------------------------------------------

    /// Attach the freshly-discovered NUS peripheral via [scanner]. The
    /// scanner's CBCentralManager is reused — IosBleTransport sets
    /// itself as the delegate, replacing the scanner's own delegate
    /// (the scanner stops emitting after this point, which is what we
    /// want anyway).
    func connectBle(scanner: IosBleScanManager, picked: DiscoveredPeripheral) {
        Task {
            lastConnectError = nil
            do {
                if let prior = bleTransport {
                    engine.detach(kind: .ble)
                    try? await prior.disconnect()
                }
                scanner.stopScan()
                let transport = IosBleTransport(
                    central: scanner.central,
                    peripheral: picked.peripheral,
                    scope: factory.scope
                )
                try await transport.connect()
                // Push the saved RNode radio config (freq, BW, SF, CR,
                // TX power) and turn the radio on. Without this the
                // RNode sits idle and announces never reach the air.
                // Mirrors Android's startBle path; failures are logged
                // and don't abort the BLE attach.
                let cfg = currentRadioConfig
                do {
                    try await transport.applyRadioConfig(config: cfg)
                } catch {
                    appendLog("RNode: radio config failed: \(error)")
                }
                bleTransport = transport
                engine.attach(transport: transport, kind: .ble)
                try await engine.ensureIdentity()
                await refreshOurDestHash()
                // Reached Connected — remember this RNode so the next
                // cold start can reconnect it. iOS has no MAC; the
                // peripheral's opaque CoreBluetooth UUID is stable per
                // device per install, and retrievePeripherals() takes
                // it back on launch.
                let d = UserDefaults.standard
                d.set(picked.peripheral.identifier.uuidString, forKey: kConnBleUuid)
                d.set(picked.name ?? "", forKey: kConnBleName)
                d.set("ble", forKey: kConnLastKind)
            } catch {
                lastConnectError = "\(error)"
            }
        }
    }

    /// Push the saved radio config to every BLE-attached RNode. UI
    /// "Save & apply" calls this after the user edits any field —
    /// matches Android's `ReticulumService.reapplyRadioConfig`.
    func reapplyRadioConfig() {
        guard let transport = bleTransport else { return }
        let cfg = currentRadioConfig
        Task {
            do {
                try await transport.applyRadioConfig(config: cfg)
                appendLog("RNode: radio re-applied at \(cfg.frequencyHz / 1_000_000) MHz, BW \(cfg.bandwidthHz / 1000) kHz, SF \(cfg.spreadingFactor), CR \(cfg.codingRate), \(cfg.txPowerDbm) dBm")
            } catch {
                appendLog("RNode: radio re-apply failed: \(error)")
            }
        }
    }

    /// Snapshot the radio config from UserDefaults. The SettingsView
    /// owns @AppStorage views into the same keys; reading once via
    /// UserDefaults.standard avoids a SwiftUI dependency in the store
    /// and matches whatever the user last saved.
    private var currentRadioConfig: RadioConfig {
        let d = UserDefaults.standard
        let freq = d.object(forKey: "radio.frequencyHz") as? Int64 ?? 904_375_000
        let bw   = d.object(forKey: "radio.bandwidthHz") as? Int64 ?? 250_000
        let sf   = d.object(forKey: "radio.spreadingFactor") as? Int ?? 10
        let cr   = d.object(forKey: "radio.codingRate") as? Int ?? 5
        let tx   = d.object(forKey: "radio.txPowerDbm") as? Int ?? 22
        return RadioConfig(
            frequencyHz: freq,
            bandwidthHz: bw,
            spreadingFactor: Int32(sf),
            codingRate: Int32(cr),
            txPowerDbm: Int32(tx)
        )
    }

    private func appendLog(_ line: String) {
        Task { @MainActor in
            self.logLines = (self.logLines + [line]).suffix(500).map { $0 }
        }
    }

    func disconnectBle() {
        guard let t = bleTransport else { return }
        // Explicit Disconnect — drop the saved auto-reconnect target.
        if UserDefaults.standard.string(forKey: kConnLastKind) == "ble" {
            UserDefaults.standard.removeObject(forKey: kConnLastKind)
        }
        bleAutoReconnectCancellable?.cancel()
        bleAutoReconnectCancellable = nil
        Task {
            engine.detach(kind: .ble)
            try? await t.disconnect()
            bleTransport = nil
        }
    }

    // ---- cold-start auto-reconnect -------------------------------------

    /// Combine subscription that waits for the BLE central to power on
    /// during a [restoreBle] before retrieving the saved peripheral.
    private var bleAutoReconnectCancellable: AnyCancellable?

    /// Combine subscription that waits for the first Connected
    /// transport before re-opening saved RRC hub sessions.
    private var rrcRestoreCancellable: AnyCancellable?

    /// Public entry point called from ContentView's first-frame
    /// `.task` — runs after the iOS launch transaction has completed,
    /// so anything slow in the restore path can't trip the launch
    /// watchdog. Awaits the path monitor's first emission so the TCP
    /// reachability gate doesn't fire prematurely against the default
    /// `false`, then defers to `restoreLastConnection`.
    func performStartupRestore() async {
        await pathMonitor.waitForFirstUpdate()
        restoreLastConnection()
    }

    /// React to OS-level network path transitions. Path-satisfied →
    /// auto-reconnect TCP if the user's saved target hasn't been
    /// restored yet. Path-unsatisfied → tear down the live TCP socket
    /// (it's already dead at the kernel level by the time the path
    /// monitor reports unsatisfied) so the UI flips to Disconnected
    /// and the next path-satisfied event can re-attach cleanly.
    ///
    /// `dropFirst(1)` skips the initial emit — that's the cold-start
    /// snapshot, handled by `performStartupRestore()`. We only want
    /// to react to *transitions* here.
    private func wireNetworkPathChanges() {
        pathChangeCancellable = pathMonitor.$isReachable
            .dropFirst(1)
            .removeDuplicates()
            .sink { [weak self] reachable in
                self?.handleNetworkPathChange(reachable: reachable)
            }
    }

    private func handleNetworkPathChange(reachable: Bool) {
        let d = UserDefaults.standard
        if reachable {
            engine.logExternal(line: "network: path satisfied")
            // Auto-reconnect when the user's saved transport was TCP
            // and we don't have a live socket. BLE doesn't need this
            // — the BLE central's own state callbacks drive its
            // reconnect.
            let autoReconnect = d.object(forKey: kConnAutoReconnect) as? Bool ?? true
            guard autoReconnect else { return }
            guard d.string(forKey: kConnLastKind) == "tcp",
                  tcpTransport == nil else { return }
            let host = d.string(forKey: "tcp.host") ?? ""
            let port = d.integer(forKey: "tcp.port")
            guard !host.isEmpty, port > 0, port <= 65_535 else { return }
            engine.logExternal(line: "network: auto-reconnect TCP \(host):\(port)")
            connectTcp(host: host, port: Int32(port))
        } else {
            engine.logExternal(line: "network: path unsatisfied — tearing down TCP")
            // Involuntary teardown: KEEP the saved auto-reconnect
            // target so the next path-satisfied transition brings the
            // session back. Only an explicit Disconnect (via
            // `disconnectTcp`) clears that target.
            guard let t = tcpTransport else { return }
            Task {
                engine.detach(kind: .tcp)
                try? await disconnectAsync(t)
                tcpTransport = nil
            }
        }
    }

    /// Cold-start auto-reconnect: re-establish the transport the app
    /// was last connected to. Honours the `connectivity.autoReconnect`
    /// opt-out (default on). TCP is gated on `pathMonitor.isReachable`
    /// — without a live network interface there's no point in
    /// blocking on `getaddrinfo`; the path-monitor sink will retry
    /// when the path becomes satisfied. BLE doesn't need the gate
    /// because the central's power-on callback is the equivalent
    /// signal there. The iOS counterpart of Android's
    /// `ReticulumService.restoreLastConnection`.
    private func restoreLastConnection() {
        let d = UserDefaults.standard
        let autoReconnect = d.object(forKey: kConnAutoReconnect) as? Bool ?? true
        guard autoReconnect else { return }
        switch d.string(forKey: kConnLastKind) {
        case "tcp":
            guard pathMonitor.isReachable else {
                engine.logExternal(line: "restore: TCP deferred — no network yet")
                return
            }
            let host = d.string(forKey: "tcp.host") ?? ""
            let port = d.integer(forKey: "tcp.port")
            guard !host.isEmpty, port > 0, port <= 65_535 else { return }
            engine.logExternal(line: "restore: reconnecting last TCP node \(host):\(port)")
            connectTcp(host: host, port: Int32(port))
        case "ble":
            restoreBle()
        default:
            break
        }
    }

    /// Re-establish the last BLE RNode. `retrievePeripherals` needs the
    /// central powered on, so watch `bluetoothReady` for its first true
    /// then retrieve the saved peripheral by UUID and connect. A no-op
    /// if a BLE transport is already up (e.g. iOS state-restoration —
    /// `wireBleRestoration` — got there first).
    private func restoreBle() {
        guard let uuid = UUID(uuidString: UserDefaults.standard.string(forKey: kConnBleUuid) ?? "") else {
            return
        }
        bleAutoReconnectCancellable = bleScanner.$bluetoothReady
            .filter { $0 }
            .first()
            .sink { [weak self] _ in
                guard let self, self.bleTransport == nil else { return }
                let found = self.bleScanner.central.retrievePeripherals(withIdentifiers: [uuid])
                guard let peripheral = found.first else {
                    self.engine.logExternal(line: "restore: saved BLE RNode \(uuid.uuidString) not retrievable")
                    return
                }
                self.engine.logExternal(line: "restore: reconnecting last BLE RNode \(uuid.uuidString)")
                let savedName = UserDefaults.standard.string(forKey: kConnBleName)
                let picked = DiscoveredPeripheral(
                    peripheral: peripheral,
                    name: (savedName?.isEmpty ?? true) ? peripheral.name : savedName,
                    rssi: -127
                )
                self.connectBle(scanner: self.bleScanner, picked: picked)
            }
    }

    // ---- Identity helpers ----------------------------------------------

    private func refreshOurDestHash() async {
        let hashBytes = try? await engine.ourDestHash()
        guard let bytes = hashBytes else { return }
        // `byteArrayToHex` lives in iosMain (IosEngineFactory.kt) — a
        // plain top-level function is a more stable Swift bridge than
        // calling the `ByteArray.toHex()` extension across the
        // Kotlin/Native interop boundary.
        ourDestHash = IosEngineFactoryKt.byteArrayToHex(bytes: bytes)
    }

    // ---- Messaging -----------------------------------------------------

    // ---- Nodes-tab actions ---------------------------------------------

    func toggleFavorite(hash: String, favorite: Bool) {
        Task { try? await engine.setFavorite(hashHex: hash, favorite: favorite) }
    }

    /// Request navigation into the conversation for [hash]. ContentView
    /// switches to Messages, MessagesView pushes the conversation onto
    /// its NavigationStack.
    func openContact(hash: String) {
        openContactEvent = OpenContactEvent(id: UUID(), hash: hash)
    }

    /// Fire the open-RRC-hub deep-link event. ContentView switches to
    /// the Rooms tab; RoomsView pushes the hub's chat onto its
    /// NavigationStack. Mirrors [openContact].
    func openRrcHub(hash: String) {
        openRrcHubEvent = OpenRrcHubEvent(id: UUID(), hash: hash)
    }

    /// Fire the open-Nomad-page deep-link event. ContentView
    /// switches to the Nomad tab (when enabled); NomadView observes
    /// and navigates to the destination + path. Triggered by
    /// `<destHash>:/path` taps in LXMF message bubbles.
    func openNomadPage(hash: String, path: String) {
        openNomadPageEvent = OpenNomadPageEvent(id: UUID(), hash: hash, path: path)
    }

    /// Clear the in-memory diagnostic log. Wired to the Settings →
    /// Diagnostics "Clear" button. Doesn't affect engine state — just
    /// resets the published display list.
    func clearLog() {
        logLines = []
    }

    // ---- Identity admin actions ----------------------------------------

    /// Force an immediate announce on every attached transport.
    /// Used by Settings → "Send announce" — bypasses the engine's
    /// re-announce throttle so the user can prod a transport node into
    /// learning a fresh path back to us.
    func sendAnnounce() {
        // Kotlin default arg `asPathResponse: Boolean = false` doesn't
        // synthesize a no-arg Swift overload via the K/N bridge — must
        // pass explicitly. `false` for user-initiated announces (path-
        // response is only for the engine's own reply path).
        Task { try? await engine.sendAnnounce(asPathResponse: false) }
    }

    /// Wipe the on-device identity and generate a fresh keypair.
    /// Existing message history stays (it's keyed by contactHash, not
    /// our identity), but our destination hash changes — anyone
    /// messaging the old hash won't reach us. Caller MUST have
    /// confirmed user intent at the UI layer.
    func resetIdentity() {
        Task {
            do {
                try await engine.resetIdentity()
                await refreshOurDestHash()
            } catch {
                lastConnectError = "\(error)"
            }
        }
    }

    /// Pending task that clears `propagationSyncResult` a few seconds
    /// after a sync finishes — cancelled + replaced on every sync so a
    /// rapid re-tap doesn't clear the newer result early.
    private var syncResultClearTask: Task<Void, Never>?

    /// Sync messages from the closest propagation node. Tracks
    /// `propagationSyncing` (drives the Messages-tab refresh spinner)
    /// and folds the outcome into `propagationSyncResult` as a short
    /// human line. Re-taps mid-sync are ignored. Mirrors the Android
    /// `ReticulumViewModel.syncPropagationAuto`.
    func syncPropagationAuto() {
        guard !propagationSyncing else { return }
        propagationSyncing = true
        propagationSyncResult = nil
        syncResultClearTask?.cancel()
        // Honor the Settings → Connection → Propagation picker: when the
        // user nailed down a specific node, talk to that one only. Empty
        // pref falls back to the hop-ranked auto cascade. A stale pick
        // surfaces the engine's "Unknown propagation node" error rather
        // than silently swapping strategies, so the user notices and
        // re-picks. Mirrors Android `ReticulumViewModel.syncPropagationAuto`.
        let preferred = (UserDefaults.standard.string(forKey: "propagation.preferredHash") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        Task {
            let resultText: String
            do {
                let res: ReticulumEngine.PropagationSyncResult
                if preferred.isEmpty {
                    // Kotlin default arg `maxAttempts: Int = 5` doesn't
                    // surface as a no-arg Swift overload — pass the same
                    // default explicitly (5 candidates is what Android
                    // also uses).
                    res = try await engine.syncPropagationAuto(maxAttempts: 5)
                } else {
                    res = try await engine.syncPropagation(
                        propagationNodeHash: preferred,
                        proofTimeoutMs: 45_000,
                        roundTimeoutMs: 30_000,
                    )
                }
                if let err = res.errorMessage {
                    resultText = "Sync failed: \(err)"
                } else if res.messagesStored > 0 {
                    resultText = "Synced — \(res.messagesStored) new message"
                        + (res.messagesStored == 1 ? "" : "s")
                } else {
                    resultText = "Synced — nothing new"
                }
            } catch {
                resultText = "Sync failed"
            }
            propagationSyncing = false
            propagationSyncResult = resultText
            syncResultClearTask = Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                guard !Task.isCancelled else { return }
                self?.propagationSyncResult = nil
            }
        }
    }

    // ---- Destination admin actions -------------------------------------

    /// Hard-delete a destination row + all its associated messages.
    /// Used by Messages-tab swipe-to-delete and Nodes-tab swipe-to-
    /// delete. If the destination announces again later it'll come
    /// back as a fresh row (without the deleted history).
    func deleteDestinationAndMessages(hash: String) {
        Task { try? await engine.deleteDestinationAndMessages(hashHex: hash) }
    }

    /// Clear all messages for a destination but keep the destination
    /// row itself (and any favorite / userLabel state on it). Used by
    /// the conversation-view "Clear" button.
    func deleteMessagesForDestination(hash: String) {
        Task { try? await engine.deleteMessagesForDestination(hashHex: hash) }
    }

    /// Clear cached Nomad pages for a single destination. Used by the
    /// Nomad page-view "Clear cache" button. Engine consumes
    /// `repos.nomadPageCache` directly so this delegates straight to
    /// the repo.
    func clearNomadCache(destHash: String) {
        Task { try? await repos.nomadPageCache.clearAllForDest(destHash: destHash) }
    }

    func setUserLabel(hash: String, label: String?) {
        Task { try? await engine.setUserLabel(hashHex: hash, label: label) }
    }

    func addManualDestination(hashHex: String, label: String) {
        Task {
            do {
                _ = try await engine.addManualDestination(hashHex: hashHex, label: label)
            } catch {
                lastConnectError = "\(error)"
            }
        }
    }

    /// Register a QR-scanned IdentityCard. The card carries the public
    /// key + ratchet, so the destination becomes immediately
    /// messagable (unlike addManualDestination which waits for an
    /// announce to fill in the keys).
    func applyIdentityCard(_ card: IdentityCard.Payload) {
        Task {
            do {
                _ = try await engine.applyIdentityCard(card: card)
            } catch {
                // Write to both surfaces: lastQrImportError drives the
                // new NodesView alert (primary, blocks the user so they
                // know the contact was NOT imported); lastConnectError
                // keeps the existing Settings → TCP log line populated.
                let msg = "\(error)"
                lastQrImportError = msg
                lastConnectError = msg
            }
        }
    }

    // ---- Unread badge --------------------------------------------------

    /// UserDefaults key prefix for the per-contact "last opened"
    /// timestamps that gate the home-screen badge count. Format:
    ///   "lastSeen.<contactHashHex>" → epochMs as Int64
    /// Stored on UserDefaults rather than the SQLDelight schema for
    /// simplicity; survives backgrounding + relaunch + iCloud restore
    /// (a fresh install starts every contact at lastSeen=0 → all
    /// historical incoming counts as unread, which is the correct
    /// first-launch UX).
    private static let lastSeenPrefix = "lastSeen."

    /// Record that the user just opened a conversation with [contactHash].
    /// All incoming messages received before this moment no longer count
    /// toward the home-screen badge. Called by ConversationView.onAppear
    /// (replacing the previous global clearBadge call).
    func markConversationOpened(contactHash: String) {
        let key = Self.lastSeenPrefix + contactHash
        // Epoch ms — matches the column type the Kotlin engine writes
        // to StoredMessage.timestamp.
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        UserDefaults.standard.set(now, forKey: key)
        Task { @MainActor in
            await recomputeUnreadBadge()
        }
    }

    /// Walk the message repo, count incoming rows whose timestamp is
    /// greater than their contact's `lastSeen` mark, push the total
    /// to iOS via `IosNotifications.setBadge`.
    ///
    /// Called from two sites: (a) the engine event observer when a
    /// MessageReceived fires (badge goes up); (b)
    /// `markConversationOpened` when the user enters a conversation
    /// (badge goes down by that contact's slice). Single read of
    /// `getAll()` per call keeps the work bounded; for a chat history
    /// of thousands of rows this is still <50 ms on phone-class CPUs.
    func recomputeUnreadBadge() async {
        let allMessages: [StoredMessage]
        do {
            allMessages = try await repos.messages.getAll() as! [StoredMessage]
        } catch {
            // getAll() doesn't throw in practice, but the bridge
            // declares it async — swallow + skip rather than crash.
            return
        }
        var unread = 0
        // Cache per-contact lastSeen for the duration of the walk so
        // we don't hit UserDefaults once per row (each lookup is a
        // dictionary access on UserDefaults' in-memory backing store
        // — cheap but not free).
        var lastSeenCache: [String: Int64] = [:]
        for msg in allMessages {
            guard msg.direction == "incoming" else { continue }
            let contact = msg.contactHash
            let lastSeen: Int64
            if let cached = lastSeenCache[contact] {
                lastSeen = cached
            } else {
                let fetched = Int64(UserDefaults.standard.integer(forKey: Self.lastSeenPrefix + contact))
                lastSeenCache[contact] = fetched
                lastSeen = fetched
            }
            if msg.timestamp > lastSeen {
                unread += 1
            }
        }
        IosNotifications.shared.setBadge(unread)
    }

    // ---- Messaging -----------------------------------------------------

    /// Queue an opportunistic LXMF send to [destinationHash]. The engine
    /// handles retry + delivery-proof internally; observers of
    /// `observeMessagesForContact(hash)` see the row flip from
    /// "pending" → "sent" → "delivered" or "failed" as the proof
    /// arrives or the retry budget is exhausted.
    func sendMessage(destinationHash: String, content: String) {
        sendMessage(destinationHash: destinationHash, content: content,
                    imageBytes: nil, fileBytes: nil, fileName: nil, replyToMessageId: nil)
    }

    /// Send a text LXMF message with an optional attachment. An
    /// [imageBytes] payload travels as LXMF `FIELD_IMAGE` (key 6), a
    /// [fileBytes] payload as `FIELD_FILE_ATTACHMENTS` (key 5, named by
    /// [fileName]) — both inside a Resource-framed container; the
    /// engine falls back to a text-only opportunistic send if Resource
    /// delivery fails. Image and file are mutually exclusive (the
    /// engine rejects both). The Swift `Data` is copied byte-by-byte
    /// into a `KotlinByteArray` because Kotlin/Native can't accept raw
    /// `Data` across the bridge.
    func sendMessage(
        destinationHash: String,
        content: String,
        imageBytes: Data?,
        fileBytes: Data? = nil,
        fileName: String? = nil,
        replyToMessageId: String? = nil,
    ) {
        let kotlinImage = imageBytes.map(Self.kotlinBytes)
        let kotlinFile = fileBytes.map(Self.kotlinBytes)
        Task {
            lastSendError = nil
            do {
                _ = try await engine.sendMessage(
                    destinationHash: destinationHash,
                    content: content,
                    title: "",
                    imageBytes: kotlinImage,
                    fileBytes: kotlinFile,
                    fileName: fileName,
                    replyToMessageId: replyToMessageId,
                )
            } catch {
                lastSendError = "\(error)"
            }
        }
    }

    /// Copy a Swift `Data` into a Kotlin `ByteArray` for the K/N
    /// bridge — Kotlin/Native exposes no raw-`Data` entry point.
    private static func kotlinBytes(_ data: Data) -> KotlinByteArray {
        let arr = KotlinByteArray(size: Int32(data.count))
        for i in 0..<data.count {
            arr.set(index: Int32(i), value: Int8(bitPattern: data[i]))
        }
        return arr
    }

    /// Send a tap-back emoji reaction. Mirrors the Android
    /// `ReticulumService.sendReaction` shim — applies locally so
    /// the user sees their own reaction immediately, then ships a
    /// separate empty-body LXMF with field 16 carrying the
    /// (reaction_to, emoji, sender) triple per the Sideband /
    /// Columba convention. Audit reference: 2026-05-13 reactions
    /// + replies feature.
    func sendReaction(
        destinationHash: String,
        targetMessageId: String,
        emoji: String,
    ) async {
        do {
            _ = try await engine.sendReaction(
                destinationHash: destinationHash,
                targetMessageId: targetMessageId,
                emoji: emoji,
            )
        } catch {
            // Reaction-send failures are not user-blocking — the
            // local apply already happened so the user sees their
            // own reaction. Just surface the error in the
            // diagnostic log via the engine's logExternal path.
            engine.logExternal(line: "reaction send failed: \(error)")
        }
    }

    // ---- Identity backup ----------------------------------------------

    /// Export the device's identity into a passphrase-encrypted `.rmid`
    /// archive. Same wire format the Android export produces, so a
    /// backup made on iOS imports cleanly on Android and vice-versa.
    /// The Kotlin engine returns `KotlinByteArray`; we copy byte-by-byte
    /// into a Swift `Data` for the SwiftUI fileExporter document handoff.
    func exportIdentityArchive(passphrase: String) async throws -> Data {
        // The archive KDF (PBKDF2) is CPU-bound and `engine.exportIdentity`
        // doesn't dispatch internally. ReticulumStore is @MainActor, so a
        // plain `await` here would run the KDF on the main actor and
        // freeze the UI — the bug docs/REDESIGN.md §10 calls out. Hop to
        // a detached task (the Android counterpart dispatches the same
        // way) and keep a blocking spinner up in the meantime.
        let engine = self.engine
        let bytes = try await Task.detached(priority: .userInitiated) {
            try await engine.exportIdentity(passphrase: passphrase)
        }.value
        var out = Data(count: Int(bytes.size))
        for i in 0..<Int(bytes.size) {
            out[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
        }
        return out
    }

    /// Replace the device's identity with one decrypted from [archive]
    /// using [passphrase]. Tears down active link sessions inside the
    /// engine; the published `ourDestHash` refreshes explicitly after
    /// the call so the Settings → About row updates immediately. Wrong
    /// passphrase or tampered bytes surface as a thrown error from the
    /// underlying Kotlin code.
    ///
    /// Bug fix 2026-05-12: the refreshOurDestHash() call below was
    /// missing, which made the @Published ourDestHash stay stuck on
    /// the pre-import (auto-generated) identity. Engine internals were
    /// correctly using the imported identity, but the UI displayed the
    /// stale value — surfaced as the tester report "exported it,
    /// upgraded and re-imported it, but it's a different hash."
    /// connectTcp / resetIdentity already refresh; importIdentity was
    /// the odd one out. See engine-level regression test
    /// IdentityArchiveTest.roundtrip_preserves_destination_hash for
    /// the underlying engine-correctness pin.
    func importIdentityArchive(archive: Data, passphrase: String) async throws {
        let bytes = KotlinByteArray(size: Int32(archive.count))
        for i in 0..<archive.count {
            bytes.set(index: Int32(i), value: Int8(bitPattern: archive[i]))
        }
        // Off-main, same reasoning as exportIdentityArchive — the
        // unpack KDF must not run on the main actor.
        let engine = self.engine
        let payload = try await Task.detached(priority: .userInitiated) {
            try await engine.importIdentity(archive: bytes, passphrase: passphrase)
        }.value
        // v0x02 archives carry the user's display name; restore it to
        // UserDefaults so the @AppStorage("displayName") field in
        // SettingsView picks it up and the next announce uses the
        // imported label. v0x01 (legacy) archives surface nil here
        // — leave the existing local name in place.
        if let recoveredName = payload.displayName,
           recoveredName != UserDefaults.standard.string(forKey: "displayName") {
            UserDefaults.standard.set(recoveredName, forKey: "displayName")
        }
        await refreshOurDestHash()
    }

    // ---- RRC actions ----------------------------------------------------

    /// Fold one RrcActivity projection into [rrcHubStates]. Called from
    /// the engine-event subscription, already on the main actor.
    private func applyRrcActivity(_ info: RrcActivityInfo) {
        var st = rrcHubStates[info.hubDestHash] ?? RrcHubState()
        switch info.kind {
        case "state":
            st.stateName = info.stateName
            if info.stateName != "CONNECTING" { st.connecting = false }
        case "welcomed":
            st.stateName = "WELCOMED"
            st.connecting = false
            st.hubName = info.hubName
        case "notice":
            if let t = info.text { st.lastNotice = t }
        case "error":
            st.connecting = false
            st.lastNotice = "Error: \(info.text ?? "")"
        case "roomTopic":
            if let room = info.room {
                if let topic = info.topic {
                    st.roomTopics[room] = topic
                } else {
                    st.roomTopics.removeValue(forKey: room)
                }
            }
        case "roomModes":
            if let room = info.room, let modes = info.modes {
                st.roomModes[room] = modes
            }
        case "roomList":
            st.availableRooms = info.rooms ?? []
        default:
            // roomMessage / joined / parted are persisted by the
            // engine's RrcPersistence — observed via the repo flows.
            break
        }
        rrcHubStates[info.hubDestHash] = st
    }

    /// Open (or reuse) a live session to the RRC hub at [hubHash]. The
    /// persisted nick is read here and handed to the engine — editing
    /// it via [setRrcHubNick] takes effect on the next open.
    func openRrcSession(hubHash: String) {
        // Remember this hub has a live session so a cold start re-opens
        // it once a transport is back up.
        addLiveRrcHub(hubHash)
        var st = rrcHubStates[hubHash] ?? RrcHubState()
        st.connecting = true
        st.lastNotice = nil
        rrcHubStates[hubHash] = st
        Task {
            do {
                let nick = try await repos.rrc.getHub(destHash: hubHash)?.nick
                let err = try await IosEngineFactoryKt.openRrcSessionBridge(
                    engine: engine, hubDestHash: hubHash, nick: nick,
                )
                if let err = err {
                    var s = rrcHubStates[hubHash] ?? RrcHubState()
                    s.connecting = false
                    s.lastNotice = err
                    rrcHubStates[hubHash] = s
                }
            } catch {
                var s = rrcHubStates[hubHash] ?? RrcHubState()
                s.connecting = false
                s.lastNotice = "\(error)"
                rrcHubStates[hubHash] = s
            }
        }
    }

    func closeRrcSession(hubHash: String) {
        // Explicit close — forget the hub so a relaunch doesn't re-open it.
        removeLiveRrcHub(hubHash)
        Task { try? await engine.closeRrcSession(hubDestHash: hubHash) }
    }

    private func addLiveRrcHub(_ hubHash: String) {
        var hubs = Set(UserDefaults.standard.stringArray(forKey: kConnLiveRrcHubs) ?? [])
        hubs.insert(hubHash)
        UserDefaults.standard.set(Array(hubs), forKey: kConnLiveRrcHubs)
    }

    private func removeLiveRrcHub(_ hubHash: String) {
        var hubs = Set(UserDefaults.standard.stringArray(forKey: kConnLiveRrcHubs) ?? [])
        hubs.remove(hubHash)
        UserDefaults.standard.set(Array(hubs), forKey: kConnLiveRrcHubs)
    }

    /// Cold-start RRC restore: once a transport is Connected, re-open
    /// every hub that had a live session before the app was shut down.
    /// The engine's room auto-rejoin then restores each hub's joined
    /// rooms. Fires once per app session; gated on the experimental
    /// RRC feature. Mirrors Android's `scheduleRrcRestore`.
    private func scheduleRrcRestore() {
        guard UserDefaults.standard.bool(forKey: "experimental.rrc") else { return }
        let hubs = UserDefaults.standard.stringArray(forKey: kConnLiveRrcHubs) ?? []
        guard !hubs.isEmpty else { return }
        rrcRestoreCancellable = $connections
            .filter { conns in conns.contains { $0.transport == .connected } }
            .first()
            .sink { [weak self] _ in
                guard let self else { return }
                self.rrcRestoreCancellable = nil
                for hub in hubs { self.openRrcSession(hubHash: hub) }
            }
    }

    func joinRrcRoom(hubHash: String, room: String) {
        let name = room.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        Task { try? await engine.joinRrcRoom(hubDestHash: hubHash, room: name, key: nil) }
    }

    func partRrcRoom(hubHash: String, room: String) {
        Task { try? await engine.partRrcRoom(hubDestHash: hubHash, room: room) }
    }

    /// Remove a room from local storage (its row + cached messages).
    /// Parts it on the hub first when a session is live. Housekeeping
    /// — works whether or not the hub is connected.
    func deleteRrcRoom(hubHash: String, room: String) {
        Task { try? await engine.deleteRrcRoom(hubDestHash: hubHash, room: room) }
    }

    /// Send `/list`; the reply lands in
    /// `rrcHubStates[hubHash].availableRooms` via the RrcActivity stream.
    func browseRrcRooms(hubHash: String) {
        var st = rrcHubStates[hubHash] ?? RrcHubState()
        st.availableRooms = nil  // clear stale — the sheet shows a spinner
        rrcHubStates[hubHash] = st
        Task { try? await engine.browseRrcRooms(hubDestHash: hubHash) }
    }

    func sendRrcMessage(hubHash: String, room: String, text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task { try? await engine.sendRrcMessage(hubDestHash: hubHash, room: room, text: trimmed) }
    }

    func setRrcHubNick(hubHash: String, nick: String?) {
        let cleaned = nick?.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            try? await engine.setRrcHubNick(
                hubDestHash: hubHash,
                nick: (cleaned?.isEmpty ?? true) ? nil : cleaned,
            )
        }
    }

    /// Add (or update) a hub row. The Rooms list observes
    /// `repos.observeRrcHubs()` so the new row appears immediately.
    func addRrcHub(destHash: String, displayName: String, nick: String?) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let cleanedNick = nick?.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            try? await repos.rrc.upsertHub(hub: StoredRrcHub(
                destHash: destHash,
                displayName: displayName,
                nick: (cleanedNick?.isEmpty ?? true) ? nil : cleanedNick,
                lastConnectedAt: 0,
                addedAt: now,
            ))
        }
    }

    func deleteRrcHub(hubHash: String) {
        Task {
            try? await engine.closeRrcSession(hubDestHash: hubHash)
            try? await repos.rrc.deleteHub(destHash: hubHash)
        }
        rrcHubStates.removeValue(forKey: hubHash)
    }
}

/// Volatile per-hub RRC session state, folded from the engine's
/// `EngineEvent.RrcActivity` stream. Not persisted — recomputed each
/// session. Mirrors the Android `ReticulumViewModel.RrcHubState`.
struct RrcHubState {
    var connecting: Bool = false
    /// "CONNECTING" / "WELCOMED" / "CLOSED", or nil before any session.
    var stateName: String? = nil
    var hubName: String? = nil
    /// Most recent hub NOTICE / ERROR text, for a transient banner.
    var lastNotice: String? = nil
    /// Per-room topic, parsed from the hub's structured NOTICEs.
    var roomTopics: [String: String] = [:]
    /// Per-room mode string (e.g. "+int").
    var roomModes: [String: String] = [:]
    /// Most recent `/list` result; nil while a browse request is in
    /// flight (drives the browse sheet's spinner).
    var availableRooms: [RrcRoomListing]? = nil

    var welcomed: Bool { stateName == "WELCOMED" }
}
