package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenBucketTest {
    @Test fun allowsABurstUpToCapacity() {
        val bucket = TokenBucket(3, 1_000L, 0L)
        assertEquals(3, (1..5).count { bucket.tryAcquire(0L) })
    }

    @Test fun refillsOnePerInterval() {
        val bucket = TokenBucket(2, 1_000L, 0L)
        repeat(2) { bucket.tryAcquire(0L) }
        assertFalse(bucket.tryAcquire(999L))
        assertTrue(bucket.tryAcquire(1_000L))
        assertFalse(bucket.tryAcquire(1_000L))
    }

    @Test fun neverExceedsCapacityAfterALongIdle() {
        val bucket = TokenBucket(2, 1_000L, 0L)
        assertEquals(2, (1..5).count { bucket.tryAcquire(1_000_000L) })
    }

    @Test fun aClockGoingBackwardsGrantsNothing() {
        val bucket = TokenBucket(1, 1_000L, 10_000L)
        assertTrue(bucket.tryAcquire(10_000L))
        assertFalse(bucket.tryAcquire(5_000L))
    }
}
