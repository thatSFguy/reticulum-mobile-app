// SPDX-License-Identifier: MIT
//
// Phase 3 placeholder. Will become the Messages list + conversation
// view once IosCryptoProvider + iOS storage actual land in Phase 2.

import SwiftUI
import Shared

struct MessagesView: View {
    var body: some View {
        PhaseThreePlaceholder(
            title: "Messages",
            blockedBy: "iOS storage (SQLDelight) + IosCryptoProvider",
            sharedDemo: "kiss frame for empty payload: 0x" + helloKissFrameHex(),
        )
    }

    /// Tiny round-trip into Shared to prove the framework bridge works.
    /// `buildKissFrame` is pure Kotlin (no platform actuals required), so
    /// it runs identically on iOS and Android.
    private func helloKissFrameHex() -> String {
        let frame = KissKt.buildKissFrame(cmd: 0x00, data: KotlinByteArray(size: 0))
        var bytes = [UInt8]()
        for i in 0..<frame.size {
            bytes.append(UInt8(bitPattern: frame.get(index: i)))
        }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}
