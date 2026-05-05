// SPDX-License-Identifier: MIT
//
// iOS app entry point. Owns the lone `ReticulumStore` for the app's
// lifetime and injects it into the SwiftUI environment so every tab
// can `@EnvironmentObject` it.

import SwiftUI

@main
struct ReticulumApp: App {
    /// Single store, lifetime-scoped to the app process. Holds the
    /// engine, repos, transports, and the @Published state SwiftUI
    /// observes. Cancels its own coroutine scope in `deinit`.
    @StateObject private var store = ReticulumStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
        }
    }
}
