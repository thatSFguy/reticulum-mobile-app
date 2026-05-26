package io.github.thatsfguy.reticulum.android.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import io.github.thatsfguy.reticulum.platform.BleTransport
import io.github.thatsfguy.reticulum.platform.LoraMeshBleTransport
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Active scan for BLE devices that advertise the Nordic UART Service.
 * Matches the same service UUID a Reticulum RNode uses, so the result
 * list is a tight set of "things this app can probably connect to".
 *
 * Returned [DiscoveredDevice]s are deduplicated by MAC address. RSSI
 * is the most recent observation for that device.
 *
 * Caller MUST hold BLUETOOTH_SCAN at runtime (Android 12+) and
 * BLUETOOTH_CONNECT before attempting a connect to any of the
 * results. [BlePermissions] handles that prompt.
 */
data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
)

/** Which class of NUS-speaking device the user is looking for. Both
 *  the RNode firmware and the reticulum-loramesh firmware advertise
 *  the same Nordic UART Service UUID, so a service-UUID-only filter
 *  can't distinguish them. The discriminator is the advertised name
 *  prefix: `rlm-xxxxxx` for LoraMesh nodes, anything else for RNode. */
enum class BleScanKind { RNode, LoraMesh }

object BleScanner {

    /** Cold flow that emits the running set of discovered devices
     *  matching [kind]. Cancelling collection stops the scan. */
    @SuppressLint("MissingPermission")
    fun scan(
        context: Context,
        kind: BleScanKind = BleScanKind.RNode,
    ): Flow<List<DiscoveredDevice>> = callbackFlow {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = mgr.adapter?.bluetoothLeScanner
        if (scanner == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val seen = mutableMapOf<String, DiscoveredDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val displayName = dev.name ?: result.scanRecord?.deviceName
                // Both transports share the NUS service UUID, so the
                // platform filter alone passes RNodes and LoraMesh
                // nodes through together. Discriminate by the
                // advertised name prefix the firmware sets: LoraMesh
                // nodes always start with "rlm-", RNodes never do.
                //
                // BUT — the reticulum-loramesh firmware emits its
                // name in the SCAN_RESPONSE packet, not the main ADV
                // packet (Bluefruit's `ScanResponse.addName()` in
                // Ble.cpp). On Android the first onScanResult for an
                // unbonded device often arrives with name=null and
                // the platform dedupes subsequent callbacks, so a
                // strict "name starts with rlm-" filter loses the
                // device entirely. Include nameless devices in BOTH
                // kinds and let the user pick — same fallback the
                // RNode path already used.
                val isLoraMesh = displayName
                    ?.startsWith(LoraMeshBleTransport.ADVERTISED_NAME_PREFIX, ignoreCase = true) == true
                val nameless = displayName.isNullOrBlank()
                val matches = when (kind) {
                    BleScanKind.LoraMesh -> isLoraMesh || nameless
                    BleScanKind.RNode    -> !isLoraMesh
                }
                if (!matches) return
                seen[dev.address] = DiscoveredDevice(
                    name = displayName,
                    address = dev.address,
                    rssi = result.rssi,
                )
                trySend(seen.values.sortedByDescending { it.rssi })
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }
        }

        // Filter on the NUS service UUID so the list is just RNode-class
        // devices, not every nearby beacon. If a device fails to advertise
        // the service in its ad packet (some firmwares only expose it
        // post-connect), it won't appear here — fall back to manual MAC.
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleTransport.NUS_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
