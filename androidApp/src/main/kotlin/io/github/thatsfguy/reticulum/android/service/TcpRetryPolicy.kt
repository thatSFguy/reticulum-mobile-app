package io.github.thatsfguy.reticulum.android.service

/**
 * Backoff policy for the TCP supervisor's reconnect loop, extracted as a
 * pure function so the ramp can be asserted without standing up a
 * service, a socket or an engine.
 *
 * Two ramps, per upstream RNS guidance:
 *  - **read failure** — a usable attachment existed and then its read
 *    loop died (NAT idle, middlebox timeout, server restart, parser
 *    issue). Floor 5s, ceiling 60s: the node wants us, something
 *    transient broke, come back quickly.
 *  - **connect failure** — we never got a usable attachment (DNS,
 *    refused, ECONNABORTED, *or a server that accepts and immediately
 *    closes*). Floor 15s, ceiling 5min: the node is not serving us, so
 *    knock gently.
 *
 * The distinction that matters is **usable**, not **established**. A
 * node that has denied our IP still completes the TCP handshake and only
 * then closes — measured against a real denylisted address, the socket
 * was accepted in 62ms and shut in under one. Classifying purely on "did
 * we reach Connected" therefore filed a refusal as a read failure and
 * gave it the fast ramp, so a client settled at the 60s ceiling and
 * knocked ~1,440 times a day on a door that was closed to it — forever,
 * from every install behind that address. That is a plausible way to
 * *earn* a listing and a certain way to keep one. On the connect ramp
 * the same client settles at ~288 attempts a day.
 *
 * [USABLE_AFTER_MS] is the line between the two. It sits far above any
 * refusal (sub-second by construction — the peer closes as soon as it
 * has decided) and far below any real session, so a misclassification in
 * either direction costs one backoff step and nothing else.
 */
internal object TcpRetryPolicy {

    /** How long an attachment must survive to count as having been usable. */
    const val USABLE_AFTER_MS = 3_000L

    /** Read-failure ramp: floor, and the ceiling it doubles toward. */
    const val READ_FAIL_FLOOR_MS = 5_000L
    const val READ_FAIL_CEILING_MS = 60_000L

    /** Connect-failure ramp: floor, and the ceiling it doubles toward. */
    const val CONNECT_FAIL_FLOOR_MS = 15_000L
    const val CONNECT_FAIL_CEILING_MS = 300_000L

    /**
     * An attachment that lived at least this long is treated as healthy
     * rather than flapping: its death is almost always a NAT/middlebox
     * idle timeout, so the read ramp restarts from the floor instead of
     * carrying over whatever it had climbed to.
     */
    const val HEALTHY_AFTER_MS = 60_000L

    /**
     * What to do after one failed attempt.
     *
     * @param survivedMs how long the attachment lasted, or null if it
     *   never reached Connected at all.
     */
    fun decide(
        survivedMs: Long?,
        readFailBackoffMs: Long,
        connectFailBackoffMs: Long,
    ): Decision {
        val wasReadFailure = survivedMs != null && survivedMs >= USABLE_AFTER_MS
        if (!wasReadFailure) {
            // Never usable. Note what is deliberately NOT done here: the
            // connect ramp is not reset. It used to be reset the moment a
            // socket was established, which meant a server that accepted
            // and closed us reset the ramp on every single attempt and
            // pinned it at its 15s floor — worse than the ceiling it was
            // meant to climb to.
            return Decision(
                wasReadFailure = false,
                delayBaseMs = connectFailBackoffMs,
                nextReadFailBackoffMs = readFailBackoffMs,
                nextConnectFailBackoffMs =
                    (connectFailBackoffMs * 2).coerceAtMost(CONNECT_FAIL_CEILING_MS),
            )
        }

        val base =
            if (survivedMs >= HEALTHY_AFTER_MS) READ_FAIL_FLOOR_MS else readFailBackoffMs
        return Decision(
            wasReadFailure = true,
            delayBaseMs = base,
            nextReadFailBackoffMs = (base * 2).coerceAtMost(READ_FAIL_CEILING_MS),
            // A usable attachment proves the connect path works, so the
            // connect ramp is earned back here — where it means something
            // — rather than on a bare socket.
            nextConnectFailBackoffMs = CONNECT_FAIL_FLOOR_MS,
        )
    }

    data class Decision(
        val wasReadFailure: Boolean,
        /** Backoff to wait, before jitter. */
        val delayBaseMs: Long,
        val nextReadFailBackoffMs: Long,
        val nextConnectFailBackoffMs: Long,
    )
}
