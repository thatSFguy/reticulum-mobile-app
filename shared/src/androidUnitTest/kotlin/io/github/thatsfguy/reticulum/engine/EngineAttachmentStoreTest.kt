package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.lxmf.packMessage
import io.github.thatsfguy.reticulum.protocol.CTX_NONE
import io.github.thatsfguy.reticulum.protocol.DEST_SINGLE
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.store.AttachmentStore
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.transport.IncomingPacket
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Attachment-store phase 2 (docs/ATTACHMENT-STORE.md §3.4-3.5): with an
 * [AttachmentStore] wired into the engine, an inbound LXMF attachment
 * (`FIELD_IMAGE` / `FIELD_FILE_ATTACHMENTS`) is written off-row into
 * the store and the `StoredMessage` row carries only an opaque token +
 * byte count — NOT the legacy multi-MB blob column. Without a store
 * (the engine unit-test default), the same paths fall back to the
 * legacy blob so a caller that hasn't wired a store still works.
 *
 * The receive path under test is the opportunistic `handleData` LXMF
 * branch — the test transport doesn't enforce the radio MTU, so it can
 * carry an attachment a real single-packet opportunistic send never
 * could; the engine's extract → off-row → save logic is identical to
 * the link-delivered and propagation paths.
 */
class EngineAttachmentStoreTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test fun `inbound image is stored off-row as a token, not a blob`() = runTest {
        val crypto = TestVectors.crypto
        val store = newStore()
        val rig = newRig(store)
        val us = rig.engine.ensureIdentity()
        val ourDest = computeDestinationHash(crypto, "lxmf.delivery", us.hash!!)

        val peer = Identity(crypto).also { it.generate() }
        val peerDest = computeDestinationHash(crypto, "lxmf.delivery", peer.hash!!)
        rig.repos.dest.upsertFromAnnounce(storedFor(peer, peerDest))
        rig.engine.attach(rig.transport, ReticulumEngine.TransportKind.Tcp)

        // A payload larger than the legacy 512 KB in-row image cap —
        // proof the off-row store accepts what no CursorWindow-bound
        // blob column ever could.
        val image = ByteArray(900_000) { (it * 31).toByte() }
        rig.transport.inject(IncomingPacket(
            buildOpportunisticLxmf(crypto, peer, peerDest, us, ourDest,
                fields = mapOf<Any?, Any?>(6 to listOf("jpg", image))),
            null,
        ))
        testScheduler.runCurrent()

        val saved = rig.repos.msg.getAll().firstOrNull { it.direction == "incoming" }
        assertNotNull(saved, "engine should have persisted the inbound LXMF")
        assertNull(saved.imageBytes, "image bytes must NOT land in the legacy in-row blob")
        assertNotNull(saved.imageToken, "row must carry an attachment-store token")
        assertEquals(image.size, saved.imageSize)
        val loaded = store.load(saved.imageToken!!)
        assertNotNull(loaded, "token must resolve to the stored file")
        assertTrue(image.contentEquals(loaded), "stored image bytes drifted")

