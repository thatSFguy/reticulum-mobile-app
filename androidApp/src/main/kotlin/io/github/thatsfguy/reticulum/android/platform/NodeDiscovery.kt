package io.github.thatsfguy.reticulum.android.platform

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Which Bluetooth transport a discovered node speaks. [Dual] means the
 *  same MAC was seen advertising BLE NUS *and* is bonded as a Classic
 *  device — we connect such devices over BLE (the prefer-BLE rule). */
enum class NodeTransport { Ble, BtClassic, Dual }

/**
 * A single connectable node in the unified picker, regardless of how it
 * was discovered. The transport is *auto-detected* from where the device
 * showed up — a device advertising the Nordic UART Service over BLE is a
 * BLE RNode; a Classic RNode appears only in the system bonded list.
 *
 * [rssi] is null for bonded-only (Classic) entries — they aren't being
 * actively scanned, so we have no live signal strength.
 */
data class DiscoveredNode(
    val name: String?,
    val address: String,
    val rssi: Int?,
    val transport: NodeTransport,
)

/**
 * Composition layer over [BleScanner] and [BtClassicDevices] that yields
 * one transport-agnostic device list for the "Add node" picker. This is
 * the whole "scan & add regardless of BLE vs BT" trick: merge the two
 * discovery sources and let the connect router pick the transport from
 * [DiscoveredNode.transport] — no fragile runtime probe needed.
 *
 * Does not modify [BleScanner] / [BtClassicDevices]; it only combines
 * their outputs. TCP is intentionally absent — you can't scan for an
 * internet host, so it stays a separate "add network node" entry.
 */
object NodeDiscovery {

    /** Cold flow emitting the running merged set. The bonded snapshot is
     *  read once at collection start; BLE results stream in and are
     *  merged on each emission. Cancelling collection stops the BLE scan
     *  (via [BleScanner]'s `awaitClose`). */
    fun scan(context: Context): Flow<List<DiscoveredNode>> = flow {
        // One-shot snapshot — bonded devices don't change during a scan,
        // and re-reading per BLE callback would be wasteful.
        val bonded = BtClassicDevices.bonded(context)
        val bondedAddrs = bonded.mapTo(HashSet()) { it.address.uppercase() }

        emitAll(
            BleScanner.scan(context, BleScanKind.RNode)
                // Seed an empty BLE list so the bonded-only Classic
                // devices show immediately, before (or even without) any
                // BLE advertisement arriving.
                .onStart { emit(emptyList()) }
                .map { bleList -> merge(bleList, bonded, bondedAddrs) }
        )
    }

    private fun merge(
        ble: List<DiscoveredDevice>,
        bonded: List<BondedDevice>,
        bondedAddrs: Set<String>,
    ): List<DiscoveredNode> {
        val out = ArrayList<DiscoveredNode>(ble.size + bonded.size)
        val bleAddrs = HashSet<String>(ble.size)

        for (d in ble) {
            val key = d.address.uppercase()
            bleAddrs.add(key)
            out.add(
                DiscoveredNode(
                    name = d.name,
                    address = d.address,
                    rssi = d.rssi,
                    transport = if (key in bondedAddrs) NodeTransport.Dual else NodeTransport.Ble,
                )
            )
        }

        // Bonded Classic devices not seen over BLE this scan → Classic-only.
        for (b in bonded) {
            if (b.address.uppercase() in bleAddrs) continue
            out.add(
                DiscoveredNode(
                    name = b.name,
                    address = b.address,
                    rssi = null,
                    transport = NodeTransport.BtClassic,
                )
            )
        }

        // Connectable-now (live BLE signal) first by RSSI desc; then
        // bonded-only entries alphabetically. Mirrors the per-source sorts.
        return out.sortedWith(
            compareByDescending<DiscoveredNode> { it.rssi != null }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.name?.lowercase() ?: "zz_${it.address}" }
        )
    }
}
