// SPDX-License-Identifier: MIT
//
// CoreBluetooth scan manager — owns a CBCentralManager and emits
// the running set of discovered NUS-advertising peripherals as a
// SwiftUI @Published list. The Phase 2 IosBleTransport takes an
// already-discovered CBPeripheral; this manager is the discovery
// step that produces it.
//
// Sharing the central: when the user picks a device, the manager
// hands its CBCentralManager off to the IosBleTransport (the
// transport sets itself as the delegate). The scanner can stay
// instantiated but stops emitting because the central no longer
// routes callbacks to it. Phase 4 polish — fine for foreground use;
// background mode + state restoration is later work.

import CoreBluetooth
import Foundation

@MainActor
final class IosBleScanManager: NSObject, ObservableObject {

    @Published var discovered: [DiscoveredPeripheral] = []
    @Published var isScanning: Bool = false
    @Published var bluetoothReady: Bool = false
    @Published var statusMessage: String? = nil

    /// The underlying central. Exposed so the store can hand it to
    /// IosBleTransport along with the picked peripheral — they need to
    /// share the same central instance so the peripheral's connection
    /// callbacks land on the right delegate.
    let central: CBCentralManager

    private let nusUuid = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    override init() {
        // Pass nil for the queue so callbacks land on main — saves a
        // hop to @MainActor. CoreBluetooth's main-queue callbacks are
        // fine for low-frequency events like connect / discover.
        self.central = CBCentralManager(delegate: nil, queue: nil)
        super.init()
        central.delegate = self
    }

    func startScan() {
        guard bluetoothReady else {
            statusMessage = "Bluetooth not ready (\(stateText(central.state)))"
            return
        }
        discovered.removeAll()
        statusMessage = nil
        isScanning = true
        // Filter on the NUS service UUID so we only surface RNode-like
        // devices, not every nearby beacon. Some firmware doesn't
        // advertise the service in its ad packet (only post-connect)
        // and won't appear here — that's a known limitation; Android
        // has the same constraint.
        central.scanForPeripherals(withServices: [nusUuid], options: nil)
    }

    func stopScan() {
        if isScanning { central.stopScan() }
        isScanning = false
    }

    private func stateText(_ s: CBManagerState) -> String {
        switch s {
        case .poweredOn:    return "powered on"
        case .poweredOff:   return "off"
        case .unauthorized: return "permission denied"
        case .unsupported:  return "not supported on this device"
        case .resetting:    return "resetting"
        case .unknown:      return "unknown"
        @unknown default:   return "unknown"
        }
    }
}

extension IosBleScanManager: CBCentralManagerDelegate {

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let state = central.state
        Task { @MainActor in
            self.bluetoothReady = (state == .poweredOn)
            if state != .poweredOn {
                self.statusMessage = "Bluetooth \(self.stateText(state))"
                self.isScanning = false
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = peripheral.name
            ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let id = peripheral.identifier
        let rssi = RSSI.intValue
        Task { @MainActor in
            // Dedup by identifier; refresh RSSI on re-discover so the
            // signal-strength sort stays meaningful.
            if let idx = self.discovered.firstIndex(where: { $0.id == id }) {
                self.discovered[idx].rssi = rssi
                self.discovered[idx].name = name ?? self.discovered[idx].name
            } else {
                self.discovered.append(
                    DiscoveredPeripheral(peripheral: peripheral, name: name, rssi: rssi)
                )
            }
            // Sort RSSI-DESC so closest device floats up.
            self.discovered.sort { $0.rssi > $1.rssi }
        }
    }
}

struct DiscoveredPeripheral: Identifiable, Equatable {
    let peripheral: CBPeripheral
    var name: String?
    var rssi: Int

    var id: UUID { peripheral.identifier }
    var displayName: String { name ?? "(unnamed)" }

    static func == (lhs: DiscoveredPeripheral, rhs: DiscoveredPeripheral) -> Bool {
        lhs.id == rhs.id && lhs.rssi == rhs.rssi && lhs.name == rhs.name
    }
}
