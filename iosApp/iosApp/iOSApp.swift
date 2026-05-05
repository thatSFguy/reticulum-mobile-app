// SPDX-License-Identifier: MIT
//
// iOS app entry point. Hosts the SwiftUI ContentView (TabView shell)
// inside a single WindowGroup. The Phase 3 milestone is the app
// launching, the bottom tab bar rendering, and each tab successfully
// invoking a pure-Kotlin function from `Shared.xcframework` to prove
// the cross-language link works end-to-end. Phase 4 fills in real
// screens once the iosMain platform actuals (CryptoKit, NWConnection,
// SQLDelight, CoreBluetooth) land.

import SwiftUI

@main
struct ReticulumApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
