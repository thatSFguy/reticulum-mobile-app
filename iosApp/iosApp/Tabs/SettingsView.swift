// SPDX-License-Identifier: MIT
//
// Phase 3 — first real screen. Connect/disconnect a TCP transport
// against an rnsd `TCPServerInterface` (e.g. `RNS.MichMesh.net:7822`)
// and surface the resulting connection status. Mirrors the equivalent
// section of the Android `SettingsScreen.kt`. BLE picker + radio
// config + diagnostics log come in follow-up PRs.

import Shared
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: ReticulumStore

    @State private var tcpHost: String = "RNS.MichMesh.net"
    @State private var tcpPort: String = "7822"
    @State private var hashCopiedAt: Date? = nil

    var body: some View {
        NavigationStack {
            Form {
                statusSection
                tcpSection
                identitySection
                aboutSection
            }
            .navigationTitle("Settings")
        }
    }

    // ---- Status --------------------------------------------------------

    private var statusSection: some View {
        Section("Status") {
            if store.connections.isEmpty {
                Label("Disconnected", systemImage: "wifi.slash")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(store.connections, id: \.kind) { conn in
                    HStack {
                        Image(systemName: icon(for: conn.transport))
                            .foregroundStyle(tint(for: conn.transport))
                        Text(label(for: conn))
                    }
                }
            }
        }
    }

    private func icon(for state: TransportState) -> String {
        switch state {
        case .connected:    return "checkmark.circle.fill"
        case .connecting:   return "arrow.triangle.2.circlepath"
        case .disconnected: return "circle.slash"
        case .error:        return "exclamationmark.triangle.fill"
        default:            return "questionmark.circle"
        }
    }

    private func tint(for state: TransportState) -> Color {
        switch state {
        case .connected:    return .green
        case .connecting:   return .orange
        case .disconnected: return .secondary
        case .error:        return .red
        default:            return .secondary
        }
    }

    private func label(for conn: ReticulumEngine.ConnectionState) -> String {
        let kindName = conn.kind?.name.lowercased() ?? "—"
        let stateName: String
        switch conn.transport {
        case .connected:    stateName = "Connected"
        case .connecting:   stateName = "Connecting…"
        case .disconnected: stateName = "Disconnected"
        case .error:        stateName = "Error"
        default:            stateName = "Unknown"
        }
        return "\(kindName.uppercased()) — \(stateName)"
    }

    // ---- TCP transport -------------------------------------------------

    private var tcpSection: some View {
        Section("TCP transport node") {
            TextField("Host", text: $tcpHost)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            TextField("Port", text: $tcpPort)
                .keyboardType(.numberPad)

            if isTcpConnected {
                Button(role: .destructive) { store.disconnectTcp() } label: {
                    Label("Disconnect TCP", systemImage: "xmark.circle")
                }
            } else {
                Button {
                    guard let port = Int32(tcpPort), !tcpHost.isEmpty else { return }
                    store.connectTcp(host: tcpHost.trimmingCharacters(in: .whitespaces), port: port)
                } label: {
                    Label("Connect TCP", systemImage: "network")
                }
                .disabled(tcpHost.isEmpty || Int32(tcpPort) == nil)
            }

            if let err = store.lastConnectError {
                Text(err)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }

            Text("TCP attaches to a remote rnsd transport node. Anyone running that node can observe your announces and destination hash.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var isTcpConnected: Bool {
        store.connections.contains { $0.kind == .tcp && $0.transport == .connected }
    }

    // ---- Identity ------------------------------------------------------

    private var identitySection: some View {
        Section("Identity") {
            HStack {
                if let dest = store.ourDestHash {
                    Text(dest)
                        .font(.footnote.monospaced())
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer()
                    Button {
                        UIPasteboard.general.string = dest
                        hashCopiedAt = .now
                    } label: { Text("Copy") }
                        .buttonStyle(.bordered)
                } else {
                    Text("(unknown — connect first)")
                        .foregroundStyle(.secondary)
                }
            }
            if let copied = hashCopiedAt, copied.timeIntervalSinceNow > -1.5 {
                Text("Copied")
                    .font(.caption)
                    .foregroundStyle(.green)
            }
        }
    }

    // ---- About ---------------------------------------------------------

    private var aboutSection: some View {
        Section("About") {
            Text("Reticulum Mobile · iOS Phase 3")
                .font(.footnote)
            Text("BLE / Bluetooth Classic / radio config / diagnostics: not wired yet — see iosApp/README.md.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}
