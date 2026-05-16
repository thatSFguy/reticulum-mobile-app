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
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject private var store: ReticulumStore

    /// TCP host/port persist across launches via UserDefaults; the
    /// store seeds them on first launch from the KnownTcpNodes
    /// curated rotation (see ReticulumStore.seedTcpDefaultsIfMissing).
    /// Bound here as @AppStorage so the "Pick another" button can
    /// rewrite both keys and the TextFields update immediately.
    @AppStorage("tcp.host") private var tcpHost: String = "RNS.MichMesh.net"
    @AppStorage("tcp.port") private var tcpPortInt: Int = 7822
    /// Editable text mirror of [tcpPortInt]. Two-way bound on edit so
    /// the user can clear / retype without losing the underlying Int.
    @State private var tcpPort: String = "7822"
    @State private var hashCopiedAt: Date? = nil
    @State private var showBleScanner: Bool = false
    @State private var showResetIdentityConfirm: Bool = false
    @State private var showIdentityCardSheet: Bool = false
    /// Same UserDefaults key the root ContentView reads for
    /// `.preferredColorScheme(...)`. Three values: "system", "light",
    /// "dark". Changes apply immediately app-wide.
    @AppStorage("themePreference") private var themePreference: String = "system"

    /// Scanner is owned by ReticulumStore so its CBCentralManager
    /// exists at app launch — required for iOS BLE state restoration
    /// (willRestoreState must fire on a central re-instantiated with
    /// the same restore identifier, before any other delegate call).
    /// SettingsView just reads it through the environment.
    private var bleScanner: IosBleScanManager { store.bleScanner }

    var body: some View {
        NavigationStack {
            Form {
                statusSection
                bleSection
                tcpSection
                radioConfigSection
                identitySection
                propagationSection
                appearanceSection
                privacySection
                experimentalSection
                diagnosticsSection
                aboutSection
            }
            // Tester report (2026-05-10): the keyboard sometimes sat
            // there after a tester finished typing in a Settings field.
            // SwiftUI's default behaviour is "until you submit or the
            // field goes away"; swiping anywhere on the form now
            // resigns first responder, matching iMessage / Mail / etc.
            .scrollDismissesKeyboard(.immediately)
            .keyboardDoneToolbar()
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
            // Physical-proximity threat-model notice. The Nordic UART
            // BLE profile (NUS) is unauthenticated by default —
            // anyone within ~30 m who can impersonate the RNode could
            // inject crafted packets into our parser. The KISS parser
            // has a 64 KB ceiling (MED-1) so the OOM vector is
            // closed, but packet-injection is still possible. Pair
            // the RNode in iOS Settings → Bluetooth first to harden.
            // Audit reference: 2026-05-13 MED-3.
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                Text(
                    "BLE attaches to your RNode over the Nordic UART (NUS) profile, "
                    + "which is unauthenticated by default. Anyone within ~30 m who can "
                    + "impersonate the RNode could inject crafted packets. To harden "
                    + "against this, pair the RNode in Settings → Bluetooth first."
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)

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
        Section {
            // Operator-trust notice. Lives at the top of the section
            // so the user reads it before picking a host/port and
            // tapping Connect. Reticulum's spec assumes the transport
            // node operator can see destination hashes and announces
            // — by design, not specific to our implementation, but
            // worth surfacing for LoRa users whose intuition skews
            // off-grid. Audit reference: 2026-05-13 MED-5.
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                Text(
                    "TCP attaches to a remote rnsd transport node over the internet. "
                    + "Whoever operates that node can observe your destination hash, "
                    + "see every announce you emit, and log when you're online. "
                    + "Message contents stay end-to-end encrypted, but metadata is not."
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)

            TextField("Host", text: $tcpHost)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            TextField("Port", text: Binding(
                get: { tcpPort },
                set: { newValue in
                    let filtered = newValue.filter { $0.isNumber }
                    tcpPort = filtered
                    if let n = Int(filtered), n > 0, n <= 65_535 {
                        tcpPortInt = n
                    }
                }
            ))
                .keyboardType(.numberPad)
                .onAppear { tcpPort = String(tcpPortInt) }

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
        } header: {
            HStack {
                Text("TCP transport node")
                Spacer()
                // "Pick another" — re-rolls the host/port to a
                // different curated entry. Useful if the current
                // default is overloaded or down. Disabled while a TCP
                // connection is live so the user has to disconnect
                // first (otherwise the on-screen values would drift
                // from the actually-attached endpoint). Mirrors the
                // Android Settings shuffle button.
                Button {
                    let pick = IosEngineFactoryKt.pickDifferentTcpNode(
                        currentHost: tcpHost,
                        currentPort: Int32(tcpPortInt)
                    )
                    tcpHost = pick.host
                    tcpPortInt = Int(pick.port)
                    tcpPort = String(pick.port)
                } label: {
                    Text("Pick another")
                        .textCase(nil)
                }
                .disabled(isTcpConnected)
            }
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

            // Display-name editor — peers see this label in their
            // Nodes / Messages list. Bound to UserDefaults via
            // @AppStorage so the engine's displayNameProvider closure
            // (set in ReticulumStore.init) reads the same key on the
            // next announce. "Save" persists + triggers an immediate
            // re-announce so peers don't have to wait for the 5-min
            // auto-announce. Mirrors the Android Identity → Display
            // name TextField.
            DisplayNameField()

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

            // Identity backup — passphrase-encrypted .rmid archive,
            // wire-compatible with the Android export/import. Saving via
            // .fileExporter / loading via .fileImporter.
            IdentityBackupBlock()
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

    // ---- Radio config (RNode) -----------------------------------------

    /// LoRa radio config form — frequency / BW / SF / CR / TX power.
    /// Mirrors the Android `Section("Radio config (RNode)")` block.
    /// Each field is bound to a UserDefaults key via @AppStorage so
    /// changes survive relaunch; "Save & apply" pushes the values to
    /// every attached RNode (calls IosBleTransport.applyRadioConfig).
    /// Values must match the rest of the local mesh — wrong freq / BW
    /// / SF means no one hears the user.
    private var radioConfigSection: some View {
        Section("Radio config (RNode)") {
            RadioConfigForm(reapply: { store.reapplyRadioConfig() })
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

    // ---- Privacy & security --------------------------------------------

    /// Toggle: when ON, inbound LXMF whose signature can't be matched
    /// against a known announce is silently dropped instead of saved
    /// as `state="unverified"`. Off by default — preserves the legacy
    /// retroactive-verify UX. Backed by the same UserDefaults key the
    /// ReticulumStore reads in its dropUnverified provider closure.
    /// Audit reference: 2026-05-13 MED-6.
    @AppStorage("security.dropUnverified") private var dropUnverified: Bool = false

    private var privacySection: some View {
        Section("Privacy & security") {
            Toggle(isOn: $dropUnverified) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Drop unverified messages")
                    Text(
                        "When ON, inbound messages whose signature can't be checked against a "
                        + "known announce are silently dropped. Default OFF — they're shown as "
                        + "'Unverified sender' and re-verified retroactively once the sender's "
                        + "announce arrives. Turn ON to harden against display-name phishing "
                        + "on first contact."
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
        }
    }

    // ---- Experimental --------------------------------------------------

    /// Off by default. RRC (Reticulum Relay Chat) is a new wire protocol
    /// still under development — gated so it stays invisible to ordinary
    /// users until it's interop-verified. Mirrors the Android
    /// `experimental_rrc` preference.
    @AppStorage("experimental.rrc") private var experimentalRrc: Bool = false

    private var experimentalSection: some View {
        Section("Experimental") {
            Toggle(isOn: $experimentalRrc) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Reticulum Relay Chat")
                    Text(
                        "IRC-style group chat over Reticulum hubs. In active "
                        + "development and not yet interop-verified — enable only "
                        + "to help test it. When ready it adds a Rooms view "
                        + "alongside Direct in Messages."
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
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

// MARK: - Display name editor

/// Editable display-name TextField. The persisted name lands in the
/// outbound LXMF announce as the human-readable label peers see in
/// their Nodes / Messages / Graph rows. Empty / whitespace-only input
/// falls back to "Reticulum Mobile" inside ReticulumStore — the Save
/// button is disabled when the input is blank to make that obvious.
private struct DisplayNameField: View {
    @EnvironmentObject private var store: ReticulumStore
    @AppStorage("displayName") private var stored: String = "Reticulum Mobile"
    @State private var draft: String = ""
    @State private var didLoad: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            TextField("Display name", text: $draft)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled(true)
            HStack {
                let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                let unsaved = trimmed != stored
                Button {
                    store.setDisplayName(trimmed)
                    stored = trimmed
                } label: {
                    Text(unsaved ? "Save name" : "Saved")
                }
                .buttonStyle(.bordered)
                .disabled(!unsaved || trimmed.isEmpty)
                Button {
                    draft = stored
                } label: {
                    Text("Revert")
                }
                .buttonStyle(.borderless)
                .disabled(!unsaved)
            }
            Text("Saved name is broadcast in your next announce so peers can label you. Editing triggers an immediate re-announce.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .onAppear {
            // Populate the editable draft from the persisted value
            // exactly once per view lifetime so re-entering Settings
            // doesn't clobber an in-progress edit.
            if !didLoad {
                draft = stored
                didLoad = true
            }
        }
    }
}

// MARK: - Radio config form (RNode)

/// Five-field form bound to UserDefaults via @AppStorage. The store's
/// `currentRadioConfig` snapshots the same keys at BLE connect /
/// reapply time, so what's saved here is what the RNode receives.
/// Defaults match Android's RadioConfig.kt defaults (US 902-928 ISM,
/// 250 kHz BW, SF 10 for range, CR 4/5, +22 dBm TX).
private struct RadioConfigForm: View {
    @AppStorage("radio.frequencyHz")    private var freqHz: Int = 904_375_000
    @AppStorage("radio.bandwidthHz")    private var bwHz: Int = 250_000
    @AppStorage("radio.spreadingFactor") private var sf: Int = 10
    @AppStorage("radio.codingRate")      private var cr: Int = 5
    @AppStorage("radio.txPowerDbm")      private var txp: Int = 22

    /// Editable text views over the persisted Int values. Track these
    /// separately so the user can clear a field and retype without
    /// losing the underlying default.
    @State private var freqMhzText: String = ""
    @State private var bwKhzText: String = ""
    @State private var sfText: String = ""
    @State private var crText: String = ""
    @State private var txpText: String = ""
    @State private var unsaved: Bool = false

    let reapply: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Applied automatically when BLE connects to an RNode. Match these to the rest of your mesh (RatDeck / Sideband / NomadNet peers) — wrong freq/BW/SF means no one hears you.")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                LabelledNumberField(label: "Freq (MHz)", text: $freqMhzText, allowDecimal: true) {
                    unsaved = true
                }
                LabelledNumberField(label: "BW (kHz)", text: $bwKhzText, allowDecimal: false) {
                    unsaved = true
                }
            }
            HStack {
                LabelledNumberField(label: "SF (7-12)", text: $sfText, allowDecimal: false) {
                    unsaved = true
                }
                LabelledNumberField(label: "CR (5-8)", text: $crText, allowDecimal: false) {
                    unsaved = true
                }
                LabelledNumberField(label: "TX (dBm)", text: $txpText, allowDecimal: false, allowNegative: true) {
                    unsaved = true
                }
            }
            Button {
                if let f = Double(freqMhzText) { freqHz = Int(f * 1_000_000) }
                if let b = Int(bwKhzText)      { bwHz   = b * 1_000 }
                if let s = Int(sfText)         { sf     = s }
                if let c = Int(crText)         { cr     = c }
                if let t = Int(txpText)        { txp    = t }
                unsaved = false
                reapply()
            } label: {
                Label(unsaved ? "Save & apply" : "Saved", systemImage: "checkmark.circle")
            }
            .buttonStyle(.bordered)
            .disabled(!unsaved)
        }
        .onAppear {
            // Populate text fields from the persisted Ints on first show
            // so re-entering Settings shows the saved values, not blank.
            freqMhzText = String(format: "%g", Double(freqHz) / 1_000_000.0)
            bwKhzText   = String(bwHz / 1_000)
            sfText      = String(sf)
            crText      = String(cr)
            txpText     = String(txp)
        }
    }
}

/// Tiny labelled number-only TextField. Filters to digits + optional
/// dot / minus on every keystroke so the saved Int parse never sees
/// junk. Calls [onEdit] after each filtered set so the parent can
/// flip its "unsaved" flag.
private struct LabelledNumberField: View {
    let label: String
    @Binding var text: String
    let allowDecimal: Bool
    var allowNegative: Bool = false
    let onEdit: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField("", text: Binding(
                get: { text },
                set: { newValue in
                    let filtered = newValue.filter { c in
                        c.isNumber ||
                            (allowDecimal && c == ".") ||
                            (allowNegative && c == "-")
                    }
                    if filtered != text {
                        text = filtered
                        onEdit()
                    } else if filtered != newValue {
                        // Force-redraw with filtered value when user
                        // typed a stray char.
                        text = filtered
                    } else {
                        text = newValue
                        onEdit()
                    }
                }
            ))
                .textFieldStyle(.roundedBorder)
                .keyboardType(allowDecimal ? .decimalPad : (allowNegative ? .numbersAndPunctuation : .numberPad))
        }
    }
}

// MARK: - Identity backup (Export / Import .rmid)

/// Passphrase-encrypted identity archive UI. Mirrors the Android
/// `IdentityBackupBlock` composable in SettingsScreen.kt. The `.rmid`
/// wire format is shared (PBKDF2-HMAC-SHA256 → HKDF-split → AES-256-CBC
/// + HMAC-SHA256, encrypt-then-MAC) so a backup made on Android imports
/// cleanly on iOS and vice-versa.
///
/// Three transient states drive the dialogs:
/// - [pendingExportPassphrase]: Export tapped, passphrase prompt open
/// - [exportPayload]: bytes ready, fileExporter sheet open for SAF-write
/// - [pendingImportArchive]: file picked, passphrase prompt open
/// - [pendingReplaceConfirm]: passphrase entered, "are you sure" prompt
private struct IdentityBackupBlock: View {
    @EnvironmentObject private var store: ReticulumStore

    @State private var pendingExportPassphrase: String? = nil
    @State private var exportPayload: RmidDocument? = nil
    @State private var pendingImportArchive: Data? = nil
    @State private var importPassphrase: String = ""
    @State private var pendingReplaceConfirm: Bool = false
    @State private var importerOpen: Bool = false
    @State private var errorText: String? = nil
    @State private var busy: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Button {
                    errorText = nil
                    pendingExportPassphrase = ""
                } label: { Label("Export identity…", systemImage: "square.and.arrow.up") }
                    .buttonStyle(.bordered)
                    .disabled(busy)
                Button {
                    errorText = nil
                    importerOpen = true
                } label: { Label("Import identity…", systemImage: "square.and.arrow.down") }
                    .buttonStyle(.bordered)
                    .disabled(busy)
            }
            Text("Encrypted with a passphrase. Save the .rmid file somewhere safe (Drive, password manager, etc.) — anyone with both the file AND the passphrase can impersonate you.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        // Export passphrase prompt → produces RmidDocument → fileExporter
        .alert("Export identity",
               isPresented: Binding(
                get: { pendingExportPassphrase != nil },
                set: { if !$0 { pendingExportPassphrase = nil } }
               )
        ) {
            SecureField("Passphrase", text: Binding(
                get: { pendingExportPassphrase ?? "" },
                set: { pendingExportPassphrase = $0 }
            ))
            Button("Export") {
                guard let passphrase = pendingExportPassphrase else { return }
                // Defense in depth: client-side check stops the
                // confirm tap on weak input; IdentityArchive.pack
                // re-checks at the engine layer.
                // Audit reference: 2026-05-13 HIGH-3.
                guard assessPassphraseSwift(passphrase).acceptable else { return }
                Task {
                    busy = true
                    defer { busy = false }
                    do {
                        let data = try await store.exportIdentityArchive(passphrase: passphrase)
                        pendingExportPassphrase = nil
                        exportPayload = RmidDocument(data: data)
                    } catch {
                        errorText = "Export failed: \(error)"
                        pendingExportPassphrase = nil
                    }
                }
            }
            .disabled(busy || !assessPassphraseSwift(pendingExportPassphrase ?? "").acceptable)
            Button("Cancel", role: .cancel) { pendingExportPassphrase = nil }
        } message: {
            // SwiftUI alerts render the message statically; we can't
            // show a live strength meter the way the Android dialog
            // does. Spell the policy out so the user knows what
            // "strong" means here. Anyone with the file AND the
            // passphrase is them on the mesh forever.
            Text(
                "Pick a strong passphrase — ≥12 chars with mixed character types, "
                + "OR ≥20 chars of any kind. Anyone with the .rmid file AND the "
                + "passphrase can impersonate you on the mesh."
            )
        }
        // SAF-equivalent save sheet for the encrypted bytes.
        .fileExporter(
            isPresented: Binding(
                get: { exportPayload != nil },
                set: { if !$0 { exportPayload = nil } }
            ),
            document: exportPayload,
            contentType: .data,
            defaultFilename: "reticulum-identity.rmid"
        ) { result in
            exportPayload = nil
            if case .failure(let err) = result {
                errorText = "Couldn't save archive: \(err.localizedDescription)"
            }
        }
        // Import file pick → load bytes → prompt for passphrase.
        .fileImporter(isPresented: $importerOpen, allowedContentTypes: [.data]) { result in
            switch result {
            case .success(let url):
                let didStart = url.startAccessingSecurityScopedResource()
                defer { if didStart { url.stopAccessingSecurityScopedResource() } }
                do {
                    let data = try Data(contentsOf: url)
                    pendingImportArchive = data
                    importPassphrase = ""
                    errorText = nil
                } catch {
                    errorText = "Couldn't read archive: \(error.localizedDescription)"
                }
            case .failure(let err):
                // User-cancelled file picks raise CocoaError.userCancelled;
                // surfacing those as errors would be noisy.
                if (err as NSError).code != NSUserCancelledError {
                    errorText = "Couldn't open archive: \(err.localizedDescription)"
                }
            }
        }
        // Import passphrase prompt → Continue advances to replace-confirm.
        .alert("Import identity",
               isPresented: Binding(
                get: { pendingImportArchive != nil && !pendingReplaceConfirm },
                set: { if !$0 && !pendingReplaceConfirm { pendingImportArchive = nil; importPassphrase = "" } }
               )
        ) {
            SecureField("Passphrase", text: $importPassphrase)
            Button("Continue") {
                guard !importPassphrase.isEmpty else { return }
                pendingReplaceConfirm = true
            }
            .disabled(busy || importPassphrase.isEmpty)
            Button("Cancel", role: .cancel) {
                pendingImportArchive = nil
                importPassphrase = ""
            }
        } message: {
            Text("Enter the passphrase the archive was encrypted with.")
        }
        // Replace-confirmation — overwriting the current identity is
        // permanent unless the user has already exported the old one.
        .alert("Replace current identity?", isPresented: $pendingReplaceConfirm) {
            Button("Replace", role: .destructive) {
                guard let archive = pendingImportArchive else { return }
                let passphrase = importPassphrase
                Task {
                    busy = true
                    defer { busy = false }
                    do {
                        try await store.importIdentityArchive(archive: archive, passphrase: passphrase)
                        pendingReplaceConfirm = false
                        pendingImportArchive = nil
                        importPassphrase = ""
                        errorText = nil
                    } catch {
                        errorText = "Import failed (wrong passphrase or corrupt archive)"
                        pendingReplaceConfirm = false
                        // Keep pendingImportArchive set so the passphrase
                        // alert reopens — the user can retry without
                        // re-picking the file.
                    }
                }
            }
            Button("Cancel", role: .cancel) {
                pendingReplaceConfirm = false
            }
        } message: {
            Text("This permanently overwrites your current identity with the imported one. Anyone messaging your old destination hash won't reach you anymore. Active link sessions will be torn down. Existing message history stays. If you didn't already export your current identity, this can't be undone.")
        }
        // Inline error banner for the latest export/import failure.
        .overlay(alignment: .bottom) {
            if let err = errorText {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .padding(.horizontal, 4)
                    .padding(.top, 6)
            }
        }
    }
}

/// Lightweight FileDocument wrapper for the encrypted `.rmid` archive
/// bytes. SwiftUI's fileExporter requires its document type to be
/// FileDocument-conforming; we only need the write path so the read
/// init throws.
private struct RmidDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        // Imports go through the fileImporter path, not this one. Throw
        // so SwiftUI surfaces a clear error instead of silently producing
        // an empty document if anyone wires read-mode by mistake.
        throw CocoaError(.fileReadUnsupportedScheme)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

/// Strength bands for the identity-export passphrase. Mirrors the
/// Kotlin `PassphraseStrength` enum in the shared module so the two
/// implementations stay synchronised at review time — the source of
/// truth is `IdentityArchive.pack` which re-runs the same policy
/// before encrypting, so the worst case if these drift is "iOS lets
/// the user tap Export and then the engine throws". Audit reference:
/// 2026-05-13 HIGH-3.
enum PassphraseStrengthLevel {
    case tooWeak, acceptable, strong
}

struct PassphraseAssessmentSwift {
    let strength: PassphraseStrengthLevel
    let acceptable: Bool
    let reason: String?
}

/// Assess `passphrase` under the same policy as the Kotlin
/// `assessPassphrase`:
///   - len ≥ 20 (any character set) → strong
///   - len ≥ 12 with ≥ 3 of {lower, upper, digit, symbol} → strong
///   - len ≥ 12 with 2 classes → acceptable
///   - everything else → tooWeak
func assessPassphraseSwift(_ passphrase: String) -> PassphraseAssessmentSwift {
    let len = passphrase.count
    let hasLower  = passphrase.contains { ("a"..."z").contains($0) }
    let hasUpper  = passphrase.contains { ("A"..."Z").contains($0) }
    let hasDigit  = passphrase.contains { ("0"..."9").contains($0) }
    let hasSymbol = passphrase.contains { c in
        !(("a"..."z").contains(c) || ("A"..."Z").contains(c) || ("0"..."9").contains(c))
    }
    let classes = [hasLower, hasUpper, hasDigit, hasSymbol].filter { $0 }.count

    if len == 0 {
        return .init(strength: .tooWeak, acceptable: false, reason: "Passphrase required.")
    }
    if len >= 20 {
        return .init(strength: .strong, acceptable: true, reason: nil)
    }
    if len >= 12 && classes >= 3 {
        return .init(strength: .strong, acceptable: true, reason: nil)
    }
    if len >= 12 && classes >= 2 {
        return .init(
            strength: .acceptable, acceptable: true,
            reason: "OK — but consider longer (≥20 chars) or 3+ character classes."
        )
    }
    return .init(
        strength: .tooWeak, acceptable: false,
        reason: len < 12
            ? "Passphrase too short. Use ≥12 chars with mixed types, OR ≥20 chars of any kind."
            : "Passphrase too narrow. Include ≥2 of: lowercase, uppercase, digits, symbols."
    )
}
