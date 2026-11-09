package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.lxmf.packLinkMessage
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A message delivered over a Link names its recipient in its own bytes,
 * and a valid LINKIDENTIFY says only who the peer is. Neither may let a
 * peer pass off someone's message as sent to us, or redirect our replies.
 */
class LinkLxmfRecipientTest {
    private val crypto = TestVectors.crypto

    private class Party(val identity: Identity, val dest: ByteArray) {
        val hex: String get() = dest.toHex()
    }

    private inner class Rig(scope: TestScope) {
        val dests = InMemoryDestRepo()
        val messages = InMemoryMsgRepo()
        val engine = ReticulumEngine(
            crypto = crypto,
            identityRepo = InMemoryIdentityRepo(),
            destinationRepo = dests,
            messageRepo = messages,
            scope = scope.backgroundScope,
            nowMs = { 1_700_000_000_000L },
            displayNameProvider = { "Test" },
        )

        suspend fun us(): ByteArray =
            computeDestinationHash(crypto, "lxmf.delivery", engine.ensureIdentity().hash!!)

        suspend fun announceKnown(party: Party) {
            dests.upsertFromAnnounce(StoredDestination(
                hash = party.hex,
                identityHash = party.identity.hash!!.toHex(),
                publicKey = party.identity.publicKey,
                destHash = party.dest,
                nameHash = ByteArray(0),
                ratchetPub = party.identity.ratchetPubKey,
                displayName = "known",
                appName = "lxmf.delivery",
                appLabel = null,
                telemetry = null,
                lat = null, lon = null,
                appDataHex = "",
                lastSeen = 0,
                rssi = null,
                favorite = false,
                source = "test",
                hopCount = 1,
            ))
        }
    }

    private suspend fun party(): Party {
        val identity = Identity(crypto).also { it.generate() }
        return Party(identity, computeDestinationHash(crypto, "lxmf.delivery", identity.hash!!))
    }

    private suspend fun container(author: Party, recipient: ByteArray): ByteArray = packLinkMessage(
        sourceIdentity = author.identity,
        destHash = recipient,
        sourceHash = author.dest,
        title = "",
        content = "hello",
        timestampSeconds = 1_700_000_000.0,
        crypto = crypto,
    )

    @Test fun `message signed for another recipient is dropped`() = runTest {
        val rig = Rig(this)
        val alice = party()
        val mallory = party()
        rig.announceKnown(alice)

        val forwarded = container(alice, recipient = mallory.dest)
        rig.engine.handleLinkLxmf(forwarded, alice.hex, null, null, null)

        assertEquals(0, rig.messages.getAll().size, "a message for someone else must not be stored")
    }

    @Test fun `message signed for us is stored as verified`() = runTest {
        val rig = Rig(this)
        val alice = party()
        rig.announceKnown(alice)

        rig.engine.handleLinkLxmf(container(alice, rig.us()), alice.hex, null, null, null)

        val row = rig.messages.getAll().single()
        assertEquals("verified", row.state)
        assertEquals(alice.hex, row.arrivedViaDest)
    }

    @Test fun `verified message routes replies through the identified relay`() = runTest {
        val rig = Rig(this)
        val alice = party()
        val relay = party()
        rig.announceKnown(alice)

        rig.engine.handleLinkLxmf(container(alice, rig.us()), alice.hex, null, null, relay.hex)

        assertEquals(relay.hex, rig.messages.getAll().single().arrivedViaDest, "SPEC 5.9.8 relay routing")
    }

    @Test fun `unverified message cannot redirect replies to the link peer`() = runTest {
        val rig = Rig(this)
        val claimedAuthor = party()
        val mallory = party()

        val forged = packLinkMessage(
            sourceIdentity = mallory.identity,
            destHash = rig.us(),
            sourceHash = claimedAuthor.dest,
            title = "",
            content = "hello",
            timestampSeconds = 1_700_000_000.0,
            crypto = crypto,
        )
        rig.engine.handleLinkLxmf(forged, claimedAuthor.hex, null, null, mallory.hex)

        val row = rig.messages.getAll().single()
        assertEquals("unverified", row.state)
        assertEquals(claimedAuthor.hex, row.arrivedViaDest, "replies must not go to the link peer")
    }

    private fun unverifiedRow(contact: String, raw: ByteArray) = StoredMessage(
        contactHash = contact,
        direction = "incoming",
        content = "hello",
        title = "",
        timestamp = 1_700_000_000_000L,
        state = "unverified",
        attempts = 0,
        lastAttempt = 0,
        rawPacket = raw,
        rssi = null,
        hopCount = null,
        messageId = "mid",
        replyToMessageId = null,
        arrivedViaDest = contact,
    )

    @Test fun `retroactive verification rejects a message for another recipient`() = runTest {
        val rig = Rig(this)
        val alice = party()
        val mallory = party()
        rig.us()
        rig.messages.save(unverifiedRow(alice.hex, container(alice, recipient = mallory.dest)))

        rig.engine.reverifyMessagesFrom(alice.hex, alice.identity.publicKey)

        assertEquals("unverified", rig.messages.getAll().single().state)
    }

    @Test fun `retroactive verification accepts a message signed for us`() = runTest {
        val rig = Rig(this)
        val alice = party()
        rig.messages.save(unverifiedRow(alice.hex, container(alice, rig.us())))

        rig.engine.reverifyMessagesFrom(alice.hex, alice.identity.publicKey)

        assertEquals("verified", rig.messages.getAll().single().state)
    }
}
