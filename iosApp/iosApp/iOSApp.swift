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

    init() {
        // Register the UNUserNotificationCenter delegate at app
        // launch (BEFORE the SwiftUI scene attaches) so a cold
        // launch from a notification tap fires didReceive into our
        // handler. If we deferred this to first-view lifecycle,
        // cold-launch deep-links would silently lose the contactHash.
        // The store's wireNotificationDeepLinks() drains any
        // already-queued tap when it runs next.
        IosNotifications.shared.install()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
        }
    }
}
