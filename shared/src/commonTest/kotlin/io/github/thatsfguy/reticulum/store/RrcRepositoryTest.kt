package io.github.thatsfguy.reticulum.store

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract test for [RrcRepository]. Exercises [InMemoryRrcRepository],
 * which is the behavioural reference the Room (Android) and SQLDelight
 * (iOS) implementations must match.
 *
 * camelCase test names keep the iosTest Kotlin/Native compile happy —
 * K/N rejects `,` `(` `)` in backticked identifiers.
 */
class RrcRepositoryTest {

    private fun hub(hash: String, name: String = "Hub $hash") =
        StoredRrcHub(destHash = hash, displayName = name, addedAt = 1_000L)

    private fun room(hubHash: String, name: String) =
        StoredRrcRoom(hubHash = hubHash, name = name)

    private fun msg(
        hubHash: String,
        room: String,
        text: String,
        timestamp: Long,
        msgId: String? = null,
        direction: String = "incoming",
    ) = StoredRrcMessage(
        hubHash = hubHash,
        room = room,
        direction = direction,
        senderIdHash = "ab".repeat(16),
        nick = "alice",
        text = text,
        timestamp = timestamp,
        msgId = msgId,
    )

    // ---- hubs -------------------------------------------------------------

    @Test
    fun upsertHubThenGetReturnsIt() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertHub(hub("aa"))
        assertEquals("Hub aa", repo.getHub("aa")?.displayName)
        assertNull(repo.getHub("unknown"))
    }

    @Test
    fun upsertHubReplacesExisting() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertHub(hub("aa", "First"))
        repo.upsertHub(hub("aa", "Second"))
        assertEquals(1, repo.getAllHubs().size)
        assertEquals("Second", repo.getHub("aa")?.displayName)
    }

    @Test
    fun getAllHubsReturnsEveryHub() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertHub(hub("aa"))
        repo.upsertHub(hub("bb"))
        assertEquals(setOf("aa", "bb"), repo.getAllHubs().map { it.destHash }.toSet())
    }

    @Test
    fun setHubLastConnectedStampsRow() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertHub(hub("aa"))
        repo.setHubLastConnected("aa", 9_999L)
        assertEquals(9_999L, repo.getHub("aa")?.lastConnectedAt)
    }

    @Test
    fun setHubLastConnectedUnknownIsNoOp() = runTest {
        val repo = InMemoryRrcRepository()
        repo.setHubLastConnected("ghost", 9_999L)
        assertNull(repo.getHub("ghost"))
    }

    @Test
    fun deleteHubCascadesRoomsAndMessages() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertHub(hub("aa"))
        repo.upsertRoom(room("aa", "#general"))
        repo.saveMessage(msg("aa", "#general", "hi", 100L))
        repo.deleteHub("aa")
        assertNull(repo.getHub("aa"))
        assertTrue(repo.getRoomsForHub("aa").isEmpty())
        assertTrue(repo.getMessages("aa", "#general").isEmpty())
    }

    // ---- rooms ------------------------------------------------------------

    @Test
    fun upsertRoomThenGetForHub() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertRoom(room("aa", "#general"))
        repo.upsertRoom(room("aa", "#random"))
        assertEquals(
            setOf("#general", "#random"),
            repo.getRoomsForHub("aa").map { it.name }.toSet(),
        )
    }

    @Test
    fun upsertRoomReplacesOnCompositeKey() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertRoom(room("aa", "#general").copy(joined = false))
        repo.upsertRoom(room("aa", "#general").copy(joined = true))
        val rooms = repo.getRoomsForHub("aa")
        assertEquals(1, rooms.size)
        assertTrue(rooms.single().joined)
    }

    @Test
    fun roomsAreScopedPerHub() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertRoom(room("aa", "#general"))
        repo.upsertRoom(room("bb", "#general"))
        assertEquals(1, repo.getRoomsForHub("aa").size)
        assertEquals(1, repo.getRoomsForHub("bb").size)
    }

    @Test
    fun setRoomJoinedFlipsTheFlag() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertRoom(room("aa", "#general"))
        repo.setRoomJoined("aa", "#general", true)
        assertTrue(repo.getRoomsForHub("aa").single().joined)
        repo.setRoomJoined("aa", "#general", false)
        assertFalse(repo.getRoomsForHub("aa").single().joined)
    }

    @Test
    fun touchRoomAdvancesActivityForwardOnly() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertRoom(room("aa", "#general").copy(lastActivityAt = 500L))
        repo.touchRoom("aa", "#general", 1_000L)
        assertEquals(1_000L, repo.getRoomsForHub("aa").single().lastActivityAt)
        // An older timestamp must not roll the clock back.
        repo.touchRoom("aa", "#general", 200L)
        assertEquals(1_000L, repo.getRoomsForHub("aa").single().lastActivityAt)
    }

    @Test
    fun deleteRoomRemovesRoomAndItsMessages() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertRoom(room("aa", "#general"))
        repo.upsertRoom(room("aa", "#random"))
        repo.saveMessage(msg("aa", "#general", "hi", 100L))
        repo.saveMessage(msg("aa", "#random", "yo", 100L))
        repo.deleteRoom("aa", "#general")
        assertEquals(setOf("#random"), repo.getRoomsForHub("aa").map { it.name }.toSet())
        assertTrue(repo.getMessages("aa", "#general").isEmpty())
        assertEquals(1, repo.getMessages("aa", "#random").size)
    }

    // ---- messages ---------------------------------------------------------

    @Test
    fun saveMessageAssignsIncrementingIds() = runTest {
        val repo = InMemoryRrcRepository()
        val id1 = repo.saveMessage(msg("aa", "#general", "one", 100L))
        val id2 = repo.saveMessage(msg("aa", "#general", "two", 200L))
        assertTrue(id2 > id1)
    }

    @Test
    fun getMessagesReturnsArrivalOrderNotSenderClock() = runTest {
        val repo = InMemoryRrcRepository()
        // Messages arrive in this order off the hub's single ordered
        // fan-out stream. Each carries its *sender's* own K_TS — and
        // senders' clocks disagree (skew, or a clockless LoRa peer
        // stamping seconds-since-boot). The hub never re-stamps K_TS
        // on fan-out, so timestamp order != arrival order.
        repo.saveMessage(msg("aa", "#general", "first", timestamp = 5_000L))
        repo.saveMessage(msg("aa", "#general", "second", timestamp = 1_000L))
        repo.saveMessage(msg("aa", "#general", "third", timestamp = 9_000L))
        // History must read back in arrival (row-id) order. Sorting by
        // the senders' clocks would scramble it to second/first/third.
        assertEquals(
            listOf("first", "second", "third"),
            repo.getMessages("aa", "#general").map { it.text },
        )
    }

    @Test
    fun getMessagesIsScopedPerRoomAndHub() = runTest {
        val repo = InMemoryRrcRepository()
        repo.saveMessage(msg("aa", "#general", "a", 100L))
        repo.saveMessage(msg("aa", "#random", "b", 100L))
        repo.saveMessage(msg("bb", "#general", "c", 100L))
        assertEquals(listOf("a"), repo.getMessages("aa", "#general").map { it.text })
        assertEquals(listOf("b"), repo.getMessages("aa", "#random").map { it.text })
        assertEquals(listOf("c"), repo.getMessages("bb", "#general").map { it.text })
    }

    @Test
    fun hasMessageIdDetectsDuplicate() = runTest {
        val repo = InMemoryRrcRepository()
        assertFalse(repo.hasMessageId("aa", "deadbeef"))
        repo.saveMessage(msg("aa", "#general", "hi", 100L, msgId = "deadbeef"))
        assertTrue(repo.hasMessageId("aa", "deadbeef"))
    }

    @Test
    fun hasMessageIdIsScopedPerHub() = runTest {
        val repo = InMemoryRrcRepository()
        repo.saveMessage(msg("aa", "#general", "hi", 100L, msgId = "deadbeef"))
        // Same msgId on a different hub is not a duplicate.
        assertFalse(repo.hasMessageId("bb", "deadbeef"))
    }

    @Test
    fun deleteMessagesForRoomClearsHistory() = runTest {
        val repo = InMemoryRrcRepository()
        repo.saveMessage(msg("aa", "#general", "hi", 100L))
        repo.saveMessage(msg("aa", "#general", "bye", 200L))
        repo.deleteMessagesForRoom("aa", "#general")
        assertTrue(repo.getMessages("aa", "#general").isEmpty())
    }
}

