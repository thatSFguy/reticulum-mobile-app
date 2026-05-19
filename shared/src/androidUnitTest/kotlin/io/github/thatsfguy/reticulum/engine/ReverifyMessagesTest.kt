package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.lxmf.packLinkMessage
import io.github.thatsfguy.reticulum.lxmf.packMessage
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [ReticulumEngine.reverifyMessagesFrom] — the retroactive
 * "unverified → verified" pass that runs when a sender's announce
 * finally arrives and gives us the Ed25519 key their earlier
 * messages couldn't be checked against.
 *
 * Regression guard for the bug found 2026-05-19: an unverified row's
 * stored plaintext is either an opportunistic LXMF body
 * (`source_hash + sig + msgpack`) or a link-delivered container
 * (`dest_hash + source_hash + sig + msgpack`) — different layouts,
 * different unpackers, and the row doesn't record which. The pass
 * only tried `unpackMessage` (the opportunistic form), so a
 * link-delivered unverified row — which is most large images, since
 * they ride a Resource over a Link — never re-verified and stayed
 * amber forever even after the announce arrived.
 */
class ReverifyMessagesTest {

    @Test fun `reverify flips both opportunistic and link-delivered rows`() = runTest {
        val crypto = TestVectors.crypto
        val repos = Triple(InMemoryIdentityRepo(), InMemoryDestRepo(), InMemoryMsgRepo())
        val engine = ReticulumEngine(
            crypto = crypto,
            identityRepo = repos.first,
            destinationRepo = repos.second,
            messageRepo = repos.third,
            scope = this,
            nowMs = { 1_700_000_000_000L },
            displayNameProvider = { "Test Receiver" },
        )
        val us = engine.ensureIdentity()
        val ourDest = computeDestinationHash(crypto, "lxmf.delivery", us.hash!!)

        val sender = Identity(crypto).also { it.generate() }
        val senderDest = computeDestinationHash(crypto, "lxmf.delivery", sender.hash!!)
        val senderHex = senderDest.toHex()

        // An opportunistic-format body and a link-delivered container,
        // both signed by the sender — the two plaintext shapes a
        // `rawPacket` on an unverified row can hold.
        val oppoPlain = packMessage(
            sourceIdentity = sender, destHash = ourDest, sourceHash = senderDest,
            title = "", content = "oppo text", timestampSeconds = 1_700_000_000.0,
            crypto = crypto,
        )
        val linkPlain = packLinkMessage(
            sourceIdentity = sender, destHash = ourDest, sourceHash = senderDest,
            title = "", content = "link image", timestampSeconds = 1_700_000_000.0,
            crypto = crypto,
        )

        // Both saved unverified, as they would be if the sender's key
        // wasn't in the destinations table when they arrived (e.g. the
        // contact had been evicted by announce-flood pressure).
        val oppoId = repos.third.save(StoredMessage(
            contactHash = senderHex, direction = "incoming", content = "oppo text",
            timestamp = 1L, state = "unverified", rawPacket = oppoPlain,
        ))
        val linkId = repos.third.save(StoredMessage(
            contactHash = senderHex, direction = "incoming", content = "link image",
            timestamp = 2L, state = "unverified", rawPacket = linkPlain,
        ))

        // The sender's announce just arrived — re-verify their backlog.
        engine.reverifyMessagesFrom(senderHex, sender.publicKey)

        assertEquals("verified", repos.third.getById(oppoId)?.state,
            "opportunistic unverified row must re-verify")
        assertEquals("verified", repos.third.getById(linkId)?.state,
            "link-delivered unverified row must re-verify — the bug was it never did")

        coroutineContext.cancelChildren()
    }
}
