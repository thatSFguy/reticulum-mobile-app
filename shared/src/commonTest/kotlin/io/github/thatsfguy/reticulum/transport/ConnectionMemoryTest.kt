package io.github.thatsfguy.reticulum.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ConnectionMemory.resolve] — the pure decision the app/service runs
 * on a cold start to decide which transport (if any) to auto-reconnect.
 *
 * camelCase test names keep the iosTest Kotlin/Native compile happy.
 */
class ConnectionMemoryTest {

    private fun resolve(
        autoReconnect: Boolean = true,
        kind: String? = null,
        bleAddress: String? = null,
        bleName: String? = null,
        btClassicAddress: String? = null,
        btClassicName: String? = null,
        tcpHost: String? = null,
        tcpPort: Int? = null,
        agnosticLoraAddress: String? = null,
        agnosticLoraName: String? = null,
        agnosticLoraUplink: String? = null,
    ) = ConnectionMemory.resolve(
        autoReconnect = autoReconnect,
        kind = kind,
        bleAddress = bleAddress,
        bleName = bleName,
        btClassicAddress = btClassicAddress,
        btClassicName = btClassicName,
        tcpHost = tcpHost,
        tcpPort = tcpPort,
        agnosticLoraAddress = agnosticLoraAddress,
        agnosticLoraName = agnosticLoraName,
        agnosticLoraUplink = agnosticLoraUplink,
    )

    @Test
    fun autoReconnectOffYieldsNull() {
        // The opt-out toggle short-circuits everything else.
        assertNull(resolve(autoReconnect = false, kind = "tcp", tcpHost = "h", tcpPort = 7822))
    }

    @Test
    fun noRememberedKindYieldsNull() {
        assertNull(resolve(kind = null))
        assertNull(resolve(kind = ""))
        // A kind we don't auto-restore (e.g. USB needs a re-granted
        // device permission) must not resolve.
        assertNull(resolve(kind = "usb"))
    }

    @Test
    fun bleResolvesWithAddress() {
        assertEquals(
            ConnectionMemory.Ble("AA:BB:CC:DD:EE:FF", "RNode"),
            resolve(kind = "ble", bleAddress = "AA:BB:CC:DD:EE:FF", bleName = "RNode"),
        )
    }

    @Test
    fun bleWithBlankAddressYieldsNull() {
        assertNull(resolve(kind = "ble", bleAddress = ""))
        assertNull(resolve(kind = "ble", bleAddress = null))
    }

    @Test
    fun bleBlankNameNormalizesToNull() {
        val m = resolve(kind = "ble", bleAddress = "AA:BB", bleName = "") as ConnectionMemory.Ble
        assertNull(m.name)
    }

    @Test
    fun btClassicResolvesWithAddress() {
        assertEquals(
            ConnectionMemory.BtClassic("11:22:33:44:55:66", "RNode BT"),
            resolve(kind = "btclassic", btClassicAddress = "11:22:33:44:55:66", btClassicName = "RNode BT"),
        )
    }

    @Test
    fun btClassicWithBlankAddressYieldsNull() {
        assertNull(resolve(kind = "btclassic", btClassicAddress = ""))
        assertNull(resolve(kind = "btclassic", btClassicAddress = null))
    }

    @Test
    fun tcpResolvesWithHostAndPort() {
        assertEquals(
            ConnectionMemory.Tcp("rns.example.net", 7822),
            resolve(kind = "tcp", tcpHost = "rns.example.net", tcpPort = 7822),
        )
    }

    @Test
    fun tcpWithBlankHostYieldsNull() {
        assertNull(resolve(kind = "tcp", tcpHost = "", tcpPort = 7822))
        assertNull(resolve(kind = "tcp", tcpHost = null, tcpPort = 7822))
    }

    @Test
    fun tcpWithOutOfRangePortYieldsNull() {
        assertNull(resolve(kind = "tcp", tcpHost = "h", tcpPort = 0))
        assertNull(resolve(kind = "tcp", tcpHost = "h", tcpPort = 70_000))
        assertNull(resolve(kind = "tcp", tcpHost = "h", tcpPort = null))
    }

    @Test
    fun agnosticLoraResolvesWithAddressAndUplink() {
        val m = resolve(
            kind = "agnosticlora",
            agnosticLoraAddress = "AA:BB:CC:DD:EE:FF",
            agnosticLoraName = "AgnLoRa-9828F51B",
            agnosticLoraUplink = "9828F51B",
        ) as ConnectionMemory.AgnosticLora
        assertEquals("AA:BB:CC:DD:EE:FF", m.address)
        assertEquals("AgnLoRa-9828F51B", m.name)
        assertEquals("9828F51B", m.uplinkNodeId)
    }

    @Test
    fun agnosticLoraWithoutAddressOrUplinkYieldsNull() {
        // Both the MAC and the uplink locator are required to reconnect.
        assertNull(resolve(kind = "agnosticlora", agnosticLoraAddress = "", agnosticLoraUplink = "9828F51B"))
        assertNull(resolve(kind = "agnosticlora", agnosticLoraAddress = "AA:BB", agnosticLoraUplink = ""))
        assertNull(resolve(kind = "agnosticlora", agnosticLoraAddress = "AA:BB", agnosticLoraUplink = null))
    }

    @Test
    fun kindPropertyRoundTripsTheConstants() {
        assertEquals(ConnectionMemory.KIND_BLE, ConnectionMemory.Ble("a", null).kind)
        assertEquals(ConnectionMemory.KIND_BT_CLASSIC, ConnectionMemory.BtClassic("a", null).kind)
        assertEquals(ConnectionMemory.KIND_TCP, ConnectionMemory.Tcp("h", 1).kind)
        assertEquals(ConnectionMemory.KIND_AGNOSTIC_LORA, ConnectionMemory.AgnosticLora("a", null, "9828F51B").kind)
    }
}
