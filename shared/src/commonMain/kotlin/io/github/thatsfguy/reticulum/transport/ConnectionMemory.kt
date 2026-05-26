package io.github.thatsfguy.reticulum.transport

/**
 * A persisted record of the transport the app was last *connected* to,
 * so a cold start can re-establish it without the user re-picking and
 * re-tapping Connect every launch.
 *
 * This type is the platform-independent core of the connection-state
 * persistence feature: the per-platform stores (Android `Preferences`
 * over `SharedPreferences`, iOS `UserDefaults`) persist the flat fields
 * below, and the orchestration layer (Android `ReticulumService`, iOS
 * `ReticulumStore`) calls [resolve] on startup to decide what — if
 * anything — to reconnect.
 *
 * Only kinds whose reconnect parameters are fully self-contained are
 * modelled: BLE / Bluetooth-Classic (MAC address) and TCP (host:port).
 * USB is deliberately excluded — a USB device re-attach needs a
 * freshly-granted Android host permission, so it cannot be restored
 * silently.
 */
sealed interface ConnectionMemory {

    /** Persisted kind tag — see the `KIND_*` constants. */
    val kind: String

    /** A BLE (NUS-over-GATT) RNode, addressed by its [address] MAC. */
    data class Ble(val address: String, val name: String?) : ConnectionMemory {
        override val kind: String get() = KIND_BLE
    }

    /** A Bluetooth-Classic (RFCOMM/SPP) RNode, addressed by [address]. */
    data class BtClassic(val address: String, val name: String?) : ConnectionMemory {
        override val kind: String get() = KIND_BT_CLASSIC
    }

    /** A direct TCP attachment to an rnsd `TCPServerInterface`. */
    data class Tcp(val host: String, val port: Int) : ConnectionMemory {
        override val kind: String get() = KIND_TCP
    }

    /** A reticulum-loramesh firmware node over BLE-NUS (KISS-CRC16 dialect).
     *  Different wire protocol from [Ble] — the firmware does its own
     *  mesh routing under the surface and exposes a host-side
     *  `REGISTER_IDENTITY`/`DATA_TX`/`DATA_RX` opcode set, not RNode
     *  KISS. See `docs/mobile_ble_integration.md`. */
    data class LoraMesh(val address: String, val name: String?) : ConnectionMemory {
        override val kind: String get() = KIND_LORA_MESH
    }

    companion object {
        const val KIND_BLE = "ble"
        const val KIND_BT_CLASSIC = "btclassic"
        const val KIND_TCP = "tcp"
        const val KIND_LORA_MESH = "loramesh"

        /**
         * Resolve the transport to auto-reconnect on launch from the
         * persisted fields.
         *
         * Returns `null` — i.e. come up disconnected — when:
         *  - [autoReconnect] is off (the user opted out);
         *  - no [kind] was remembered, or it is one we don't restore;
         *  - the remembered kind's parameters are absent or malformed
         *    (blank MAC / host, port outside 1..65535).
         *
         * A blank [bleName] / [btClassicName] is normalised to `null`
         * (the name is only a display hint; the address is authoritative).
         */
        fun resolve(
            autoReconnect: Boolean,
            kind: String?,
            bleAddress: String?,
            bleName: String?,
            btClassicAddress: String?,
            btClassicName: String?,
            tcpHost: String?,
            tcpPort: Int?,
            loraMeshAddress: String? = null,
            loraMeshName: String? = null,
        ): ConnectionMemory? {
            if (!autoReconnect) return null
            return when (kind) {
                KIND_BLE ->
                    bleAddress?.takeIf { it.isNotBlank() }
                        ?.let { Ble(it, bleName?.ifBlank { null }) }

                KIND_BT_CLASSIC ->
                    btClassicAddress?.takeIf { it.isNotBlank() }
                        ?.let { BtClassic(it, btClassicName?.ifBlank { null }) }

                KIND_TCP ->
                    if (!tcpHost.isNullOrBlank() && tcpPort != null && tcpPort in 1..65_535) {
                        Tcp(tcpHost, tcpPort)
                    } else {
                        null
                    }

                KIND_LORA_MESH ->
                    loraMeshAddress?.takeIf { it.isNotBlank() }
                        ?.let { LoraMesh(it, loraMeshName?.ifBlank { null }) }

                else -> null
            }
        }
    }
}
