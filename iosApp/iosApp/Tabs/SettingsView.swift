// SPDX-License-Identifier: MIT
//
// Phase 3 — first real screen. Connect/disconnect a TCP transport
// against an rnsd `TCPServerInterface` (e.g. `RNS.MichMesh.net:7822`)
// and surface the resulting connection status. Mirrors the equivalent
// section of the Android `SettingsScreen.kt`. BLE picker + radio
// config + diagnostics log come in follow-up PRs.

import CoreBluetooth
import CoreImage.CIFilterBuiltins
import Shared
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: ReticulumStore

    @State private var tcpHost: String = "RNS.MichMesh.net"
    @State private var tcpPort: String = "7822"
    @State private var hashCopiedAt: Date? = nil
    @State private var showBleScanner: Bool = false
    @State private var showResetIdentityConfirm: Bool = false
    @State private var showIdentityCardSheet: Bool = false
    /// Same UserDefaults key the root ContentView reads for
    /// `.preferredColorScheme(...)`. Three values: "system", "light",
    /// "dark". Changes apply immediately app-wide.
    @AppStorage("themePreference") private var themePreference: String = "system"

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
                propagationSection
                appearanceSection
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
            .sheet(isPresented: $showIdentityCardSheet) {
                IdentityCardSheet()
            }
            .alert("Reset identity?", isPresented: $showResetIdentityConfirm) {
                Button("Reset", role: .destructive) { store.resetIdentity() }
                Button("Cancel", role: .cancel) { }
            } message: {
                Text("Generates a new keypair and a new destination hash. Anyone who knew your old hash will need to see a fresh announce from you. Contacts and message history stay on this device.")
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

            // Identity actions: announce, show QR card to share with
            // peers, hard-reset. Mirrors the Android Settings →
            // Identity row block.
            HStack {
                Button {
                    store.sendAnnounce()
                } label: { Label("Announce", systemImage: "antenna.radiowaves.left.and.right") }
                    .buttonStyle(.bordered)
                Spacer()
                Button {
                    showIdentityCardSheet = true
                } label: { Label("QR card", systemImage: "qrcode") }
                    .buttonStyle(.bordered)
                    .disabled(store.ourDestHash == nil)
            }
            Button(role: .destructive) {
                showResetIdentityConfirm = true
            } label: { Label("Reset identity…", systemImage: "arrow.counterclockwise") }
                .buttonStyle(.bordered)

            Text("An announce is sent automatically every 5 minutes while connected. Share your QR card so peers can add you without typing the 32-character hash.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    // ---- Propagation ---------------------------------------------------

    /// Surfaces lxmf.propagation nodes the engine has heard about and
    /// lets the user trigger a sync. Mirrors the Android Propagation
    /// section — auto-picks the closest by hop count and falls
    /// through up to 5 candidates.
    private var propagationSection: some View {
        Section("Propagation") {
            let nodes = store.propagationNodes
            if nodes.isEmpty {
                Text("No propagation nodes seen yet. Once a peer announces with name_hash 'lxmf.propagation' it'll show up here and you can pull queued messages.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                let ranked = nodes.sorted { lhs, rhs in
                    if lhs.hopCount != rhs.hopCount { return lhs.hopCount < rhs.hopCount }
                    return lhs.lastSeen > rhs.lastSeen
                }
                if let best = ranked.first {
                    let ageMinutes = max(0, (Int64(Date().timeIntervalSince1970 * 1000) - best.lastSeen) / 60_000)
                    Text("\(nodes.count) propagation node(s) seen. Closest: \(best.hopCount) hop\(best.hopCount == 1 ? "" : "s"), last seen \(ageMinutes)m ago.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Button {
                    store.syncPropagationAuto()
                } label: { Label("Sync now", systemImage: "arrow.down.circle") }
                    .buttonStyle(.bordered)
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
            let displayed = store.displayedLogLines
            HStack {
                if store.verboseLog || displayed.count == store.logLines.count {
                    Text("\(displayed.count) line\(displayed.count == 1 ? "" : "s")")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text("\(displayed.count) of \(store.logLines.count)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    UIPasteboard.general.string = displayed.joined(separator: "\n")
                } label: { Text("Copy") }
                    .buttonStyle(.bordered)
                    .disabled(displayed.isEmpty)
                Button(role: .destructive) {
                    store.clearLog()
                } label: { Text("Clear") }
                    .buttonStyle(.bordered)
                    .disabled(store.logLines.isEmpty)
            }
            // Verbose toggle — when off, per-packet `rx ...` wire
            // traces are hidden (they're high-volume routine chatter
            // that drowns out the high-signal `msg #N: ...` /
            // `LINKREQUEST rejected: ...` lines). Defaults off.
            Toggle("Verbose (show wire traces)", isOn: $store.verboseLog)
                .font(.caption)
            if displayed.isEmpty {
                Text(store.logLines.isEmpty
                     ? "No events yet. Connect a transport and send a message; engine events will appear here in arrival order (newest at the top)."
                     : "All current lines are hidden by the verbose filter. Toggle Verbose to see wire traces.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                // Reversed so newest entries are at the top — natural
                // for "what just happened" debugging without scrolling
                // through old chatter. ScrollView keeps the section
                // bounded so it doesn't dominate Settings.
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(displayed.enumerated().reversed()), id: \.offset) { _, line in
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

    // ---- Appearance ----------------------------------------------------

    private var appearanceSection: some View {
        Section("Appearance") {
            Picker("Theme", selection: $themePreference) {
                Text("System").tag("system")
                Text("Light").tag("light")
                Text("Dark").tag("dark")
            }
            .pickerStyle(.segmented)
            Text("System follows iOS Display & Brightness. Light/Dark pin the app regardless of the device setting.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    // ---- About ---------------------------------------------------------

    private var aboutSection: some View {
        Section("About") {
            Text("Reticulum Mobile · iOS \(versionString)")
                .font(.footnote)
            Text("Sideload-only — see iosApp/README.md for build / flash instructions.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    /// Reads CFBundleShortVersionString + CFBundleVersion from the
    /// app's Info.plist so the user can tell support which build
    /// they're running. Falls back to `dev` if neither is set
    /// (which would be a build-script bug).
    private var versionString: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "dev"
        let build = info?["CFBundleVersion"] as? String
        if let b = build, b != short { return "\(short) (\(b))" }
        return short
    }
}

// MARK: - Identity QR card sheet

/// Renders a QR code of the user's IdentityCard JSON for peers to
/// scan from their Add-by-hash flow. Uses CoreImage's built-in QR
/// generator — same wire format the Android `myIdentityCard()` Kotlin
/// extension produces, so QR exchange works cross-platform.
private struct IdentityCardSheet: View {
    @EnvironmentObject private var store: ReticulumStore
    @Environment(\.dismiss) private var dismiss
    @State private var cardJson: String?
    @State private var qrImage: UIImage?

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if let img = qrImage {
                    Image(uiImage: img)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 320, maxHeight: 320)
                        .padding()
                } else {
                    ProgressView("Building card…")
                        .frame(maxWidth: 320, maxHeight: 320)
                }
                if let dest = store.ourDestHash {
                    Text(dest)
                        .font(.footnote.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .padding(.horizontal)
                }
                Text("Have the other device scan this from their Nodes → Add → Scan QR. They'll be able to message you immediately, even before your next announce reaches them.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
                Spacer()
            }
            .padding()
            .navigationTitle("Identity card")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task { await buildCard() }
        }
    }

    private func buildCard() async {
        do {
            // myIdentityCard() returns an IdentityCard.Payload; encode
            // to the standard JSON wire form via IdentityCard.encode.
            let card = try await store.engine.myIdentityCard()
            let json = IdentityCard.shared.encode(payload: card)
            cardJson = json
            qrImage = generateQrCode(from: json)
        } catch {
            cardJson = nil
            qrImage = nil
        }
    }

    private func generateQrCode(from string: String) -> UIImage? {
        let data = Data(string.utf8)
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = data
        filter.correctionLevel = "M"  // Medium — fits ~500 chars at this density
        guard let ciImage = filter.outputImage else { return nil }
        // Scale up so the QR isn't blurry on retina screens. Nearest-
        // neighbor (.interpolation(.none) on the Image) keeps it crisp.
        let scaled = ciImage.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
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
