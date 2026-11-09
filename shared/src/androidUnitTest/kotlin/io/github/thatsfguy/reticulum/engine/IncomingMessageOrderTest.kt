package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.lxmf.packLinkMessage
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A reply must not sort above the message it answers because of clock skew. */
class IncomingMessageOrderTest {
    private val crypto = TestVectors.crypto
    private val now = 1_700_000_000_000L

    private suspend fun incomingTimestamp(backgroundScope: kotlinx.coroutines.CoroutineScope, sentAt: Long?, contactIsPeer: Boolean = true): Long {
        val messages = InMemoryMsgRepo()
        val engine = ReticulumEngine(
            crypto = crypto,
            identityRepo = InMemoryIdentityRepo(),
            destinationRepo = InMemoryDestRepo(),
            messageRepo = messages,
            scope = backgroundScope,
            nowMs = { now },
            displayNameProvider = { "Test" },
        )
        val peer = Identity(crypto).also { it.generate() }
        val peerDest = computeDestinationHash(crypto, "lxmf.delivery", peer.hash!!)
        val ourDest = computeDestinationHash(crypto, "lxmf.delivery", engine.ensureIdentity().hash!!)
        if (sentAt != null) {
            messages.save(StoredMessage(
                contactHash = if (contactIsPeer) peerDest.toHex() else "other",
                direction = "outgoing", content = "/version", timestamp = sentAt,
            ))
        }
        val body = packLinkMessage(
            sourceIdentity = peer, destHash = ourDest, sourceHash = peerDest,
            title = "", content = "reply",
            timestampSeconds = (now - 300L) / 1000.0, crypto = crypto,
        )
        engine.handleLinkLxmf(body, peerDest.toHex(), null, null, null)
        return messages.getAll().single { it.direction == "incoming" }.timestamp
    }

    @Test fun `a link reply slightly behind our send sorts after it`() = runTest {
        assertEquals(now, incomingTimestamp(backgroundScope, sentAt = now))
    }

    @Test fun `a send to someone else does not move it`() = runTest {
        assertEquals(now - 300L, incomingTimestamp(backgroundScope, sentAt = now, contactIsPeer = false))
    }

    @Test fun `with nothing sent it keeps the sender's time`() = runTest {
        assertEquals(now - 300L, incomingTimestamp(backgroundScope, sentAt = null))
    }
}
