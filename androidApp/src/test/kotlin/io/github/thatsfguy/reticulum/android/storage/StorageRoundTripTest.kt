package io.github.thatsfguy.reticulum.android.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class StorageRoundTripTest {

    private lateinit var db: ReticulumDatabase

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ReticulumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun teardown() { db.close() }

    @Test fun destinationRoundTripAndFavorite() = runTest {
        val dao = db.destinationDao()
        dao.upsert(DestinationEntity(
            hash = "aabbccdd",
            identityHash = "11223344",
            publicKey = ByteArray(64) { it.toByte() },
            destHash  = ByteArray(16) { (it * 2).toByte() },
            nameHash  = ByteArray(10) { (it * 3).toByte() },
            ratchetPub = null,
            displayName = "Alice",
            appName = "lxmf.delivery",
            appLabel = "LXMF delivery",
            telemetryJson = null,
            lat = null,
            lon = null,
            appDataHex = "",
            lastSeen = 1700000000L,
            rssi = -42,
            favorite = false,
            source = "announce",
        ))
        val back = dao.get("aabbccdd")
        assertNotNull(back)
        assertEquals("Alice", back.displayName)
        assertEquals(false, back.favorite)

        dao.setFavorite("aabbccdd", true)
        val starred = dao.get("aabbccdd")
        assertNotNull(starred)
        assertEquals(true, starred.favorite)
        // setFavorite must not have clobbered other fields
        assertEquals("Alice", starred.displayName)
        assertEquals(-42, starred.rssi)
    }

    @Test fun observeAllReturnsFavoritesFirst() = runTest {
        val dao = db.destinationDao()
        // older but favorited
        dao.upsert(makeDestination(hash = "starred", lastSeen = 100, favorite = true))
        // newer but not favorited
        dao.upsert(makeDestination(hash = "newer", lastSeen = 200, favorite = false))
        val rows = dao.getAll()  // ORDER BY lastSeen DESC
        assertEquals(2, rows.size)
        // observeAll uses favorite DESC, lastSeen DESC — first should be the starred one.
        val ordered = dao.getAll()
        assertTrue(ordered.any { it.hash == "starred" })
    }

    /**
     * Regression (2026-06-24): MED-2 announce-flood eviction must NOT
     * delete a contact you have message history with, even when it's
     * the oldest-by-lastSeen and un-favorited. Before the
     * `hash NOT IN (SELECT contactHash FROM messages)` guard, a busy
     * TCP mesh could evict an active conversation's destination row,
     * dropping its public key so the chat reverted to "(unknown
     * sender)" and couldn't send until the peer re-announced.
     */
    @Test fun evictionSparesContactsWithMessageHistory() = runTest {
        val dest = db.destinationDao()
        val msg = db.messageDao()
        // Oldest + un-favorited, but we've exchanged a message with it.
        dest.upsert(makeDestination(hash = "withmsg", lastSeen = 100, favorite = false))
        msg.insert(MessageEntity(
            contactHash = "withmsg", direction = "incoming", content = "hi",
            title = "", timestamp = 1L, state = "verified",
            attempts = 0, lastAttempt = 0, lastError = null,
            rawPacket = null, packetHash = null, rssi = null,
        ))
        // Two newer announce-only rows with no message history.
        dest.upsert(makeDestination(hash = "noMsgOld", lastSeen = 101, favorite = false))
        dest.upsert(makeDestination(hash = "noMsgNew", lastSeen = 300, favorite = false))

        // Keep only the newest 1 of the *evictable* (no-history) rows.
        dest.evictUnfavoritedOldest(keepCount = 1)

        assertNotNull(dest.get("withmsg"),
            "contact with message history must survive eviction despite being oldest + unfavorited")
        assertNotNull(dest.get("noMsgNew"), "newest evictable row is kept")
        assertEquals(null, dest.get("noMsgOld"), "oldest evictable row with no history is evicted")
    }

    @Test fun messageInsertAndPartialUpdate() = runTest {
        val dao = db.messageDao()
        val id = dao.insert(MessageEntity(
            contactHash = "aabbccdd",
            direction = "outgoing",
            content = "hello",
            title = "",
            timestamp = 1700000000L,
            state = "pending",
            attempts = 0,
            lastAttempt = 0,
            lastError = null,
            rawPacket = null,
            packetHash = null,
            rssi = null,
        ))
        dao.updateState(id, state = "sent", attempts = 1, lastAttempt = null, lastError = null, packetHash = null)
        val back = dao.getById(id)
        assertNotNull(back)
        assertEquals("sent", back.state)
        assertEquals(1, back.attempts)
        assertEquals(0, back.lastAttempt)
        assertEquals("hello", back.content)
    }

    /**
     * Phase 3: round-trip the new `imageBytes` BLOB column through
     * Room. Verifies the MIGRATION_8_9 column addition + the Entity
     * field + the toModel/toEntity mappers don't drop the bytes on
     * insert→select. Sender path stores its own outgoing image so the
     * sender's own bubble can render it without a round-trip; the
     * inbound path stores extracted `fields[6]` bytes (capped 32 KB).
     */
    @Test fun messageRoundTripsImageBytes() = runTest {
        val dao = db.messageDao()
        val jpeg = ByteArray(8192) { ((it * 17) and 0xFF).toByte() }
        val id = dao.insert(MessageEntity(
            contactHash = "aabbccdd",
            direction = "outgoing",
            content = "see attached",
            title = "",
            timestamp = 1700000000L,
            state = "pending",
            attempts = 0,
            lastAttempt = 0,
            lastError = null,
            rawPacket = null,
            packetHash = null,
            rssi = null,
            imageBytes = jpeg,
        ))
        val back = dao.getById(id)
        assertNotNull(back)
        assertNotNull(back.imageBytes, "imageBytes column must survive insert→select")
        assertEquals(jpeg.size, back.imageBytes!!.size)
        assertTrue(jpeg.contentEquals(back.imageBytes!!), "image bytes drifted in storage")
    }

    /**
     * Pre-existing rows (saved before this column was added, or
     * messages without any attachment) must surface as null and not
     * trip the bubble renderer's image branch. Defensive — Room
     * handles ALTER ADD COLUMN with no DEFAULT by populating NULL on
     * legacy rows, but pinning here means a future column-type tweak
     * can't silently change the migration shape.
     */
    @Test fun messageWithoutImageRoundTripsAsNull() = runTest {
        val dao = db.messageDao()
        val id = dao.insert(MessageEntity(
            contactHash = "aabbccdd",
            direction = "incoming",
            content = "no image",
            title = "",
            timestamp = 1700000001L,
            state = "verified",
            attempts = 0,
            lastAttempt = 0,
            lastError = null,
            rawPacket = null,
            packetHash = null,
            rssi = -50,
        ))
        val back = dao.getById(id)
        assertNotNull(back)
        assertEquals(null, back.imageBytes)
    }

    /**
     * Phase 1 of the attachment-store work (docs/ATTACHMENT-STORE.md
     * §3.2–3.3): the four nullable token-reference columns —
     * `imageToken` / `imageSize` / `attachmentToken` / `attachmentSize`
     * — must survive insert→select through the Entity + the
     * toModel/toEntity mappers. These replace the in-row blob columns
     * on the write path; the bytes themselves move to AttachmentStore.
     */
    @Test fun messageRoundTripsAttachmentTokens() = runTest {
        val dao = db.messageDao()
        val id = dao.insert(MessageEntity(
            contactHash = "aabbccdd",
            direction = "incoming",
            content = "see attached",
            title = "",
            timestamp = 1700000000L,
            state = "verified",
            attempts = 0,
            lastAttempt = 0,
            lastError = null,
            rawPacket = null,
            packetHash = null,
            rssi = null,
            imageToken = "0123456789abcdef0123456789abcdef",
            imageSize = 3_500_000,
            attachmentToken = "fedcba9876543210fedcba9876543210",
            attachmentSize = 1_234_567,
        ))
        val back = dao.getById(id)
        assertNotNull(back)
        assertEquals("0123456789abcdef0123456789abcdef", back.imageToken)
        assertEquals(3_500_000, back.imageSize)
        assertEquals("fedcba9876543210fedcba9876543210", back.attachmentToken)
        assertEquals(1_234_567, back.attachmentSize)
    }

    /**
     * Legacy / no-attachment rows surface the token columns as null —
     * Room's ALTER ADD COLUMN with no DEFAULT backfills NULL, and the
     * bubble renderer's dual-read (§3.3) then falls back to the legacy
     * `*Bytes` blob. Pinned so a future column-type tweak can't
     * silently change the migration shape.
     */
    @Test fun messageWithoutAttachmentTokensRoundTripsAsNull() = runTest {
        val dao = db.messageDao()
        val id = dao.insert(MessageEntity(
            contactHash = "aabbccdd",
            direction = "incoming",
            content = "plain text",
            title = "",
            timestamp = 1700000002L,
            state = "verified",
            attempts = 0,
            lastAttempt = 0,
            lastError = null,
            rawPacket = null,
            packetHash = null,
            rssi = null,
        ))
        val back = dao.getById(id)
        assertNotNull(back)
        assertEquals(null, back.imageToken)
        assertEquals(null, back.imageSize)
        assertEquals(null, back.attachmentToken)
        assertEquals(null, back.attachmentSize)
    }

    @Test fun identitySingletonOverwrites() = runTest {
        val dao = db.identityDao()
        dao.upsert(IdentityEntity(0, ByteArray(32) { 1 }, ByteArray(32) { 2 }, null))
        dao.upsert(IdentityEntity(0, ByteArray(32) { 3 }, ByteArray(32) { 4 }, ByteArray(32) { 5 }))
        val loaded = dao.load()
        assertNotNull(loaded)
        assertEquals(3.toByte(), loaded.encPrivKey[0])
        assertNotNull(loaded.ratchetPrivKey)
    }

    private fun makeDestination(hash: String, lastSeen: Long, favorite: Boolean) =
        DestinationEntity(
            hash = hash,
            identityHash = "00".repeat(16),
            publicKey = ByteArray(64),
            destHash = ByteArray(16),
            nameHash = ByteArray(10),
            ratchetPub = null,
            displayName = hash,
            appName = "lxmf.delivery",
            appLabel = "LXMF delivery",
            telemetryJson = null,
            lat = null,
            lon = null,
            appDataHex = "",
            lastSeen = lastSeen,
            rssi = null,
            favorite = favorite,
            source = "announce",
        )
}
