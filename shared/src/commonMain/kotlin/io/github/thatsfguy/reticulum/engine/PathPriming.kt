package io.github.thatsfguy.reticulum.engine

/**
 * Default settle window between issuing a path request and acting on
 * it. Empirically 1.5s is enough for the local rnsd to ingest the
 * path? we just sent and refresh its forward-table entry for the
 * target before we attempt the actual send. Same value upstream
 * fetchNomadPage / propagation flows used independently before this
 * helper consolidated them.
 */
const val DEFAULT_PATH_SETTLE_MS: Long = 1500L

/**
 * Run [requestPath] for [destHash] then wait [settleMs] for the local
 * rnsd to refresh its forward path. Returns when the settle window has
 * elapsed, leaving the caller to do the actual send / link open / etc.
 * No block-style API because the three callers (sendMessage,
 * fetchNomadPage, syncPropagation) interleave state mutation around
 * the send and a closure form would force awkward variable hoisting.
 *
 * Why a helper at all: drift between the three sites — different
 * settle windows, missing failure handling, one variant forgetting to
 * await — was a real "sometimes works, sometimes doesn't" risk before
 * this consolidation.
 *
 * The lambdas are injected (not method references on the engine) so
 * tests can drive this directly with virtual time and recording
 * collaborators, without setting up a full engine + transport.
 */
suspend fun primePath(
    destHash: ByteArray,
    requestPath: suspend (ByteArray) -> Unit,
    delayMs: suspend (Long) -> Unit,
    settleMs: Long = DEFAULT_PATH_SETTLE_MS,
    onPathFailure: (Throwable) -> Unit = {},
) {
    runCatching { requestPath(destHash) }.onFailure(onPathFailure)
    delayMs(settleMs)
}

/**
 * Default ratchet rotation interval. Mirrors upstream RNS
 * `Destination.RATCHET_INTERVAL = 30 * 60` seconds (verified via
 * reticulum-specifications/tools/regen_identities.py against RNS 1.2.0).
 *
 * Rotating more aggressively (e.g. on every announce) creates a race
 * with peers who encrypt to a recent ratchet pub right before we
 * rotate it out — their DATA arrives at us encrypted to a key we no
 * longer hold, and decrypt silently fails.
 */
const val DEFAULT_RATCHET_INTERVAL_MS: Long = 30L * 60L * 1000L

/**
 * Pure policy: should we rotate the ratchet on this announce?
 *
 * Returns true when [lastRotationMs] is 0 (we've never rotated, e.g.
 * fresh install) OR enough wall-clock time has elapsed since the last
 * rotation. Else returns false — re-use the existing ratchet.
 *
 * Decoupling rotation from per-announce events lets path-request
 * responses (which can fire many times per minute when peers spam)
 * announce without churning the ratchet pub.
 */
fun shouldRotateRatchet(
    nowMs: Long,
    lastRotationMs: Long,
    intervalMs: Long = DEFAULT_RATCHET_INTERVAL_MS,
): Boolean = lastRotationMs == 0L || (nowMs - lastRotationMs) >= intervalMs

/**
 * Extract the target destination hash from a Reticulum
 * `rnstransport.path.request` DATA packet's payload.
 *
 * Wire layout: target_dest_hash(16) + random_tag(16) = 32 bytes for
 * a non-transport-enabled originator (which is what we are). Transport
 * instances append their own identity hash; we tolerate but don't
 * inspect that. Returns null if the payload is too short to be a
 * valid request.
 *
 * Used by inbound DATA routing in the engine: when a path-request
 * arrives whose target is our own destHash, we must immediately
 * re-announce so the requester learns our path. Without this, peers
 * sending us LXMF after a `path?` round-trip just time out — exactly
 * the symptom that surfaced from the BLE inbound debug session
 * 2026-05-03 (we received many 51B path-request DATA packets and
 * silently dropped them all because handleData filtered on
 * pkt.destHash != ourDest).
 */
fun parsePathRequestTarget(payload: ByteArray): ByteArray? =
    if (payload.size < 16) null else payload.copyOfRange(0, 16)

/**
 * Closure-style overload for callers (test code, future flows) that
 * prefer to keep the actual action visually grouped with the priming.
 * Behavior is identical to calling [primePath] then [block] in order.
 */
