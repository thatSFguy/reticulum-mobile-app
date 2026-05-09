package io.github.thatsfguy.reticulum.storage

import io.github.thatsfguy.reticulum.platform.IosRepositories
import io.github.thatsfguy.reticulum.store.StoredMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the iOS-only "hourglass stays after delivery" symptom
 * a tester reported on ios-v1.0.4 (2026-05-09): the engine successfully
 * called [MessageRepository.updateState] with state="delivered" and
 * logged "msg #N: ✓ delivered via link", but the conversation view in
 * the iOS app kept rendering the ⏳ glyph mapped to state="pending".
 *
 * The two suspicious links in the chain are:
 *  1. SQLDelight's `observeMessagesForContact(...).asFlow().mapToList()`
 *     not re-emitting on UPDATE.
 *  2. SwiftUI not re-rendering even though the @Published list changed.
 *
 * This test pins #1: subscribe to the flow, save a message, update its
 * state, and assert the collector sees state="delivered" within a
 * reasonable window. If this fails, the SQLDelight notification path is
 * the bug — we'd need to either tickle the listener manually or pivot
 * to an event-driven re-fetch.
 */
class IosMessageRepoFlowTest {

    /**
     * Pins the iOS-only id-from-save bug separately from the flow
     * re-emission concern: NativeSqliteDriver's reader/writer connection
     * pool means SELECT last_insert_rowid() routes to a reader connection
     * that never saw our INSERT, so it returns 0/stale. save() must wrap
     * INSERT + lastInsertRowId in a transactionWithResult so both run on
     * the writer connection. Without this, the engine's
     * updateState(msgIdFromSave, ...) UPDATE has WHERE id=0 and matches
     * no row — message state stays at "pending" forever (hourglass bug
     * reported on ios-v1.0.4, 2026-05-09).
     */
    @Test fun save_returns_actual_inserted_row_id() = runTest {
        val name = "test_saveid_${Random.nextLong().toULong().toString(16)}.db"
        val repos = IosRepositories.create(name = name)
        val contact = "0123456789abcdef0123456789abcdef"

        val msgId = repos.messages.save(StoredMessage(
            contactHash = contact,
            direction = "outgoing",
            content = "hi",
            title = "",
            timestamp = 1_000L,
            state = "pending",
            attempts = 0,
            lastAttempt = 1_000L,
        ))

        // The same id from save() must round-trip through the read APIs.
        val byId = repos.messages.getById(msgId)
        assertEquals(msgId, byId?.id, "getById($msgId) should return that exact row")

        val forContact = repos.messages.getForContact(contact)
        assertEquals(1, forContact.size)
        assertEquals(msgId, forContact[0].id, "row in getForContact must have the id save() returned")
    }

    @Test fun observeMessagesForContact_emits_after_state_update() = runTest {
        // Use a fresh on-disk SQLite name per test run; NativeSqliteDriver
        // doesn't expose an "in-memory" toggle through the common API.
        val name = "test_observe_${Random.nextLong().toULong().toString(16)}.db"
        val repos = IosRepositories.create(name = name)
        val contact = "deadbeefdeadbeefdeadbeefdeadbeef"
        val msgId = repos.messages.save(StoredMessage(
            contactHash = contact,
            direction = "outgoing",
            content = "hi",
            title = "",
            timestamp = 1_000L,
            state = "pending",
            attempts = 0,
            lastAttempt = 1_000L,
        ))

        // Capture every emission with its state so a failed test surfaces
        // WHAT we did see, not just "we didn't see delivered". println
        // lands in the iOS sim test stdout which Gradle's test report
        // captures.
        val emissions = mutableListOf<String?>()
        val seenDelivered = CompletableDeferred<StoredMessage>()
        val collectJob: Job = launch(Dispatchers.Default) {
            println("[test] subscribing to observeMessagesForContact")
            repos.observeMessagesForContact(contact).collect { list ->
                val row = list.firstOrNull { it.id == msgId }
                emissions += row?.state
                println("[test] emission #${emissions.size}: state=${row?.state} (size=${list.size})")
                if (row?.state == "delivered" && !seenDelivered.isCompleted) {
                    seenDelivered.complete(row)
                }
            }
        }

        // Give the subscriber time to register before we fire the update.
        withContext(Dispatchers.Default) { delay(500) }
        println("[test] firing updateState(state=delivered)")

        repos.messages.updateState(
            id = msgId,
            state = "delivered",
            attempts = 1,
            lastAttempt = 2_000L,
        )

        try {
            val row = withContext(Dispatchers.Default) {
                withTimeout(5_000) { seenDelivered.await() }
            }
            assertEquals("delivered", row.state)
            assertEquals(msgId, row.id)
        } finally {
            collectJob.cancel()
            println("[test] emissions captured: $emissions")
            assertTrue(
                emissions.contains("delivered"),
                "expected at least one emission with state=delivered, got: $emissions"
            )
        }
    }
}
