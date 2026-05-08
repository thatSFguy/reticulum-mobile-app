// SPDX-License-Identifier: MIT
//
// Root tab-bar shell. Mirrors the Android NavigationBar (Messages /
// Nodes / Nomad / Graph / Settings) so the cross-platform UX stays
// consistent — every iOS feature that's reachable on Android lives at
// the same coordinate.
//
// Phase 3 deliverable: this view renders, the tabs switch, and each
// tab's placeholder content successfully calls into Shared. Phase 4
// replaces the placeholders with real screens.

import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var store: ReticulumStore
    @State private var selectedTab: Tab = .messages

    enum Tab: Hashable { case messages, nodes, nomad, graph, settings }

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

            GraphView()
                .tabItem { Label("Graph", systemImage: "point.3.connected.trianglepath.dotted") }
                .tag(Tab.graph)

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
