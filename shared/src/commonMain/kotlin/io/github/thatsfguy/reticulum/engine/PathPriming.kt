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
