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

import Foundation
import Shared
import SwiftUI

@MainActor
final class ReticulumStore: ObservableObject {

    // ---- Engine + repos -------------------------------------------------

    private let factory: IosEngineFactory
    var engine: ReticulumEngine { factory.engine }
    var repos: IosRepositories { factory.repos }
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

    /// Starred messagable destinations. Pinned at the top of the
    /// Messages tab.
    @Published var favorites: [StoredDestination] = []

    /// Senders we've received from but haven't starred yet — drives
    /// the Messages tab's Inbox section.
    @Published var inbox: [StoredDestination] = []

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

    // ---- KMP → SwiftUI subscriptions ------------------------------------

    private var subscriptions: [FlowSubscription] = []

    init() {
        // Pass a Swift-side display-name provider that reads
        // UserDefaults each time the engine builds an announce. The
        // SettingsView's Display name TextField writes to the same
        // key (@AppStorage("displayName")), so an edit lands in the
        // next announce without restarting the engine. Mirrors the
        // Android pattern of `Preferences.getDisplayName()`.
        self.factory = IosEngineFactoryKt.createIosEngineFactoryWithDisplayName(
            displayName: {
                let raw = UserDefaults.standard.string(forKey: "displayName")?
                    .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                return raw.isEmpty ? "Reticulum Mobile" : raw
            }
        )
        wireEngineSubscriptions()
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
            guard let typed = event as? ReticulumEngine.EngineEvent,
                  let line = IosEngineFactoryKt.engineEventToLogLine(event: typed) else {
                return
            }
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
                let transport = TcpInterface(
                    host: host,
                    port: port,
                    scope: factory.scope,
                    socketFactory: { h, p in TcpSocket(host: h, port: Int32(truncating: p)) },
                    txLogger: { _ in /* hook the diagnostics log here later */ }
                )
                try await transport.connect()
                tcpTransport = transport
                engine.attach(transport: transport, kind: .tcp)
                try await engine.ensureIdentity()
                await refreshOurDestHash()
            } catch {
                lastConnectError = "\(error)"
            }
        }
    }

    func disconnectTcp() {
        guard let t = tcpTransport else { return }
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
        Task {
            engine.detach(kind: .ble)
            try? await t.disconnect()
            bleTransport = nil
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

    /// Sync messages from the user's selected propagation node (or
    /// auto-pick the closest one). Surface result via the engine event
    /// log; on failure surface as a brief lastConnectError too so the
    /// Settings UI flashes the error.
    func syncPropagationAuto() {
        Task {
            do {
                // Kotlin default arg `maxAttempts: Int = 5` doesn't
                // surface as a no-arg Swift overload — pass the same
                // default explicitly (5 candidates is what Android
                // also uses).
                _ = try await engine.syncPropagationAuto(maxAttempts: 5)
            } catch {
                lastConnectError = "propagation sync: \(error)"
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
                lastConnectError = "\(error)"
            }
        }
    }

    // ---- Messaging -----------------------------------------------------

    /// Queue an opportunistic LXMF send to [destinationHash]. The engine
    /// handles retry + delivery-proof internally; observers of
    /// `observeMessagesForContact(hash)` see the row flip from
    /// "pending" → "sent" → "delivered" or "failed" as the proof
    /// arrives or the retry budget is exhausted.
    func sendMessage(destinationHash: String, content: String) {
        Task {
            lastSendError = nil
            do {
                _ = try await engine.sendMessage(
                    destinationHash: destinationHash,
                    content: content,
                    title: ""
                )
            } catch {
                lastSendError = "\(error)"
            }
        }
    }

    // ---- Identity backup ----------------------------------------------

    /// Export the device's identity into a passphrase-encrypted `.rmid`
    /// archive. Same wire format the Android export produces, so a
    /// backup made on iOS imports cleanly on Android and vice-versa.
    /// The Kotlin engine returns `KotlinByteArray`; we copy byte-by-byte
    /// into a Swift `Data` for the SwiftUI fileExporter document handoff.
    func exportIdentityArchive(passphrase: String) async throws -> Data {
        let bytes = try await engine.exportIdentity(passphrase: passphrase)
        var out = Data(count: Int(bytes.size))
        for i in 0..<Int(bytes.size) {
            out[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
        }
        return out
    }

    /// Replace the device's identity with one decrypted from [archive]
    /// using [passphrase]. Tears down active link sessions inside the
    /// engine; the published `ourDestHash` will refresh shortly via the
    /// engine's identity flow. Wrong passphrase or tampered bytes
    /// surface as a thrown error from the underlying Kotlin code.
    func importIdentityArchive(archive: Data, passphrase: String) async throws {
        let bytes = KotlinByteArray(size: Int32(archive.count))
        for i in 0..<archive.count {
            bytes.set(index: Int32(i), value: Int8(bitPattern: archive[i]))
        }
        try await engine.importIdentity(archive: bytes, passphrase: passphrase)
    }
}
