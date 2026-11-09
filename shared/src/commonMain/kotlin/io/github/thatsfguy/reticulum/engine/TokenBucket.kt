package io.github.thatsfguy.reticulum.engine

/** Link requests accepted in a burst on one transport. */
const val LINK_REQUEST_BURST: Int = 10

/** One more link request is allowed per this interval, so 10 a minute
 *  sustained. Far above what a phone receives from real peers. */
const val LINK_REQUEST_REFILL_MS: Long = 6_000L

/** Allows [capacity] events at once, refilling one per [refillMs]. */
internal class TokenBucket(
    private val capacity: Int,
    private val refillMs: Long,
    startMs: Long,
) {
    private var tokens = capacity
    private var lastRefillMs = startMs

    fun tryAcquire(nowMs: Long): Boolean {
        val earned = ((nowMs - lastRefillMs) / refillMs).coerceAtLeast(0)
        if (earned > 0) {
            tokens = minOf(capacity.toLong(), tokens + earned).toInt()
            lastRefillMs += earned * refillMs
        }
        if (tokens == 0) return false
        tokens--
        return true
    }
}