suspend fun withPathPrimed(
    destHash: ByteArray,
    requestPath: suspend (ByteArray) -> Unit,
    delayMs: suspend (Long) -> Unit,
    settleMs: Long = DEFAULT_PATH_SETTLE_MS,
    onPathFailure: (Throwable) -> Unit = {},
    block: suspend () -> Unit,
) {
    primePath(destHash, requestPath, delayMs, settleMs, onPathFailure)
    block()
}

/**
 * How long to wait for an LRPROOF / RESPONSE on a Reticulum Link, scaled by
 * the destination's announced hop count. The flat 45 s used pre-v0.1.47 was
 * tight for >2-hop destinations: ALAYA (4 hops) and Cryptid_Node (6 hops)
 * both timed out for us 2026-05-03 even though the path was known.
 *
 * Formula: `BASE + PER_HOP * hopCount`, capped at [MAX_LINK_TIMEOUT_MS].
 *  - 1 hop  →  30 s   (was 45 — directly attached, 30 is plenty)
 *  - 2 hops →  45 s
 *  - 3 hops →  60 s
 *  - 4 hops →  75 s
 *  - 5 hops →  90 s
 *  - 6 hops → 105 s
 *  - 7+     → 120 s   (capped)
 *
 * Caller passes the pre-normalised hopCount from `StoredDestination`
 * (which already includes the upstream `+1` for the originator's own
 * receive step — so `hopCount = wire_hops + 1`).
 */
fun proofTimeoutForHops(hopCount: Int): Long {
    val effective = hopCount.coerceAtLeast(1)
    val computed = LINK_TIMEOUT_BASE_MS + LINK_TIMEOUT_PER_HOP_MS * effective
    return computed.coerceAtMost(MAX_LINK_TIMEOUT_MS)
}

const val LINK_TIMEOUT_BASE_MS: Long = 15_000L
const val LINK_TIMEOUT_PER_HOP_MS: Long = 15_000L
const val MAX_LINK_TIMEOUT_MS: Long = 120_000L

/**
 * Pure helper used by [io.github.thatsfguy.reticulum.engine.ReticulumEngine.fetchNomadPage]
 * to give the user a more useful error than the generic "no LRPROOF within Ns".
 *
 * Three signals available without round-tripping further packets:
 *  1. `hopCount` — how many transport relays sit between us and the
 *     destination. Long paths fail more often even when the responder is
 *     up.
 *  2. `lastSeenMs` — when we last received an announce from this dest.
 *     Real upstream nodes re-announce every ~5–30 min; if we haven't
 *     heard from them in over an hour the announce is almost certainly
 *     stale and the responder is offline.
 *  3. `nowMs` — current wall clock so the caller can pin the diagnosis.
 *
 * Verified manually 2026-05-03 against `tools/test_nomadnet_client.py`:
 * Python RNS reference client fails identically when the responder is
 * offline (path established but LRPROOF never arrives), so this is the
 * best diagnosis possible without protocol-level NACKs that don't exist.
 */
fun classifyLinkFailure(hopCount: Int, lastSeenMs: Long, nowMs: Long): String {
    val ageMs = (nowMs - lastSeenMs).coerceAtLeast(0)
    return when {
        // Stale announce → responder almost certainly offline.
        ageMs > STALE_ANNOUNCE_THRESHOLD_MS ->
            "Node hasn't announced in ${ageMs / 60_000L}m — most likely offline. " +
                "The transport still caches its path, but the node itself isn't on the air."

        // Long path AND not stale → could be slow or refusing initiator
        // links. We can't tell those apart (RNS has no NACK packet).
        hopCount >= 4 ->
            "Node is $hopCount hops away — link may be too slow to establish. " +
                "Try Reload, or the node may be offline / refusing initiator-side links."

        // Short path, recent announce → responder accepted the path? but
        // dropped the LINKREQUEST or refuses links. Verified Python RNS
        // hits the same wall in this case.
        else ->
            "Transport knows the path but the node isn't responding to LINKREQUEST. " +
                "Likely offline or refusing initiator-side links."
    }
}

const val STALE_ANNOUNCE_THRESHOLD_MS: Long = 60L * 60L * 1000L  // 1 hour
