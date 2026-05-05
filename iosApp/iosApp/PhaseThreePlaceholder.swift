// SPDX-License-Identifier: MIT
//
// Common placeholder body each Phase 3 tab renders until its real
// content is written. Three lines:
//   1. Tab title.
//   2. What Phase 2 / Phase 4 work this tab is blocked on, so a Mac
//      dev opening the app knows exactly what to fill in next.
//   3. A small string returned by a pure-Kotlin function in
//      `Shared.xcframework` — proves cross-language interop works
//      without depending on any iosMain platform actual.

import SwiftUI

struct PhaseThreePlaceholder: View {
    let title: String
    let blockedBy: String
    let sharedDemo: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.largeTitle)
                .bold()

            Text("Phase 3 placeholder — UI not yet ported to iOS.")
                .font(.headline)
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 4) {
                Text("Blocked on:")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(blockedBy)
                    .font(.callout)
            }

            Divider()

            VStack(alignment: .leading, spacing: 4) {
                Text("Shared framework round-trip:")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(sharedDemo)
                    .font(.system(.callout, design: .monospaced))
            }

            Spacer()
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
