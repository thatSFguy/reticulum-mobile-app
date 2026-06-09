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
import io.github.thatsfguy.reticulum.transport.AgnosticLoraTunnel
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

/** Which class of NUS-speaking device the user is looking for. Both RNodes
 *  and agnostic-LoRa-Net nodes advertise the same Nordic UART Service UUID,
 *  so a service-UUID-only filter can't separate them. The discriminator is
 *  the advertised name prefix: agnostic-LoRa nodes are `AgnLoRa-<id>`,
 *  RNodes are not. */
enum class BleScanKind { RNode, AgnLoRa }

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
                // RNodes and agnostic-LoRa nodes both pass the NUS service
                // filter; discriminate by the `AgnLoRa-` name prefix. Keep
                // nameless devices in BOTH kinds — some firmwares emit their
                // name only in the SCAN_RESPONSE packet or post-connect, so
                // the first onScanResult for an unbonded device often arrives
                // with name=null and the platform dedupes later callbacks.
                val isAgnLora = displayName
                    ?.startsWith(AgnosticLoraTunnel.ADVERTISED_NAME_PREFIX, ignoreCase = true) == true
                val nameless = displayName.isNullOrBlank()
                val matches = when (kind) {
                    BleScanKind.AgnLoRa -> isAgnLora || nameless
                    BleScanKind.RNode   -> !isAgnLora
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
