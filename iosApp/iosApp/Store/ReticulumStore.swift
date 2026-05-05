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

    // ---- KMP → SwiftUI subscriptions ------------------------------------

    private var subscriptions: [FlowSubscription] = []

    init() {
        // IosEngineFactoryKt.createIosEngineFactory() is a top-level
        // helper because IosEngineFactory's Kotlin constructor uses
        // default args, which Kotlin/Native doesn't synthesise into a
        // Swift-visible `init()`. (Swift sees the no-arg form as
        // 'unavailable'.)
        self.factory = IosEngineFactoryKt.createIosEngineFactory()
        wireEngineSubscriptions()
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
                bleTransport = transport
                engine.attach(transport: transport, kind: .ble)
                try await engine.ensureIdentity()
                await refreshOurDestHash()
            } catch {
                lastConnectError = "\(error)"
            }
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
}
