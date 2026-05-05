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

    /// One TCP transport at a time for now. BLE is added in a follow-up
    /// PR alongside the CoreBluetooth scanner UI.
    private var tcpTransport: TcpInterface?

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

    // ---- KMP → SwiftUI subscriptions ------------------------------------

    private var subscriptions: [FlowSubscription] = []

    init() {
        self.factory = IosEngineFactory()
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
                    socketFactory: { h, p in TcpSocket(host: h, port: p) },
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

    // ---- Identity helpers ----------------------------------------------

    private func refreshOurDestHash() async {
        let hashBytes = try? await engine.ourDestHash()
        guard let bytes = hashBytes else { return }
        // `toHex` is a top-level extension on ByteArray in
        // shared/.../transport/Kiss.kt; exported as a static on KissKt.
        ourDestHash = KissKt.toHex(bytes)
    }
}
