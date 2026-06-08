package io.github.thatsfguy.reticulum.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedNodeTest {

    @Test fun bleRoundTrip() {
        val n = SavedNode(ConnectionMemory.KIND_BLE, "E1:AB:65:43:87:E1", null, "Rnode B4C1")
        assertEquals(n, SavedNode.decode(n.encode()))
    }

    @Test fun btClassicRoundTripNullName() {
        val n = SavedNode(ConnectionMemory.KIND_BT_CLASSIC, "4C:75:25:D5:01:2A", null, null)
        val back = SavedNode.decode(n.encode())
        assertEquals(n, back)
        assertNull(back?.name)
    }

    @Test fun tcpRoundTripWithPort() {
        val n = SavedNode(ConnectionMemory.KIND_TCP, "rns.example.net", 7822, null)
        val back = SavedNode.decode(n.encode())
        assertEquals(n, back)
        assertEquals(7822, back?.port)
    }

    @Test fun keyDistinguishesKindAndPort() {
        val ble = SavedNode(ConnectionMemory.KIND_BLE, "AA:BB", null, "x")
        val bt = SavedNode(ConnectionMemory.KIND_BT_CLASSIC, "AA:BB", null, "x")
        val tcp1 = SavedNode(ConnectionMemory.KIND_TCP, "h", 1)
        val tcp2 = SavedNode(ConnectionMemory.KIND_TCP, "h", 2)
        assertTrue(ble.key != bt.key)
        assertTrue(tcp1.key != tcp2.key)
        // Name is not part of identity — same node, renamed, is the same key.
        assertEquals(ble.key, ble.copy(name = "renamed").key)
    }

    @Test fun decodeRejectsMalformed() {
        assertNull(SavedNode.decode(""))
        assertNull(SavedNode.decode("ble"))
        // blank kind / address
        assertNull(SavedNode.decode(SavedNode("", "addr", null, null).encode()))
    }

    @Test fun listRoundTripViaNewlines() {
        val list = listOf(
            SavedNode(ConnectionMemory.KIND_BLE, "AA:BB:CC", null, "RNode"),
            SavedNode(ConnectionMemory.KIND_TCP, "host", 7822, null),
        )
        val raw = list.joinToString("\n") { it.encode() }
        val back = raw.split("\n").mapNotNull { SavedNode.decode(it) }
        assertEquals(list, back)
    }
}
