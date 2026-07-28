package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.LinkState
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_ADV
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
import io.github.thatsfguy.reticulum.resource.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Inbound transfer-progress emissions (webclient v0.33.7 parity,
 * 2026-07-28): the receiving side surfaces 0% on ADV accept, one
 * emission per integer-percent change as parts land (throttle — not
 * one per chunk), and 100 from finalize once the segment reassembles.
 * Without this an inbound LoRa image is minutes of dead air before the
 * bubble materializes.
 */
class LinkResourceReceiverProgressTest {

    private data class Prog(val pct: Int, val bytes: Long, val total: Long)

    private fun activeLink(): Link {
        val crypto = TestVectors.crypto
        val link = Link(crypto)
        link.linkId = ByteArray(16) { (it + 0xa0).toByte() }
        val pseudoShared = ByteArray(32) { (it + 0xc0).toByte() }
        link.derivedKey = kotlinx.coroutines.runBlocking {
            crypto.hkdfDerive(pseudoShared, link.linkId!!, ByteArray(0), 64)
        }
        link.state = LinkState.ACTIVE
        return link
    }

    @Test fun `emits 0 on ADV, per-percent while chunks land, 100 on completion — duplicates don't tick`() = runTest {
        val crypto = TestVectors.crypto
        val tokenCrypto = TokenCrypto(crypto)
        val link = activeLink()
        val payload = ByteArray(3_000) { (it % 251).toByte() }
        val outbound = Resource.buildOutbound(
            plain   = payload,
            link    = tokenCrypto,
            linkKey = link.derivedKey!!,
            linkId  = link.linkId!!,
            crypto  = crypto,
        ).single()

        val events = mutableListOf<Prog>()
        var assembled: ByteArray? = null
        val receiver = LinkResourceReceiver(
            link = link,
            tokenCrypto = tokenCrypto,
            crypto = crypto,
            sender = { },
            logger = { },
            nowMs = { 1_700_000_000_000L },
            onAssembled = { plain, _, _ -> assembled = plain },
            onProgress = { pct, bytes, total -> events += Prog(pct, bytes, total) },
        )

        receiver.handleAdvertisement(parsePacket(buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_DATA,
            destHash   = link.linkId!!,
            context    = CTX_RESOURCE_ADV,
            payload    = outbound.advBodyCipher,
        ))!!)
        assertEquals(
            Prog(0, 0L, outbound.advertisement.transferSize), events.firstOrNull(),
            "ADV accept must immediately surface (0, 0, transferSize) — the UX shows " +
                "'receiving… 0%' before the first part lands. Got: ${events.firstOrNull()}",
        )

        suspend fun feed(chunk: ByteArray) = receiver.handleChunk(parsePacket(buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_DATA,
            destHash   = link.linkId!!,
            context    = CTX_RESOURCE,
            payload    = chunk,
        ))!!)

        val n = outbound.chunks.size
        assertTrue(n >= 5, "test needs ≥5 parts; got $n")

        // All but the last part, with a duplicate of part 0 in the middle.
        for (chunk in outbound.chunks.dropLast(1)) feed(chunk)
        val eventsBeforeDup = events.toList()
        feed(outbound.chunks.first())
        assertEquals(
            eventsBeforeDup, events,
            "a duplicate (rejected) chunk must not emit progress — re-transmissions during " +
                "loss recovery would otherwise inflate the bar and the rate readout",
        )

        // Percent trail so far: monotonic, capped below 100, bytes advancing.
        val pcts = events.map { it.pct }
        assertTrue(pcts == pcts.sorted(), "percent emissions must be non-decreasing. Got: $pcts")
        assertTrue(pcts.last() < 100, "100 is reserved for finalize; got ${pcts.last()} before the last part")
        assertEquals(
            (n - 1) * 100 / n, pcts.last(),
            "after ${n - 1} of $n parts the last emission must be ${(n - 1) * 100 / n}%. Got: $pcts",
        )
        assertTrue(events.last().bytes > 0, "bytesReceived must advance as parts land")

        // Final part → segment completes: finalize emits exactly 100 and
        // the assembled plaintext reaches the consumer.
        feed(outbound.chunks.last())
        assertEquals(100, events.last().pct, "finalize must emit 100")
        assertEquals(
            outbound.advertisement.transferSize, events.last().bytes,
            "at completion every advertised ciphertext byte has been received",
        )
        assertNotNull(assembled, "resource must assemble after the final part")
        assertContentEquals(payload, assembled, "assembled plaintext must round-trip")
    }
}