/**
 * In-memory [RrcRepository] — the behavioural reference for the Room
 * and SQLDelight implementations, and a fixture for engine-level tests
 * that need RRC persistence without a real database.
 */
internal class InMemoryRrcRepository : RrcRepository {
    private val hubs = mutableMapOf<String, StoredRrcHub>()
    private val rooms = mutableMapOf<Pair<String, String>, StoredRrcRoom>()
    private val messages = mutableMapOf<Long, StoredRrcMessage>()
    private var nextId = 1L

    override suspend fun upsertHub(hub: StoredRrcHub) {
        hubs[hub.destHash] = hub
    }

    override suspend fun getHub(destHash: String): StoredRrcHub? = hubs[destHash]

    override suspend fun getAllHubs(): List<StoredRrcHub> = hubs.values.toList()

    override suspend fun setHubLastConnected(destHash: String, whenMs: Long) {
        hubs[destHash]?.let { hubs[destHash] = it.copy(lastConnectedAt = whenMs) }
    }

    override suspend fun deleteHub(destHash: String) {
        hubs.remove(destHash)
        rooms.keys.filter { it.first == destHash }.forEach { rooms.remove(it) }
        messages.entries.removeAll { it.value.hubHash == destHash }
    }

    override suspend fun upsertRoom(room: StoredRrcRoom) {
        rooms[room.hubHash to room.name] = room
    }

