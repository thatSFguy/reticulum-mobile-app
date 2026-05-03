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
