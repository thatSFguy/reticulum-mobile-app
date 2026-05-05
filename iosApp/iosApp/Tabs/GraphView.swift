// SPDX-License-Identifier: MIT
//
// Phase 3 placeholder. Will host a force-directed `me → relay → leaf`
// topology view once an iOS-side renderer is written. The shared
// `GraphTopology` builder is Android-specific (lives in
// `androidApp/.../ui/graph/`); a sibling iOS-side builder will be
// extracted into commonMain when the iOS UI is real.

import SwiftUI
import Shared

struct GraphView: View {
    var body: some View {
        PhaseThreePlaceholder(
            title: "Graph",
            blockedBy: "iOS storage + an iOS Canvas-based renderer",
            sharedDemo: "MTU = " + String(ConstantsKt.MTU),
        )
    }
}