        drain(rig)
    }

    @Test fun `inbound file attachment is stored off-row as a token`() = runTest {
        val crypto = TestVectors.crypto
        val store = newStore()
        val rig = newRig(store)
        val us = rig.engine.ensureIdentity()
        val ourDest = computeDestinationHash(crypto, "lxmf.delivery", us.hash!!)

        val peer = Identity(crypto).also { it.generate() }
        val peerDest = computeDestinationHash(crypto, "lxmf.delivery", peer.hash!!)
        rig.repos.dest.upsertFromAnnounce(storedFor(peer, peerDest))
        rig.engine.attach(rig.transport, ReticulumEngine.TransportKind.Tcp)

        val file = ByteArray(64_000) { (it + 7).toByte() }
        rig.transport.inject(IncomingPacket(
            buildOpportunisticLxmf(crypto, peer, peerDest, us, ourDest,
                fields = mapOf<Any?, Any?>(5 to listOf(listOf("report.pdf", file)))),
            null,
        ))
        testScheduler.runCurrent()

        val saved = rig.repos.msg.getAll().firstOrNull { it.direction == "incoming" }
        assertNotNull(saved)
        assertEquals("report.pdf", saved.attachmentName)
        assertNull(saved.attachmentBytes, "file bytes must NOT land in the legacy in-row blob")
        assertNotNull(saved.attachmentToken)
        assertEquals(file.size, saved.attachmentSize)
        assertTrue(file.contentEquals(store.load(saved.attachmentToken!!)!!))

        drain(rig)
    }

    @Test fun `without a store the engine falls back to the legacy in-row blob`() = runTest {
        // Regression guard for the engine unit-test default and any
        // caller that hasn't wired a store: the bytes must still be
        // persisted, just on the legacy column.
        val crypto = TestVectors.crypto
        val rig = newRig(attachmentStore = null)
        val us = rig.engine.ensureIdentity()
        val ourDest = computeDestinationHash(crypto, "lxmf.delivery", us.hash!!)

        val peer = Identity(crypto).also { it.generate() }
        val peerDest = computeDestinationHash(crypto, "lxmf.delivery", peer.hash!!)
        rig.repos.dest.upsertFromAnnounce(storedFor(peer, peerDest))
        rig.engine.attach(rig.transport, ReticulumEngine.TransportKind.Tcp)

        val image = ByteArray(4_096) { it.toByte() }
        rig.transport.inject(IncomingPacket(
            buildOpportunisticLxmf(crypto, peer, peerDest, us, ourDest,
                fields = mapOf<Any?, Any?>(6 to listOf("jpg", image))),
            null,
        ))
        testScheduler.runCurrent()

        val saved = rig.repos.msg.getAll().firstOrNull { it.direction == "incoming" }
        assertNotNull(saved)
        assertNull(saved.imageToken, "no store wired → no token")
        assertNotNull(saved.imageBytes, "no store wired → bytes fall back to the legacy blob")
        assertTrue(image.contentEquals(saved.imageBytes!!))

        drain(rig)
    }

    @Test fun `deleting a conversation removes its attachment files`() = runTest {
        // Phase 3 (docs/ATTACHMENT-STORE.md §3.7): clearing a
        // conversation must delete the off-row attachment files too,
        // else they leak on disk forever.
        val crypto = TestVectors.crypto
        val store = newStore()
        val rig = newRig(store)
        val us = rig.engine.ensureIdentity()
        val ourDest = computeDestinationHash(crypto, "lxmf.delivery", us.hash!!)

        val peer = Identity(crypto).also { it.generate() }
        val peerDest = computeDestinationHash(crypto, "lxmf.delivery", peer.hash!!)
        rig.repos.dest.upsertFromAnnounce(storedFor(peer, peerDest))
        rig.engine.attach(rig.transport, ReticulumEngine.TransportKind.Tcp)

        rig.transport.inject(IncomingPacket(
            buildOpportunisticLxmf(crypto, peer, peerDest, us, ourDest,
                fields = mapOf<Any?, Any?>(
                    6 to listOf("jpg", ByteArray(40_000) { it.toByte() }),
                    5 to listOf(listOf("note.txt", ByteArray(2_000))),
                )),
            null,
        ))
        testScheduler.runCurrent()

        val saved = rig.repos.msg.getAll().single { it.direction == "incoming" }
        val imageToken = saved.imageToken!!
        val fileToken = saved.attachmentToken!!
        assertNotNull(store.load(imageToken))
        assertNotNull(store.load(fileToken))

        rig.engine.deleteMessagesForDestination(saved.contactHash)

        assertNull(store.load(imageToken), "image file must be deleted with the conversation")
        assertNull(store.load(fileToken), "attachment file must be deleted with the conversation")

        drain(rig)
    }

    @Test fun `startup sweep deletes orphan files and keeps referenced ones`() = runTest {
        // §3.7 belt-and-braces: a file with no message row pointing at
        // it (a crash between row-delete and file-delete) is GC'd at
        // startup; a file a row still references survives.
        val store = newStore()
        val rig = newRig(store)

        val orphan = store.put(ByteArray(1_000) { 1 })
        val liveImage = store.put(ByteArray(1_000) { 2 })
        val liveFile = store.put(ByteArray(1_000) { 3 })
        rig.repos.msg.save(io.github.thatsfguy.reticulum.store.StoredMessage(
            contactHash = "aabbccdd",
            direction = "incoming",
            content = "keeps these",
            timestamp = 1L,
            imageToken = liveImage,
            attachmentToken = liveFile,
        ))

        rig.engine.sweepAttachmentsOnStartup()

        assertNull(store.load(orphan), "unreferenced file must be swept")
        assertNotNull(store.load(liveImage), "row-referenced image file must survive the sweep")
        assertNotNull(store.load(liveFile), "row-referenced attachment file must survive the sweep")

        drain(rig)
    }

    // ---- Helpers -----------------------------------------------------------

    private data class Rig(
        val engine: ReticulumEngine,
        val repos: TestRepos,
        val transport: InjectableTransport2,
    )

    private data class TestRepos(
        val identity: InMemoryIdentityRepo,
        val dest: InMemoryDestRepo,
        val msg: InMemoryMsgRepo,
    )

    private fun newStore(): AttachmentStore {
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "attach-test-${System.nanoTime()}",
        ).also { tempDirs.add(it) }
        return AttachmentStore(dir.absolutePath)
    }

    private fun TestScope.newRig(attachmentStore: AttachmentStore?): Rig {
        val repos = TestRepos(InMemoryIdentityRepo(), InMemoryDestRepo(), InMemoryMsgRepo())
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = repos.identity,
            destinationRepo = repos.dest,
            messageRepo = repos.msg,
            scope = this,
            nowMs = { 1_700_000_000_000L },
            displayNameProvider = { "Test Receiver" },
            attachmentStore = attachmentStore,
        )
        return Rig(engine, repos, InjectableTransport2())
    }

    private suspend fun TestScope.drain(rig: Rig) {
        rig.transport.disconnect()
        rig.engine.detach()
        coroutineContext.cancelChildren()
        testScheduler.advanceUntilIdle()
    }

    private fun storedFor(id: Identity, dest: ByteArray) =
        StoredDestination(
            hash = dest.toHex(),
            identityHash = id.hash!!.toHex(),
            publicKey = id.publicKey,
            destHash = dest,
            nameHash = ByteArray(0),
            ratchetPub = id.ratchetPubKey,
            displayName = "Peer",
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
        )

    private suspend fun buildOpportunisticLxmf(
        crypto: io.github.thatsfguy.reticulum.crypto.CryptoProvider,
        sourceIdentity: Identity,
        sourceHash: ByteArray,
        recipient: Identity,
        recipientDest: ByteArray,
        fields: Map<Any?, Any?>,
    ): ByteArray {
        val lxmfPlain = packMessage(
            sourceIdentity = sourceIdentity,
            destHash = recipientDest,
            sourceHash = sourceHash,
            title = "",
            content = "see attachment",
            timestampSeconds = 1_700_000_000.0,
            fields = fields,
            crypto = crypto,
        )
        val encrypted = TokenCrypto(crypto).encrypt(
            lxmfPlain, recipient.ratchetPubKey!!, recipient.hash!!,
        )
        return buildPacket(
            headerType = HEADER_1,
            destType = DEST_SINGLE,
            packetType = PACKET_DATA,
            destHash = recipientDest,
            context = CTX_NONE,
            payload = encrypted,
        )
    }
}

/** Channel-backed [Transport] with an [inject] hook — same shape as
 *  the one in OpportunisticArrivedViaDestTest (that file's copy is
 *  file-private, hence a second one here). */
private class InjectableTransport2 : Transport {
    private val _state = MutableStateFlow(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state
    private val _incoming = Channel<IncomingPacket>(64)
    override val incoming: Flow<IncomingPacket> = _incoming.receiveAsFlow()

    suspend fun inject(packet: IncomingPacket) { _incoming.send(packet) }

    override suspend fun connect() { _state.value = TransportState.Connected }
    override suspend fun disconnect() {
        _state.value = TransportState.Disconnected
        _incoming.close()
    }
    override suspend fun send(packet: ByteArray) { /* inbound-only test */ }
}
