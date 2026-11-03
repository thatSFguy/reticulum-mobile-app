package io.github.thatsfguy.reticulum.announce

import io.github.thatsfguy.reticulum.codec.MessagePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SPEC §5.8.5 / §5.9.5: `lxmf.propagation` app_data is a 7-element array
 * whose element [6] metadata map carries the node name as BYTES under key
 * 0x01, beside STR values under 0xFE / 0x00 (LXMF 1.2).
 */
class PropagationNodeAppDataTest {

    private fun appData(
        accepting: Boolean = true,
        metadata: Map<Any?, Any?> = mapOf(
            0xFE to "lxmd",
            0x00 to "1.2.1",
            0x01 to "Bay Area PN".encodeToByteArray(),
        ),
    ): ByteArray = MessagePack.encode(
        listOf(false, 1_762_000_000L, accepting, 256L, 10_240L, listOf(16L, 3L, 18L), metadata),
    )

    @Test fun `name, node_state and transfer limit are read from an LXMF 1_2 announce`() {
        val info = parsePropagationNodeAppData(appData())!!
        assertEquals("Bay Area PN", info.name)
        assertEquals(true, info.accepting)
        assertEquals(256L, info.perTransferLimitKb)
    }

    @Test fun `unnamed node (LXMF 1_1 empty map) parses with a null name`() {
        val info = parsePropagationNodeAppData(appData(metadata = emptyMap()))!!
        assertNull(info.name)
    }

    @Test fun `node_state false is reported as not accepting`() {
        assertEquals(false, parsePropagationNodeAppData(appData(accepting = false))!!.accepting)
    }

    @Test fun `name is sanitized like any other announced name`() {
        val hostile = "Evil\nNode‮gnp".encodeToByteArray()
        val info = parsePropagationNodeAppData(appData(metadata = mapOf(0x01 to hostile)))!!
        assertEquals("EvilNodegnp", info.name)
    }

    @Test fun `wrong shapes are rejected, including the lxmf_delivery shape`() {
        assertNull(parsePropagationNodeAppData(ByteArray(0)))
        assertNull(parsePropagationNodeAppData(MessagePack.encode(listOf("Alice".encodeToByteArray(), 8L))))
        assertNull(parsePropagationNodeAppData(MessagePack.encode(listOf(false, 1L, true, 1L, 1L, listOf(1L), emptyMap<Any?, Any?>(), 9L))))
        assertNull(parsePropagationNodeAppData("plain text".encodeToByteArray()))
    }
}
