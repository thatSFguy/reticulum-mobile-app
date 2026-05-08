// SPDX-License-Identifier: MIT
//
// Phase 3 — first real screen. Connect/disconnect a TCP transport
// against an rnsd `TCPServerInterface` (e.g. `RNS.MichMesh.net:7822`)
// and surface the resulting connection status. Mirrors the equivalent
// section of the Android `SettingsScreen.kt`. BLE picker + radio
// config + diagnostics log come in follow-up PRs.

import CoreBluetooth
import Shared
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: ReticulumStore

    @State private var tcpHost: String = "RNS.MichMesh.net"
    @State private var tcpPort: String = "7822"
    @State private var hashCopiedAt: Date? = nil
    @State private var showBleScanner: Bool = false

    /// One scanner instance per Settings view lifetime. Held as a
    /// StateObject so it survives view re-renders; its CBCentralManager
    /// is handed off to IosBleTransport when the user picks a device.
    @StateObject private var bleScanner = IosBleScanManager()

    var body: some View {
        NavigationStack {
            Form {
                statusSection
                bleSection
                tcpSection
                identitySection
                diagnosticsSection
                aboutSection
            }
            .navigationTitle("Settings")
            .sheet(isPresented: $showBleScanner) {
                BleScannerSheet(scanner: bleScanner) { picked in
                    showBleScanner = false
                    store.connectBle(scanner: bleScanner, picked: picked)
                }
            }
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

    // ---- BLE transport -------------------------------------------------

    private var bleSection: some View {
        Section("BLE (RNode)") {
            if isBleConnected {
                Button(role: .destructive) { store.disconnectBle() } label: {
                    Label("Disconnect BLE", systemImage: "xmark.circle")
                }
            } else {
                Button {
                    bleScanner.startScan()
                    showBleScanner = true
                } label: {
                    Label("Scan for RNode", systemImage: "antenna.radiowaves.left.and.right")
                }
            }
            Text("Bluetooth Low Energy attach to a local RNode advertising the Nordic UART Service. The RNode bridges to the LoRa RF mesh.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var isBleConnected: Bool {
        store.connections.contains { $0.kind == .ble && $0.transport == .connected }
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

    // ---- Diagnostics ---------------------------------------------------

    /// Live engine-event log + Copy-all + Clear actions. Mirrors the
    /// Android Settings log section so users can see exactly what the
    /// engine is doing — useful when "messages don't send" needs to
    /// be diagnosed without remote-attaching to the device. Last 500
    /// lines, oldest first.
    private var diagnosticsSection: some View {
        Section("Diagnostics") {
            HStack {
                Text("\(store.logLines.count) line\(store.logLines.count == 1 ? "" : "s")")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    UIPasteboard.general.string = store.logLines.joined(separator: "\n")
                } label: { Text("Copy") }
                    .buttonStyle(.bordered)
                    .disabled(store.logLines.isEmpty)
                Button(role: .destructive) {
                    store.clearLog()
                } label: { Text("Clear") }
                    .buttonStyle(.bordered)
                    .disabled(store.logLines.isEmpty)
            }
            if store.logLines.isEmpty {
                Text("No events yet. Connect a transport and send a message; engine events will appear here in arrival order (newest at the bottom).")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                // Reversed so newest entries are at the top — natural
                // for "what just happened" debugging without scrolling
                // through old chatter. ScrollView keeps the section
                // bounded so it doesn't dominate Settings.
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(store.logLines.enumerated().reversed()), id: \.offset) { _, line in
                            Text(line)
                                .font(.caption.monospaced())
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .frame(maxHeight: 280)
            }
        }
    }

    // ---- About ---------------------------------------------------------

    private var aboutSection: some View {
        Section("About") {
            Text("Reticulum Mobile · iOS")
                .font(.footnote)
            Text("Sideload-only — see iosApp/README.md for build / flash instructions.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - BLE scanner sheet

/// Presented modally from SettingsView. Owns the scanner UI but not
/// the scanner itself — the parent holds the @StateObject so its
/// CBCentralManager survives the sheet's lifecycle and can be reused
/// by IosBleTransport on connect.
private struct BleScannerSheet: View {
    @ObservedObject var scanner: IosBleScanManager
    let onPick: (DiscoveredPeripheral) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if !scanner.bluetoothReady {
                    Section {
                        Text(scanner.statusMessage ?? "Bluetooth not ready")
                            .foregroundStyle(.secondary)
                    }
                } else if scanner.discovered.isEmpty {
                    Section {
                        HStack {
                            ProgressView()
                            Text(scanner.isScanning ? "Scanning…" : "Idle")
                                .foregroundStyle(.secondary)
                        }
                    }
                } else {
                    Section {
                        ForEach(scanner.discovered) { dev in
                            Button {
                                onPick(dev)
                            } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(dev.displayName)
                                            .foregroundStyle(.primary)
                                        Text(dev.peripheral.identifier.uuidString)
                                            .font(.caption2.monospaced())
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                            .truncationMode(.middle)
                                    }
                                    Spacer()
                                    Text("\(dev.rssi) dBm")
                                        .font(.caption.monospaced())
                                        .foregroundStyle(rssiTint(dev.rssi))
                                }
                            }
                        }
                    } footer: {
                        Text("Pick the RNode you want to attach to. RSSI is signal strength — closer to zero is stronger.")
                    }
                }
            }
            .navigationTitle("Scan for RNode")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        scanner.stopScan()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if scanner.isScanning {
                        Button {
                            scanner.stopScan()
                        } label: {
                            Text("Stop")
                        }
                    } else {
                        Button {
                            scanner.startScan()
                        } label: {
                            Text("Rescan")
                        }
                    }
                }
            }
        }
    }

    private func rssiTint(_ rssi: Int) -> Color {
        switch rssi {
        case (-60)...0:    return .green
        case (-80)...(-61): return .orange
        default:           return .red
        }
    }
}