    override suspend fun getRoomsForHub(hubHash: String): List<StoredRrcRoom> =
        rooms.values.filter { it.hubHash == hubHash }

    override suspend fun setRoomJoined(hubHash: String, name: String, joined: Boolean) {
        val key = hubHash to name
        rooms[key]?.let { rooms[key] = it.copy(joined = joined) }
    }

    override suspend fun touchRoom(hubHash: String, name: String, activityMs: Long) {
        val key = hubHash to name
        rooms[key]?.let {
            if (activityMs > it.lastActivityAt) {
                rooms[key] = it.copy(lastActivityAt = activityMs)
            }
        }
    }

    override suspend fun deleteRoom(hubHash: String, name: String) {
        rooms.remove(hubHash to name)
        messages.entries.removeAll { it.value.hubHash == hubHash && it.value.room == name }
    }

    override suspend fun saveMessage(message: StoredRrcMessage): Long {
        val id = nextId++
        messages[id] = message.copy(id = id)
        return id
    }

    override suspend fun getMessages(hubHash: String, room: String): List<StoredRrcMessage> =
        messages.values
            .filter { it.hubHash == hubHash && it.room == room }
            // Arrival (row-id) order — NOT timestamp. The hub forwards
            // each sender's own K_TS unchanged, so timestamps come from
            // mutually-skewed clocks and cannot order a multi-party room.
            .sortedBy { it.id }

    override suspend fun hasMessageId(hubHash: String, msgId: String): Boolean =
        messages.values.any { it.hubHash == hubHash && it.msgId == msgId }

    override suspend fun deleteMessagesForRoom(hubHash: String, room: String) {
        messages.entries.removeAll { it.value.hubHash == hubHash && it.value.room == room }
    }
}
