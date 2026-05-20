// SPDX-License-Identifier: MIT
//
// Root tab-bar shell. Mirrors the Android NavigationBar after the UI
// redesign (docs/REDESIGN.md §5): the default bar is Nodes · Messages ·
// Settings, with Nomad and Relay Chat as opt-in tabs that only appear
// once enabled from Settings → Features. Nodes is the leftmost tab.
// Graph and Map are not tabs — they're pane switches inside Nodes.

import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var store: ReticulumStore
    @State private var selectedTab: Tab = .messages
    /// User-controlled appearance preference. Persists in UserDefaults
    /// across launches. The Settings → Appearance sub-screen writes
    /// this; ContentView reads it to drive `.preferredColorScheme(...)`.
    /// "system" leaves the scheme nil so iOS follows Display &
    /// Brightness — and the status bar tints itself to match.
    @AppStorage("themePreference") private var themePreference: String = "system"
    /// Experimental Reticulum Relay Chat. When on, a Rooms tab appears
    /// before Settings — mirrors the Android nav gate.
    @AppStorage("experimental.rrc") private var experimentalRrc: Bool = false
    /// Opt-in NomadNet browser. Default off; enabled from Settings →
    /// Features. Mirrors the Android `nomadEnabled` preference gate.
    @AppStorage("feature.nomad") private var nomadEnabled: Bool = false
    /// One-shot: a brand-new install lands on Settings → Connection
    /// (an empty Messages list is useless before a transport is up).
    @AppStorage("ui.firstLaunchRouted") private var firstLaunchRouted: Bool = false

    /// When non-nil, Settings opens drilled into this sub-screen. Set
    /// once on first launch to point at Connection, then cleared.
    @State private var pendingSettingsRoute: SettingsRoute?

    enum Tab: Hashable { case nodes, messages, nomad, rooms, settings }

    var body: some View {
        TabView(selection: $selectedTab) {
            NodesView()
                .tabItem { Label("Nodes", systemImage: "mappin.and.ellipse") }
                .tag(Tab.nodes)

            MessagesView()
                .tabItem { Label("Messages", systemImage: "envelope") }
                .tag(Tab.messages)

            if nomadEnabled {
                NomadView()
                    .tabItem { Label("Nomad", systemImage: "globe") }
                    .tag(Tab.nomad)
            }

            if experimentalRrc {
                RoomsView()
                    .tabItem { Label("Rooms", systemImage: "bubble.left.and.bubble.right") }
                    .tag(Tab.rooms)
            }

            SettingsView(pendingRoute: $pendingSettingsRoute)
                .tabItem {
                    // Red gear icon when no transport is up — same
                    // signal as the Android bottom-nav indicator.
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
        // Open-RRC-hub deep-link (e.g. detail sheet's "Open in Relay
        // Chat" button) → switch to Rooms. RoomsView observes the same
        // event and pushes the hub's chat onto its NavigationStack.
        // Gated on `experimentalRrc` because that's what conditionally
        // renders the Rooms tab — selecting a hidden tag would
        // silently no-op.
        .onChange(of: store.openRrcHubEvent) { _, new in
            if new != nil, experimentalRrc { selectedTab = .rooms }
        }
        .preferredColorScheme(resolvedColorScheme)
        .onAppear {
            // First launch: an empty Messages list before a transport
            // is attached is useless — drop the user straight into
            // Settings → Connection. One-shot, guarded by the flag.
            if !firstLaunchRouted {
                firstLaunchRouted = true
                pendingSettingsRoute = .connection
                selectedTab = .settings
            }
        }
        // Cold-start TCP / BLE restore — deferred to the first-frame
        // `.task` so the iOS launch transaction completes before any
        // restore work runs. Without this, a stalled `getaddrinfo` on
        // a saved hostname during startup-without-network can trip
        // the launch watchdog and SpringBoard kills the app with
        // "attention client lost". `performStartupRestore` awaits the
        // path monitor's first emission, then gates the TCP branch
        // on reachability.
        .task { await store.performStartupRestore() }
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
    /// (parity with the Android bottom-nav indicator).
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
