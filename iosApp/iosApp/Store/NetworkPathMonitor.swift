// SPDX-License-Identifier: MIT
//
// Reachability monitor backed by `NWPathMonitor`. Surfaces a single
// @Published boolean — `isReachable` — that flips on the first
// "satisfied" path and back to false when no interface can carry
// traffic. ReticulumStore uses it for two things:
//
//   1. Cold-start restore — the TCP auto-reconnect on launch is gated
//      on a reachable path so we don't attempt `getaddrinfo` against
//      a saved hostname while the device is offline (that path has
//      been the suspect for cold-launch crashes on locked-then-
//      unlocked phones — see `IosEngineFactory.kt:50-65` for the
//      v1.0.13 precedent).
//
//   2. Disconnect detection — when the OS-level path goes
//      unsatisfied while we have a live TCP socket, that socket is
//      already dead (the kernel reclaimed it during suspend or the
//      Wi-Fi interface dropped). Surfacing the transition lets
//      ReticulumStore tear the dead transport down and re-attempt
//      reconnect when the path comes back. Without this, the UI keeps
//      showing "Connected" with no traffic actually moving.
//
// `NWPathMonitor` is the framework-level reachability primitive on
// iOS; it doesn't require entitlements and doesn't trigger any user
// prompts. Updates fire on a private dispatch queue; we hop to
// @MainActor before mutating @Published so SwiftUI observers see
// the change on the main run loop.

import Combine
import Foundation
import Network

@MainActor
final class NetworkPathMonitor: ObservableObject {

    /// True iff at least one network interface (Wi-Fi, cellular, wired,
    /// loopback) can currently carry traffic. Starts at `false` and
    /// flips after the monitor's first update — which arrives within
    /// milliseconds of `start(queue:)`. Use `waitForFirstUpdate()` if
    /// you need to read this synchronously at app launch.
    @Published private(set) var isReachable: Bool = false

    /// Flips to true the first time `pathUpdateHandler` fires. Lets
    /// startup code distinguish "monitor hasn't reported yet" from
    /// "monitor reported false" — they're indistinguishable on the
    /// `isReachable` field alone.
    @Published private(set) var hasReceivedFirstUpdate: Bool = false

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(
        label: "io.github.thatsfguy.reticulum.network-path",
        qos: .utility,
    )

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let reachable = path.status == .satisfied
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.isReachable = reachable
                if !self.hasReceivedFirstUpdate {
                    self.hasReceivedFirstUpdate = true
                }
            }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }

    /// Suspend the caller until the monitor has emitted at least one
    /// path update. Returns immediately once the first update has
    /// already fired. Cold-start restore uses this so the initial
    /// reachability read doesn't see the default `false` before
    /// NWPathMonitor's first callback lands.
    func waitForFirstUpdate() async {
        if hasReceivedFirstUpdate { return }
        for await received in $hasReceivedFirstUpdate.values {
            if received { return }
        }
    }
}
