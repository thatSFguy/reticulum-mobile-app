// SPDX-License-Identifier: MIT
//
// Phase 3 placeholder. Will expose the multi-transport selector
// (BLE / TCP — NOT BT Classic, which needs MFi certification on iOS),
// radio config, identity card + QR, diagnostics log. All gated on
// the iosMain platform actuals from Phase 2.

import SwiftUI
import Shared

struct SettingsView: View {
    var body: some View {
        PhaseThreePlaceholder(
            title: "Settings",
            blockedBy: "IosCryptoProvider (identity), NWConnection (TCP), CoreBluetooth (BLE)",
            sharedDemo: "header byte for HEADER_1|DEST_SINGLE|PACKET_DATA = 0x" +
                String(format: "%02x", ConstantsKt.HEADER_1 | (ConstantsKt.DEST_SINGLE << 2) | ConstantsKt.PACKET_DATA),
        )
    }
}
