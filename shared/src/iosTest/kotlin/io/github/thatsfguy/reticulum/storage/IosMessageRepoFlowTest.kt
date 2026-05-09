package io.github.thatsfguy.reticulum.storage

import io.github.thatsfguy.reticulum.platform.IosRepositories
import io.github.thatsfguy.reticulum.store.StoredMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

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

        // Capture the first list emission whose row has state="delivered".
        // runTest's TestScope uses a virtual time scheduler, but
        // SQLDelight's NativeSqliteDriver fires listeners on real
        // dispatcher threads — so we collect on Dispatchers.Default and
        // signal via CompletableDeferred when delivered shows up.
        val seenDelivered = CompletableDeferred<StoredMessage>()
        val collectJob: Job = launch(Dispatchers.Default) {
            repos.observeMessagesForContact(contact).collect { list ->
                val row = list.firstOrNull { it.id == msgId }
                if (row?.state == "delivered" && !seenDelivered.isCompleted) {
                    seenDelivered.complete(row)
                }
            }
        }

        // Give the listener a chance to register before the write.
        // Without this, on a fast machine the update can fire before
        // the channel listener is wired and the post-write notify is
        // received by no one. SQLDelight emits the current snapshot
        // on subscribe so we'd see "pending" first; the bug we're
        // chasing is whether the SECOND emission ever arrives.
        withContext(Dispatchers.Default) { delay(200) }

        repos.messages.updateState(
            id = msgId,
            state = "delivered",
            attempts = 1,
            lastAttempt = 2_000L,
        )

        val row = withContext(Dispatchers.Default) {
            withTimeout(5_000) { seenDelivered.await() }
        }
        assertEquals("delivered", row.state)
        assertEquals(msgId, row.id)

        collectJob.cancel()
    }
}
