// SPDX-License-Identifier: MIT
//
// Root tab-bar shell. Mirrors the Android NavigationBar (Messages /
// Nodes / Nomad / Settings) so the cross-platform UX stays consistent —
// every iOS feature that's reachable on Android lives at the same
// coordinate. Graph is not a tab: it's a Nodes/Graph pane switch inside
// the Nodes tab (see NodesView), matching Android.
//
// Phase 3 deliverable: this view renders, the tabs switch, and each
// tab's placeholder content successfully calls into Shared. Phase 4
// replaces the placeholders with real screens.

import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var store: ReticulumStore
    @State private var selectedTab: Tab = .messages
    /// User-controlled appearance preference. Persists in UserDefaults
    /// across launches. Settings tab writes this; ContentView reads it
    /// to drive `.preferredColorScheme(...)`. "system" leaves the
    /// scheme nil so iOS follows Display & Brightness automatically.
    @AppStorage("themePreference") private var themePreference: String = "system"
    /// Experimental Reticulum Relay Chat. When on, a Rooms tab appears
    /// between Nomad and Settings — mirrors the Android nav gate.
    @AppStorage("experimental.rrc") private var experimentalRrc: Bool = false

    enum Tab: Hashable { case messages, nodes, nomad, rooms, settings }

    var body: some View {
        TabView(selection: $selectedTab) {
            MessagesView()
                .tabItem { Label("Messages", systemImage: "envelope") }
                .tag(Tab.messages)

            NodesView()
                .tabItem { Label("Nodes", systemImage: "mappin.and.ellipse") }
                .tag(Tab.nodes)

            NomadView()
                .tabItem { Label("Nomad", systemImage: "info.circle") }
                .tag(Tab.nomad)

            if experimentalRrc {
                RoomsView()
                    .tabItem { Label("Rooms", systemImage: "bubble.left.and.bubble.right") }
                    .tag(Tab.rooms)
            }

            SettingsView()
                .tabItem {
                    // Red gear icon when no transport is up — same
                    // signal as the Android bottom-nav indicator.
                    // Forces .alwaysOriginal so SwiftUI doesn't tint
                    // it with the system accent color.
                    Label {
                        Text("Settings")
                    } icon: {
                        Image(systemName: "gearshape")
                            .renderingMode(noTransportConnected ? .original : .template)
                            .foregroundStyle(noTransportConnected ? Color.red : Color.accentColor)
                    }
                }
                .tag(Tab.settings)
        }
        // Tap-to-message from any tab → switch to Messages. MessagesView
        // observes the same event and pushes the conversation onto its
        // NavigationStack.
        .onChange(of: store.openContactEvent) { _, new in
            if new != nil { selectedTab = .messages }
        }
        .preferredColorScheme(resolvedColorScheme)
    }

    /// Maps the persisted "system" / "light" / "dark" string to the
    /// SwiftUI optional ColorScheme. Returning nil for "system" hands
    /// control back to iOS (Display & Brightness setting).
    private var resolvedColorScheme: ColorScheme? {
        switch themePreference {
        case "light": return .light
        case "dark":  return .dark
        default:      return nil
        }
    }

    /// True when no transport is in the Connected state. Used to flag
    /// the Settings tab in red so users notice they need to reconnect
    /// (parity with the Android bottom-nav indicator). Includes
    /// "Connecting" as not-yet-connected.
    private var noTransportConnected: Bool {
        !store.connections.contains { $0.transport == .connected }
    }
}

#Preview {
    ContentView()
}

/// Short, non-wrapping fingerprint of a destination / identity hash
/// for list rows — first 8 + last 8 hex characters joined by an
/// ellipsis (e.g. `7579c857…d75a3315`). Mirrors the shared Kotlin
/// `shortHash`; the full hash stays available in the destination
/// detail sheet. See docs/REDESIGN.md §4.
func shortHash(_ hash: String) -> String {
    hash.count <= 17 ? hash : "\(hash.prefix(8))…\(hash.suffix(8))"
}
