// SPDX-License-Identifier: MIT
//
// Phase 3 placeholder. Will mount the NomadNet browser (micron parser,
// in-page nav, page cache, form fields) once an iOS-side renderer is
// written. The shared `Micron` parser already runs on iOS — only the
// link transport (NWConnection) and storage (SQLDelight cache) are
// blocked on Phase 2.

import SwiftUI
import Shared

struct NomadView: View {
    var body: some View {
        PhaseThreePlaceholder(
            title: "Nomad",
            blockedBy: "NWConnection-backed TcpSocket + iOS storage cache",
            sharedDemo: "micron renders \"`!hello`!\" → \"hello\" (bold)",
        )
    }
}
