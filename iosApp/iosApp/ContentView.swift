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
    var body: some View {
        TabView {
            MessagesView()
                .tabItem { Label("Messages", systemImage: "envelope") }

            NodesView()
                .tabItem { Label("Nodes", systemImage: "mappin.and.ellipse") }

            NomadView()
                .tabItem { Label("Nomad", systemImage: "info.circle") }

            GraphView()
                .tabItem { Label("Graph", systemImage: "point.3.connected.trianglepath.dotted") }

            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
    }
}

#Preview {
    ContentView()
}
