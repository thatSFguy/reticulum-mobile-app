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

    /** An agnostic-LoRa-Net node over BLE-NUS. [address] is the BLE MAC
     *  (authoritative for reconnect); [name] is the `AgnLoRa-<id>` display
     *  hint; [uplinkNodeId] is the mesh node id every outbound packet is
     *  tunnelled toward — both are needed to rebuild the transport. */
    data class AgnosticLora(
        val address: String,
        val name: String?,
        val uplinkNodeId: String,
    ) : ConnectionMemory {
        override val kind: String get() = KIND_AGNOSTIC_LORA
    }

    companion object {
        const val KIND_BLE = "ble"
        const val KIND_BT_CLASSIC = "btclassic"
        const val KIND_TCP = "tcp"
        const val KIND_AGNOSTIC_LORA = "agnosticlora"

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
            agnosticLoraAddress: String? = null,
            agnosticLoraName: String? = null,
            agnosticLoraUplink: String? = null,
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

                KIND_AGNOSTIC_LORA ->
                    if (!agnosticLoraAddress.isNullOrBlank() && !agnosticLoraUplink.isNullOrBlank()) {
                        AgnosticLora(agnosticLoraAddress, agnosticLoraName?.ifBlank { null }, agnosticLoraUplink)
                    } else {
                        null
                    }

                else -> null
            }
        }
    }
}

/**
 * One user-saved node in the multi-node connection list (Phase 4).
 *
 * Where [ConnectionMemory] models the *single* last-connected transport
 * for silent auto-reconnect, [SavedNode] is an entry the user keeps around
 * and can switch between or forget. Covers BLE / Bluetooth-Classic (MAC in
 * [address], [port] null) and TCP (host in [address], [port] set). Kept
 * platform-independent so the Android `Preferences` store and a future iOS
 * `UserDefaults` store can share the encode/decode.
 */
data class SavedNode(
    val kind: String,            // one of ConnectionMemory.KIND_*
    val address: String,         // MAC (BLE/BtClassic) or host (TCP)
    val port: Int? = null,       // TCP only
    val name: String? = null,
) {
    /** Stable identity for upsert / forget. */
    val key: String get() = "$kind|$address|${port ?: ""}"

    /** One-line storage form. Fields are joined by the US control char
     *  (0x1F), which doesn't occur in MAC addresses, hostnames, or display
     *  names — so no escaping is needed. */
    fun encode(): String =
        listOf(kind, address, port?.toString() ?: "", name ?: "").joinToString(FIELD_SEP)

    companion object {
        private const val FIELD_SEP = "\u001F"

        /** Inverse of [encode]; returns null for a malformed line. */
        fun decode(line: String): SavedNode? {
            val p = line.split(FIELD_SEP)
            if (p.size < 4) return null
            if (p[0].isBlank() || p[1].isBlank()) return null
            return SavedNode(
                kind = p[0],
                address = p[1],
                port = p[2].toIntOrNull(),
                name = p[3].ifBlank { null },
            )
        }
    }
}
