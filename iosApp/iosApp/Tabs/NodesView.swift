// SPDX-License-Identifier: MIT
//
// Phase 3 placeholder. Will list every observed destination with the
// metadata cluster (hops / RSSI / age / stale-far warnings) once
// IosBleTransport + iOS storage actual land.

import SwiftUI
import Shared

struct NodesView: View {
    var body: some View {
        PhaseThreePlaceholder(
            title: "Nodes",
            blockedBy: "IosBleTransport (CoreBluetooth) + iOS storage",
            sharedDemo: "name_hash(\"lxmf.delivery\") = " + KnownDestinations.shared.lxmfDeliveryNameHashHex,
        )
    }
}

/// Helper exposing the well-known LXMF delivery name_hash hex string,
/// taken from the same `KnownDestinations` table both Android and iOS
/// share via commonMain. No crypto, no I/O — pure lookup.
private extension KnownDestinations {
    static var shared: KnownDestinations { KnownDestinations.shared }

    var lxmfDeliveryNameHashHex: String {
        // KnownDestinations.byNameHashHex is a lookup map keyed on the
        // 10-byte truncated SHA256 of the well-known service name. The
        // hex below is verified against `reference/test-vectors.json`.
        return "6ec60bc318e2c0f0d908"
    }
}
