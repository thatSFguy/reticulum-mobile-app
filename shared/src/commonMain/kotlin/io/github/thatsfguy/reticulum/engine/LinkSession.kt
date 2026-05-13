package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.LinkState
import io.github.thatsfguy.reticulum.link.computePacketFullHash
import io.github.thatsfguy.reticulum.protocol.parsePacket
import io.github.thatsfguy.reticulum.protocol.CTX_LRPROOF
import io.github.thatsfguy.reticulum.protocol.CTX_LRRTT
import io.github.thatsfguy.reticulum.protocol.CTX_REQUEST
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_ADV
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_PRF
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_REQ
import io.github.thatsfguy.reticulum.protocol.CTX_RESPONSE
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.Packet
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.resource.Resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.math.max
import kotlin.math.min

/**
 * Common surface used by the engine pump to dispatch incoming packets to
 * either the initiator-side ([LinkSession]) or responder-side
 * ([io.github.thatsfguy.reticulum.engine.ResponderLinkSession]) driver
 * for an active link, keyed in the engine by link_id hex.
 */
interface LinkPump {
    /** Inbound packet plus the link-layer rssi sidecar from the
     *  transport that delivered it (BLE / BT Classic populate it from
     *  the RNode's CMD_STAT_RSSI frame; TCP is null). The responder
     *  session uses both [Packet.hops] and rssi to attach link-quality
     *  metadata to each saved incoming LXMF message. */
    suspend fun handlePacket(pkt: Packet, rssi: Int? = null)

    /** Release any resources held by the session — e.g. cancel the
     *  initiator-side KEEPALIVE loop (§6.7). Idempotent. Default is
     *  no-op so existing implementations don't break, but the engine
     *  should call this at every removal site to guarantee resource
     *  cleanup even on paths where parent-scope cancellation hasn't
     *  fired yet (e.g. `activeSessions.clear()` on identity reset). */
    fun dispose() {}
}

/**
 * Initiator-side driver for a single Reticulum Link, plus the simple
 * REQUEST/RESPONSE flow NomadNet uses to serve micron pages.
 *
 * The handshake bytes (LINKREQUEST → LRPROOF → LRRTT) are already covered
 * by the [Link] class. This wraps that with:
 *   - A coroutine-friendly `awaitProof()` that resolves when the engine
 *     pump routes an inbound LRPROOF packet to [handlePacket].
 *   - A `request(pathHash, body)` that wraps the request payload as
 *     msgpack `[timestamp, path_hash(32), params]`, Token-encrypts with
 *     the link's derived key, sends as a PACKET_DATA with context
 *     CTX_REQUEST addressed to link_id, and awaits the matching
 *     CTX_RESPONSE packet.
 *
 * The wire format is taken from microReticulum's Link.cpp + Type.h:
 * REQUEST/RESPONSE are msgpack-wrapped within encrypted Token payloads
 * on an established Link. Pages that fit inside one packet's encrypted
 * MDU (~383 bytes plaintext) round-trip fine; larger pages would need
 * Reticulum Resource fragmentation, which microReticulum stubs out and
 * we don't yet implement either.
 */
class LinkSession internal constructor(
    val link: Link,
    private val crypto: CryptoProvider,
    private val sender: suspend (ByteArray) -> Unit,
    private val nowMs: () -> Long,
    private val logger: (String) -> Unit = {},
) : LinkPump {
    private val tokenCrypto = TokenCrypto(crypto)

    private var proofDeferred: CompletableDeferred<ProofResult>? = null
    private var responseDeferred: CompletableDeferred<ByteArray>? = null

    /** Outstanding per-packet proof awaiters keyed by the **hex of the
     *  32-byte full packet hash** of an outbound CTX_NONE link DATA we
     *  sent. The responder's PROOF (PACKET_PROOF / DEST_LINK / CTX_NONE,
     *  payload[:32] = full_hash) wakes the matching deferred. Used by
     *  link-delivered LXMF send to confirm delivery (the link analogue
     *  of the opportunistic dest_hash-as-truncated-hash PROOF lookup
     *  the engine pump does for non-link DATA). */
    private val pendingDataProofs = mutableMapOf<String, CompletableDeferred<Boolean>>()

    /** Deferred resolved when the receiver's CTX_RESOURCE_PRF for the
     *  current outbound Resource arrives, or on timeout. The advertised
     *  integrity hash (`adv.h`) is held alongside so the handler can
     *  reject PRFs whose payload[:32] doesn't match — defense against a
     *  PRF leaked from a prior in-flight Resource on the same link.
     *  Only one outbound Resource per session is in flight at a time
     *  for MVP. */
    private var resourcePrfDeferred: CompletableDeferred<Boolean>? = null
    private var pendingResourceHash: ByteArray? = null

    /** Cached chunks for the in-flight outbound Resource, indexed by
     *  the hex of their 4-byte hashmap entry. Used by
     *  [handleResourceReq] (§10.5 pull-style) to look up which payload
     *  bytes to resend when the receiver asks for specific parts.
     *
     *  Cleared in the same `finally` block as [resourcePrfDeferred] /
     *  [pendingResourceHash] so a Resource lifecycle never leaks state
     *  past timeout / PRF / throw. */
    private var pendingResourceChunks: Map<String, ByteArray> = emptyMap()

    /** Initiator-side KEEPALIVE loop job. Launched by [startKeepalive]
     *  once the link goes ACTIVE; cancelled by [stopKeepalive] (and
     *  implicitly by the parent scope when the engine detaches). Per
     *  SPEC §6.7.1 only the initiator originates KEEPALIVE pings —
     *  responder echoes the pong, which is already handled by
     *  [ResponderLinkSession.handleKeepAlive]. */
    private var keepaliveJob: Job? = null

    /** Inbound-Resource state machine — extracted 2026-05-10 from inline
     *  state+handlers so [ResponderLinkSession] can share it (the
     *  responder used to silently drop every Resource on a peer-initiated
     *  link, breaking the standard LXMF reply pattern of opening a new
     *  outbound link to the client). The lambdas wire this initiator-side
     *  consumer's "complete responseDeferred with the [request_id,
     *  response_data] envelope" semantics. */
    private val resourceReceiver: LinkResourceReceiver = LinkResourceReceiver(
        link = link,
        tokenCrypto = tokenCrypto,
        crypto = crypto,
        sender = sender,
        logger = logger,
        onAssembled = { plain -> deliverAssembledResourceAsResponse(plain) },
        onAdvParseFailure = { responseDeferred?.complete(ByteArray(0)) },
    )

    /** 16-byte request_id of the in-flight outbound REQUEST, per spec §11.1
     *  (`SHA-256(packed_request)[:16]`). Set when [request] sends, cleared
     *  when the matching RESPONSE resolves the deferred (or on timeout).
     *  Inbound RESPONSE packets whose msgpack envelope element [0] doesn't
     *  match this id are dropped with a log line — without that check a
     *  misbehaving / colluding transit relay could feed us a stale RESPONSE
     *  from a prior request and we'd accept it as the answer to whatever's
     *  pending now (confused-deputy). Latent today — only one in-flight
     *  request per session — but a real footgun the moment we add link
     *  reuse / partial async sub-fetches. SPEC.md:2120 makes the same
     *  point about Link.handle_response in upstream RNS. */
    private var expectedRequestId: ByteArray? = null

    // ---- Diagnostics (v0.1.49) -----------------------------------------
    // Track inbound activity so a timeout can tell the user WHAT happened
    // vs. just "no packet". The histogram + first/last lets us distinguish:
    //   - silence on link_id     → relay or responder offline
    //   - LRPROOF only, no LRRTT → responder accepted but didn't transition
    //   - resource started, didn't complete → mid-stream drop (TCP bounce)
    private val rxByContext = mutableMapOf<Int, Int>()
    private var firstRxAt: Long = -1L
    private var lastRxAt: Long = -1L
    // Resource-progress counters now live on [resourceReceiver]; the
    // diagnosticSummary reader below reads them through that.

    /**
     * One-line summary of what happened on this link_id during the wait.
     * Empty string if no traffic at all (so the caller can short-circuit
     * with a tighter "complete silence" message).
     */
    fun diagnosticSummary(): String {
        if (rxByContext.isEmpty()) return ""
        val now = nowMs()
        val firstAge = if (firstRxAt > 0) (now - firstRxAt).coerceAtLeast(0) else 0L
        val lastAge = if (lastRxAt > 0) (now - lastRxAt).coerceAtLeast(0) else 0L
        val ctxParts = rxByContext.entries
            .sortedBy { it.key }
            .joinToString(", ") { (ctx, n) ->
                val name = ctxName(ctx)
                "${name ?: "ctx=0x${ctx.toString(16).padStart(2, '0')}"}×$n"
            }
        val resourceNote = if (resourceReceiver.advParts > 0) {
            ", resource: ${resourceReceiver.chunksReceived}/${resourceReceiver.advParts} parts (${resourceReceiver.advBytes}B advertised)"
        } else ""
        return "rx [$ctxParts]; first ${firstAge / 1000}s ago, last ${lastAge / 1000}s ago$resourceNote"
    }

    private fun handleDataProof(pkt: Packet) {
        // Spec §6.5.1 explicit form: 32-byte packet hash || 64-byte
        // signature, where the signature is Ed25519(packet_hash) under
        // the responder's long-term signing key. RNS.Link.prove_packet
        // (RNS/Link.py:383-394) and our own ResponderLinkSession.send-
        // PacketProof both emit this 96-byte form for link DATA proofs;
        // the implicit (64-byte signature-only) form is for
        // opportunistic DATA on a separate path, not for us here.
        //
        // Without the signature check, ANY on-path observer of the
        // outbound DATA packet (transit relay, RF/BLE/TCP eavesdropper,
        // a malicious public rnsd from the Settings list) could
        // compute the matching hash from wire bytes and forge a PROOF
        // — letting them mark a message "delivered" while silently
        // dropping the original DATA. Security review 2026-05-07 found
        // this vector before any reported exploit; the fix is to
        // verify the signature before accepting.
        //
        // Forged proofs are dropped silently rather than completing
        // the awaiter to false, so the legitimate proof (if it ever
        // arrives) can still resolve the deferred. The 30s
        // sendDataAndAwaitProof timeout handles the "neither real nor
        // forged proof arrives" case.
        if (pkt.payload.size != 96) {
            logger("link DATA proof wrong size (${pkt.payload.size}B, need 96 — spec §6.5.1)")
            return
        }
        val peerSigPub = link.peerLongTermSigPub
        if (peerSigPub == null) {
            logger("link DATA proof rx but peerLongTermSigPub missing — dropping")
            return
        }
        val fullHash = pkt.payload.copyOfRange(0, 32)
        val signature = pkt.payload.copyOfRange(32, 96)
        if (!crypto.ed25519Verify(signature, fullHash, peerSigPub)) {
            logger("link DATA proof sig verify failed — dropping (forged or replay)")
            return
        }
        val hex = fullHash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val d = pendingDataProofs.remove(hex)
        if (d != null) {
            d.complete(true)
            logger("✓ link DATA proof for ${hex.take(8)}…")
        } else {
            logger("link DATA proof for ${hex.take(8)}… (no awaiter)")
        }
    }

    private fun ctxName(ctx: Int): String? = when (ctx) {
        CTX_LRPROOF -> "LRPROOF"
        CTX_LRRTT -> "LRRTT"
        CTX_REQUEST -> "REQUEST"
        CTX_RESPONSE -> "RESPONSE"
        CTX_RESOURCE_ADV -> "RESOURCE_ADV"
        CTX_RESOURCE -> "RESOURCE"
        CTX_RESOURCE_PRF -> "RESOURCE_PRF"
        else -> null
    }

    /**
     * Outcome of the initiator-side LRPROOF wait. Distinct from a plain
     * Boolean so callers can tell apart "no packet ever arrived" (Timeout)
     * vs. "a packet arrived but the signature/format was wrong" (Invalid).
     */
    sealed class ProofResult {
        object Timeout : ProofResult()
        data class Validated(val rttSeconds: Double) : ProofResult()
        data class Invalid(val reason: String) : ProofResult()
    }

    /** Suspend until LRPROOF lands on this link or the timeout fires. */
    suspend fun awaitProof(timeoutMs: Long): ProofResult {
        val d = CompletableDeferred<ProofResult>().also { proofDeferred = it }
        return try {
            withTimeout(timeoutMs) { d.await() }
        } catch (_: TimeoutCancellationException) {
            ProofResult.Timeout
        } finally {
            proofDeferred = null
        }
    }

    /**
     * Send a REQUEST packet for [pathHash] and suspend until the matching
     * RESPONSE arrives or [timeoutMs] elapses.
     *
     * Per spec §11.1, [pathHash] must be the **16-byte** truncation of
     * SHA-256 over the path string (e.g. `SHA256("/page/index.mu")[:16]`).
     * Servers key their `request_handlers` dict on this 16-byte hash; a
     * 32-byte hash never matches, and a too-short hash collides too easily.
     *
     * [data] is the request payload as the application sees it — passed
     * directly into envelope element `[2]` and msgpack-encoded ONCE as
     * part of the outer list. Pre-v0.1.53 this was `body: ByteArray`
     * which forced callers to msgpack-encode dicts/lists themselves and
     * pass the bytes; the engine then re-wrapped those bytes as a
     * msgpack `bin`, so server-side handlers (NomadNet `Node.py:109`
     * does `isinstance(data, dict)`, LXMRouter does `isinstance(data, list)`)
     * silently fell through and every form submission / propagation poll
     * lost its payload. Accepted types: `null` (msgpack nil — typical
     * GET), `Map<*,*>` (form posts), `List<*>` (propagation rounds),
     * `ByteArray` (raw application blob), plus any scalar the encoder
     * supports. See `MessagePack.write` for the full list.
     *
     * Returns the raw response body bytes (typically UTF-8 micron text)
     * or null on timeout.
     */
    suspend fun request(
        pathHash: ByteArray,
        data: Any? = null,
        timeoutMs: Long,
    ): ByteArray? {
        check(link.state == LinkState.ACTIVE) { "Link not active (state=${link.state})" }
        require(pathHash.size == 16) {
            "pathHash must be 16 bytes per spec §11.1 (SHA-256(path)[:16]), got ${pathHash.size}"
        }

        val plaintext = MessagePack.encode(listOf(nowMs() / 1000.0, pathHash, data))
        val ciphertext = tokenCrypto.encryptWithDerivedKey(plaintext, link.derivedKey!!)
        // Spec §12.5.2: packets addressed to a link_id MUST set
        // dest_type = LINK so a transit relay's link_table lookup fires.
        // dest_type = SINGLE makes the relay try path_table (which has
        // no entry for the link_id) and silently drop the packet.
        val packet = buildPacket(
            destType = DEST_LINK,
            packetType = PACKET_DATA,
            destHash = link.linkId!!,
            context = CTX_REQUEST,
            payload = ciphertext,
        )

        // Spec §11.2 + upstream RNS Link.handle_request:1286 —
        // server-side `request_id = packet.getTruncatedHash()`, where
        // `getTruncatedHash` hashes the packet's hashable_part:
        //   (flags & 0x0F) || raw[2:]  (HEADER_1)
        //   (flags & 0x0F) || raw[18:] (HEADER_2)
        // truncated to 16 bytes. NOT a hash of the inner plaintext
        // (which is what v0.1.54 incorrectly computed — every page
        // load silently failed because the RESPONSE's request_id never
        // matched). Compute the same hash on our outbound packet so
        // the RESPONSE handler can match.
        val parsedSelf = parsePacket(packet) ?: error("self-parse failed building REQUEST")
        expectedRequestId = computePacketFullHash(parsedSelf, crypto).copyOfRange(0, 16)

        val d = CompletableDeferred<ByteArray>().also { responseDeferred = it }
        sender(packet)
        return try {
            withTimeout(timeoutMs) { d.await() }
        } catch (_: TimeoutCancellationException) {
            null
        } finally {
            responseDeferred = null
            expectedRequestId = null
        }
    }

    /**
     * Send a single-segment Resource (§10.2) carrying [plain] over this
     * link and suspend until the receiver answers with CTX_RESOURCE_PRF
     * or [timeoutMs] elapses.
     *
     * Used by the engine to deliver LXMF messages whose body exceeds a
     * single packet's MDU — typically image attachments via
     * `packLinkMessage(..., fields = mapOf(6 to imageBytes))`. The wire
     * format matches Sideband + Columba and Python RNS receivers.
     *
     * Pull-style per SPEC §10.5. We send the ADV and then WAIT for the
     * receiver's `CTX_RESOURCE_REQ` listing which 4-byte chunk hashes
     * to transmit. The chunks themselves stay cached in
     * [pendingResourceChunks]; [handleResourceReq] resolves the hash
     * list against that cache and emits the matching CTX_RESOURCE
     * packets back to the link.
     *
     * Pre-v1.1.18 was push-style — we blasted all chunks after the ADV
     * with only `yield()` between them and ignored any incoming REQ.
     * Mobile↔mobile worked because both ends tolerated unsolicited
     * chunks; mobile→Sideband silently failed because upstream RNS
     * drops chunks that arrive before its REQ goes out OR ignores
     * out-of-band chunks entirely. The receiver's REQ never got
     * answered, no chunks arrived in response, PRF never came back,
     * sendResource timed out at ~2 min and the caller dropped the
     * image. See diagnostics 2026-05-13.
     *
     * Mobile→mobile compat is preserved because
     * [LinkResourceReceiver.handleAdvertisement] always fires a single
     * REQ for ALL hashmap entries immediately on ADV receipt —
     * effectively requesting the same set of chunks the old push loop
     * would have sent.
     *
     * Returns true if PRF arrived within [timeoutMs], false on timeout
     * or if any send threw. False does not necessarily mean the
     * receiver didn't get it — just that we have no confirmation.
     */
    suspend fun sendResource(plain: ByteArray, timeoutMs: Long): Boolean {
        check(link.state == LinkState.ACTIVE) { "Link not active (state=${link.state})" }
        check(resourcePrfDeferred == null) {
            "another Resource is already in flight on this link"
        }

        val outbound = Resource.buildOutbound(
            plain   = plain,
            link    = tokenCrypto,
            linkKey = link.derivedKey!!,
            linkId  = link.linkId!!,
            crypto  = crypto,
        )

        // Build the 4-byte-hash → chunk-bytes lookup that
        // handleResourceReq will use. Hex-keying because ByteArray
        // doesn't have a value-based hashCode/equals, so Map<ByteArray,
        // _> would key by reference identity and never match.
        val chunkLookup = HashMap<String, ByteArray>(outbound.chunks.size)
        outbound.advertisement.hashmap.forEachIndexed { i, h ->
            chunkLookup[h.toHexLower()] = outbound.chunks[i]
        }

        // Arm the awaiter BEFORE the first send so a very fast PRF can't
        // arrive before the deferred exists.
        val d = CompletableDeferred<Boolean>()
        resourcePrfDeferred = d
        pendingResourceHash = outbound.advertisement.hash
        pendingResourceChunks = chunkLookup

        try {
            val advPacket = buildPacket(
                destType   = DEST_LINK,
                packetType = PACKET_DATA,
                destHash   = link.linkId!!,
                context    = CTX_RESOURCE_ADV,
                payload    = outbound.advBodyCipher,
            )
            sender(advPacket)
            logger("→ RESOURCE_ADV ${outbound.chunks.size} parts, ${outbound.advertisement.transferSize}B total — awaiting REQ")

            return withTimeout(timeoutMs) { d.await() }
        } catch (_: TimeoutCancellationException) {
            return false
        } finally {
            resourcePrfDeferred = null
            pendingResourceHash = null
            pendingResourceChunks = emptyMap()
        }
    }

    /**
     * Inbound CTX_RESOURCE_REQ for an outbound Resource we're sending.
     * SPEC §10.5 body layout (Token-encrypted with the link's derived
     * key):
     *   exhausted_flag(1) || resource_hash(32) || N × map_hash(4)
     *
     * `exhausted_flag = 0x00` means the receiver still needs parts;
     * `0x01` means it has them all and is just acknowledging — we
     * don't resend in that case, just keep waiting for the PRF.
     *
     * For each 4-byte `map_hash` in the request, look up the matching
     * chunk in [pendingResourceChunks] and emit a CTX_RESOURCE packet.
     * Unknown hashes (typo, stale REQ from a previous Resource on the
     * same link, etc.) are logged and skipped — never crash, never
     * resend the wrong bytes.
     */
    private suspend fun handleResourceReq(pkt: Packet) {
        val lookup = pendingResourceChunks
        val expected = pendingResourceHash
        if (lookup.isEmpty() || expected == null) {
            logger("RESOURCE_REQ arrived with no in-flight outbound resource — ignoring")
            return
        }

        val plain = runCatching {
            tokenCrypto.decryptWithDerivedKey(pkt.payload, link.derivedKey!!)
        }.onFailure { logger("RESOURCE_REQ decrypt failed: ${it.message}") }
            .getOrNull() ?: return

        if (plain.size < 1 + 32) {
            logger("RESOURCE_REQ body too small (${plain.size}B, need ≥33)")
            return
        }
        val exhaustedFlag = plain[0].toInt() and 0xFF
        val advHash = plain.copyOfRange(1, 33)
        if (!advHash.contentEquals(expected)) {
            logger("RESOURCE_REQ adv hash mismatch — ignoring (likely stale REQ from prior Resource)")
            return
        }
        if (exhaustedFlag != 0x00) {
            logger("← RESOURCE_REQ exhausted (receiver has all parts) — awaiting PRF")
            return
        }

        val hashmapBytes = plain.copyOfRange(33, plain.size)
        if (hashmapBytes.size % 4 != 0) {
            logger("RESOURCE_REQ hashmap size ${hashmapBytes.size} not multiple of 4 — malformed")
            return
        }
        val requestedCount = hashmapBytes.size / 4
        logger("← RESOURCE_REQ for $requestedCount parts")

        var sent = 0
        var unknown = 0
        for (i in 0 until requestedCount) {
            val partHash = hashmapBytes.copyOfRange(i * 4, (i + 1) * 4)
            val chunk = lookup[partHash.toHexLower()]
            if (chunk == null) {
                unknown++
                continue
            }
            val chunkPacket = buildPacket(
                destType   = DEST_LINK,
                packetType = PACKET_DATA,
                destHash   = link.linkId!!,
                context    = CTX_RESOURCE,
                payload    = chunk,
            )
            sender(chunkPacket)
            yield()
            sent++
        }
        if (unknown > 0) {
            logger("RESOURCE_REQ: $unknown of $requestedCount requested hashes unknown — sent $sent")
        } else {
            logger("→ $sent RESOURCE chunks sent in response to REQ — awaiting PRF")
        }
    }

    /** Lower-case hex without allocating a StringBuilder per byte. */
    private fun ByteArray.toHexLower(): String {
        val hexChars = "0123456789abcdef"
        val out = CharArray(size * 2)
        for (i in indices) {
            val b = this[i].toInt() and 0xFF
            out[i * 2] = hexChars[b ushr 4]
            out[i * 2 + 1] = hexChars[b and 0x0F]
        }
        return out.concatToString()
    }

    /**
     * Start the initiator-side KEEPALIVE loop per SPEC §6.7.1. Call
     * once after [awaitProof] returns Validated (i.e. the link is
     * ACTIVE). Idempotent — re-arming on an already-running session is
     * a no-op so engine reuse paths don't accidentally spawn duplicate
     * loops.
     *
     * Cadence per SPEC §6.7.1:
     *   keepalive = clamp(rtt × KEEPALIVE_MAX / KEEPALIVE_MAX_RTT,
     *                     [KEEPALIVE_MIN, KEEPALIVE_MAX])
     *             = clamp(rtt × 205.7, [5 s, 360 s])
     *
     * Before the first RTT is measured the link uses the upper bound
     * (360 s). Once `link.rttSeconds` is set by validateProof we
     * recompute and the loop shortens to ~RTT × 205.7 — for a 1 s
     * RTT that's ~205 s, well under the 360 s default.
     *
     * The loop only sends when no inbound traffic has arrived within
     * the keepalive window — every inbound packet that passes through
     * [handlePacket] refreshes [lastRxAt], suppressing the next ping.
     * That makes the loop genuinely idle when the link sees regular
     * traffic and only wakes on stale links, matching upstream's
     * `Link.__watchdog_job` semantics (RNS/Link.py:751-821).
     */
    fun startKeepalive(scope: CoroutineScope) {
        if (keepaliveJob?.isActive == true) return
        keepaliveJob = scope.launch {
            // SPEC §6.7.1 constants. Express as ms so we can integer-
            // arithmetic with nowMs() directly.
            val keepaliveMinMs = 5_000L
            val keepaliveMaxMs = 360_000L
            // RTT scale factor — derived from upstream's clamp:
            // KEEPALIVE_MAX / KEEPALIVE_MAX_RTT = 360s / 1.75s = 205.71...
            // Multiplying by ms and then dividing back to ms means the
            // constant is unitless; rttSeconds × 205.7 × 1000 ms.
            val rttScale = 205.7
            // Self-ping cadence anchor. Upstream RNS Link.__watchdog_job
            // (RNS/Link.py:751-821) gates the next ping on BOTH
            // `last_inbound` AND `last_keepalive_sent` — if the
            // responder's pong is dropped on the return path, last_
            // inbound stays stale forever, and pure-last_inbound logic
            // would spam pings every loop iteration. The fix is to
            // also reset the cadence clock when WE send a ping so
            // there's at most one ping per keepalive interval
            // regardless of pong delivery. Observed on-device 2026-05-13
            // pre-v1.1.22: 30-second ping spam on a Sideband peer
            // whose pongs the transit relay dropped, despite the
            // link genuinely staying alive.
            var lastKeepaliveSentAt = -1L
            while (isActive && link.state == LinkState.ACTIVE) {
                val rtt = link.rttSeconds
                val keepaliveMs = if (rtt > 0.0) {
                    val computed = (rtt * rttScale * 1000.0).toLong()
                    min(max(computed, keepaliveMinMs), keepaliveMaxMs)
                } else {
                    keepaliveMaxMs  // before first RTT
                }

                val now = nowMs()
                // The effective "last activity" anchor is the LATER of
                // (inbound traffic, our own ping). This lets a healthy
                // link with bidirectional pongs throttle on the pong
                // refresh (good for monitoring), and a lossy-return
                // link still throttle on our own emissions (good for
                // bandwidth).
                val anchorMs = max(lastRxAt, lastKeepaliveSentAt)
                val sinceAnchorMs = if (anchorMs > 0) now - anchorMs else keepaliveMs
                val sleepMs = if (sinceAnchorMs >= keepaliveMs) 0L else keepaliveMs - sinceAnchorMs
                if (sleepMs > 0) {
                    delay(sleepMs)
                    continue  // re-check after sleep; lastRxAt may have advanced
                }

                // Idle long enough — emit ping. Per §6.7.1 the body is
                // a single 0xFF byte, Token-encrypted with the link's
                // derived key. DEST_LINK is mandatory per §12.5.2 so
                // the transit relay routes via link_table.
                val idleSec = if (lastRxAt > 0) (now - lastRxAt) / 1000 else -1L
                runCatching {
                    val pingCipher = tokenCrypto.encryptWithDerivedKey(
                        byteArrayOf(0xFF.toByte()),
                        link.derivedKey!!,
                    )
                    val pingPacket = buildPacket(
                        destType   = DEST_LINK,
                        packetType = PACKET_DATA,
                        destHash   = link.linkId!!,
                        context    = io.github.thatsfguy.reticulum.protocol.CTX_KEEPALIVE,
                        payload    = pingCipher,
                    )
                    sender(pingPacket)
                    logger("→ KEEPALIVE ping (last inbound ${idleSec}s ago, cadence ${keepaliveMs / 1000}s)")
                    lastKeepaliveSentAt = nowMs()
                }.onFailure {
                    logger("KEEPALIVE send threw: ${it.message}")
                    // Don't update lastKeepaliveSentAt on failure so the
                    // next iteration retries soon (something's wrong;
                    // a fast retry is better than another full-window
                    // wait).
                }

                // The loop's anchor-based throttle keeps the next ping
                // bounded to at most one per `keepaliveMs` interval,
                // even if no pong arrives to refresh lastRxAt. Sleep a
                // short grace before re-checking so a fast pong (well
                // inside the next window) does observably refresh the
                // anchor before the next idle math runs.
                delay(min(keepaliveMs / 4, 30_000L))
            }
        }
    }

    /** Cancel the keepalive loop. Idempotent — safe to call from
     *  link-close cleanup paths even if `startKeepalive` was never
     *  invoked. */
    fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    /** [LinkPump.dispose] override — engines call this at every
     *  removal site so the keepalive loop is cancelled deterministically.
     *  Without this, `activeSessions.clear()` on identity reset would
     *  drop the session reference but the keepalive coroutine would
     *  live on until the parent scope cancels (worst case: never
     *  cancels on app lifetime, sends bogus pings on a dropped link). */
    override fun dispose() {
        stopKeepalive()
    }

    /**
     * Inbound CTX_RESOURCE_PRF for an outbound Resource we sent.
     * §10.5 payload: adv.h(32) || sha256(plain || adv.h)(32) = 64 bytes.
     *
     * Match adv.h against our cached integrity hash; only a matching PRF
     * resolves the deferred. The second 32 bytes are NOT verified here —
     * we don't keep the plaintext around after send, and the integrity
     * hash match is already a 256-bit proof the receiver has the right
     * resource. A wrong-hash PRF leaves the deferred pending so a
     * legitimate one (if it ever arrives) can still resolve it.
     */
    private fun handleResourcePrf(pkt: Packet) {
        if (pkt.payload.size != 64) {
            logger("RESOURCE_PRF wrong size (${pkt.payload.size}B, need 64)")
            return
        }
        val advHash = pkt.payload.copyOfRange(0, 32)
        val expected = pendingResourceHash
        if (expected == null) {
            logger("RESOURCE_PRF arrived with no awaiter")
            return
        }
        if (!expected.contentEquals(advHash)) {
            val got = advHash.copyOfRange(0, 4).joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            logger("RESOURCE_PRF adv hash mismatch (got $got…) — ignoring")
            return
        }
        val hashHex = advHash.copyOfRange(0, 4).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        logger("✓ RESOURCE_PRF for $hashHex…")
        resourcePrfDeferred?.complete(true)
    }

    /**
     * Send a CTX_NONE link DATA packet and suspend until the responder
     * answers with the per-packet PROOF (spec §6.5 implicit form,
     * payload[:32] = SHA-256(hashable_part) of the DATA we sent). Used
     * for link-delivered LXMF — the PROOF is the recipient's "I got it"
     * signal, equivalent in role to the opportunistic-DATA PROOF the
     * engine pump matches by truncated dest_hash.
     *
     * Returns true if the proof arrived within [timeoutMs], false on
     * timeout. False does NOT mean the recipient didn't get it —
     * just that we have no confirmation. Callers can fall back to
     * an opportunistic retransmit.
     */
    suspend fun sendDataAndAwaitProof(plaintext: ByteArray, timeoutMs: Long): Boolean {
        check(link.state == LinkState.ACTIVE) { "Link not active (state=${link.state})" }
        val ciphertext = tokenCrypto.encryptWithDerivedKey(plaintext, link.derivedKey!!)
        val packet = buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_DATA,
            destHash   = link.linkId!!,
            context    = io.github.thatsfguy.reticulum.protocol.CTX_NONE,
            payload    = ciphertext,
        )
        val parsed = parsePacket(packet) ?: error("self-parse failed building link DATA")
        val fullHashHex = computePacketFullHash(parsed, crypto)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        val d = CompletableDeferred<Boolean>()
        pendingDataProofs[fullHashHex] = d
        sender(packet)
        logger("→ link DATA ${plaintext.size}B (fullhash=${fullHashHex.take(8)}…) — awaiting proof")
        return try {
            withTimeout(timeoutMs) { d.await() }
        } catch (_: TimeoutCancellationException) {
            pendingDataProofs.remove(fullHashHex)
            false
        }
    }

    /**
     * Engine pump → session entry point. Called whenever an inbound
     * packet's destHash matches this session's link_id.
     */
    override suspend fun handlePacket(pkt: Packet, rssi: Int?) {
        logger("session rx pt=${pkt.packetType} ctx=0x${pkt.context.toString(16).padStart(2, '0')} payload=${pkt.payload.size}B")

        // Per-packet PROOF for an outbound link DATA we sent — payload
        // begins with the 32-byte full hash of the original packet.
        // This is the link analogue of the opportunistic-DATA PROOF the
        // engine pump matches by truncated dest_hash; see spec §6.5.
        //
        // CRITICAL: CTX_LRPROOF (0xFF) — the link-establishment proof
        // the responder sends in reply to our LINKREQUEST — MUST fall
        // through to the lower `when (pkt.context) { CTX_LRPROOF -> ...
        // link.validateProof + proofDeferred.complete } block. An
        // earlier draft of this dispatch (92e937b, Phase 1 Resource
        // sender) early-returned on every PROOF including LRPROOF,
        // which killed every initiator-side link establishment in
        // v1.1.15 — the responder sent LRPROOFs, our pump routed them
        // here, and the early-return skipped validateProof. tryDeliver
        // OverLink burned its full 5 × 45s retry budget on each send.
        // Discovered 2026-05-13 from live `→ LRPROOF for ... (responder,
        // on Tcp)` logs paired with our own initiator-side `✗ no LRPROOF
        // within 45s` — same flag bit lit on both sides because both
        // run our code.
        if (pkt.packetType == PACKET_PROOF) {
            when (pkt.context) {
                io.github.thatsfguy.reticulum.protocol.CTX_NONE -> {
                    handleDataProof(pkt)
                    return
                }
                CTX_RESOURCE_PRF -> {
                    handleResourcePrf(pkt)
                    return
                }
                CTX_LRPROOF -> {
                    // Fall through — handled by the established-link
                    // dispatch below.
                }
                else -> {
                    logger("PROOF with unhandled ctx=0x${pkt.context.toString(16).padStart(2, '0')}")
                    return
                }
            }
        }

        // Avoid Map.merge() — that's a JVM-only Java 8 default method and
        // the same expression doesn't compile for the iOS/Native target.
        rxByContext[pkt.context] = (rxByContext[pkt.context] ?: 0) + 1
        val now = nowMs()
        if (firstRxAt < 0) firstRxAt = now
        lastRxAt = now
        when (pkt.context) {
            CTX_LRPROOF -> {
                val res = link.validateProof(pkt.payload, nowMs())
                when (res) {
                    is Link.LrProofResult.Success -> {
                        // LRRTT confirmation packet — caller doesn't await it.
                        // Spec §12.5.2: dest_type = LINK so transit relays
                        // route via link_table, not path_table. Without
                        // this the responder never sees our LRRTT and
                        // can't transition the link to ACTIVE → REQUEST
                        // packets that follow are dropped too.
                        val rttPkt = buildPacket(
                            destType = DEST_LINK,
                            packetType = PACKET_DATA,
                            destHash = link.linkId!!,
                            context = CTX_LRRTT,
                            payload = res.rttData,
                        )
                        sender(rttPkt)
                        logger("LRPROOF ok rtt=${(res.rtt * 1000).toLong()}ms")
                        proofDeferred?.complete(ProofResult.Validated(res.rtt))
                    }
                    is Link.LrProofResult.Failure -> {
                        logger("LRPROOF rejected: ${res.reason}")
                        proofDeferred?.complete(ProofResult.Invalid(res.reason))
                    }
                }
            }

            CTX_RESOURCE_ADV -> resourceReceiver.handleAdvertisement(pkt)
            CTX_RESOURCE     -> resourceReceiver.handleChunk(pkt)
            CTX_RESOURCE_REQ -> handleResourceReq(pkt)

            CTX_RESPONSE -> {
                val plain = runCatching {
                    tokenCrypto.decryptWithDerivedKey(pkt.payload, link.derivedKey!!)
                }.onFailure { logger("RESPONSE decrypt failed: ${it.message}") }.getOrNull() ?: return
                val decoded = runCatching { MessagePack.decode(plain) }
                    .onFailure { logger("RESPONSE msgpack decode failed: ${it.message}") }
                    .getOrNull()
                if (decoded !is List<*> || decoded.size < 2) {
                    logger("RESPONSE shape unexpected: ${decoded?.let { it::class.simpleName }}")
                    return
                }
                if (!matchesExpectedRequestId(decoded[0])) return
                val body = decoded[1]
                // NomadNet pages: body is bytes/string (the page content).
                // Propagation /get rounds: body is a list (of transient_ids
                // or of LXMF blobs). Re-msgpack-encode complex types so
                // the caller always gets bytes and decides what to do.
                val bytes = when (body) {
                    is ByteArray -> body
                    is String    -> body.encodeToByteArray()
                    null         -> ByteArray(0)
                    else         -> MessagePack.encode(body)
                }
                responseDeferred?.complete(bytes)
            }

            // Other contexts (KEEPALIVE, LINKCLOSE, etc.) are not yet
            // exercised by the page-fetch / propagation flow. Silently
            // ignore. RESOURCE_REQ is handled above (added v1.1.18 for
            // Sideband interop).
            else -> Unit
        }
    }

    /**
     * Initiator-side resolver for a fully-reassembled inbound resource.
     * Outer-decrypted plaintext for a request/response resource is a
     * msgpack `[request_id(16), response_data]` envelope — same shape
     * a single-packet RESPONSE produces. We re-msgpack-encode complex
     * response_data so the caller always gets bytes (matching the
     * single-packet path). Wired into [resourceReceiver] via the
     * `onAssembled` lambda at construction.
     *
     * The [LinkResourceReceiver] handles the §10.5 RESOURCE_REQ +
     * §10.2 chunk-collection + PRF emit before this fires; this method
     * is purely the request/response envelope decode for the consumer
     * that's awaiting `responseDeferred`.
     */
    private fun deliverAssembledResourceAsResponse(plain: ByteArray) {
        val decoded = runCatching { MessagePack.decode(plain) }
            .onFailure { logger("resource msgpack decode failed: ${it.message}") }
            .getOrNull()
        if (decoded is List<*> && decoded.size >= 2) {
            if (!matchesExpectedRequestId(decoded[0])) return
            val body = decoded[1]
            val bytes = when (body) {
                is ByteArray -> body
                is String    -> body.encodeToByteArray()
                null         -> ByteArray(0)
                else         -> MessagePack.encode(body)
            }
            responseDeferred?.complete(bytes)
        } else {
            // Fall back to raw plaintext so the caller can still see something.
            responseDeferred?.complete(plain)
        }
    }

    /**
     * Compare an inbound RESPONSE envelope's element [0] against the
     * 16-byte request_id we're expecting (per spec §11.2). Returns true
     * if the response should be accepted. Logs and returns false for
     * any mismatch — wrong type, wrong length, wrong bytes, or no
     * in-flight request at all.
     *
     * Defense-in-depth: each rejection path logs a distinct reason so a
     * pattern of "mismatched id" rejections is visible to operators
     * (could indicate a misbehaving relay or replay activity, not just
     * the routine "stale RESPONSE arrived after timeout" case).
     */
    private fun matchesExpectedRequestId(envelopeId: Any?): Boolean {
        val expected = expectedRequestId ?: run {
            logger("RESPONSE arrived with no in-flight request — dropping")
            return false
        }
        if (envelopeId !is ByteArray) {
            logger("RESPONSE element [0] is not bytes (got ${envelopeId?.let { it::class.simpleName }}) — dropping")
            return false
        }
        if (envelopeId.size != 16) {
            logger("RESPONSE request_id wrong size (${envelopeId.size}B, expected 16) — dropping")
            return false
        }
        if (!envelopeId.contentEquals(expected)) {
            logger("RESPONSE request_id mismatch — dropping (expected ${expected.toLogHex()}, got ${envelopeId.toLogHex()})")
            return false
        }
        return true
    }
}

private fun ByteArray.toLogHex(): String =
    take(4).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } +
        if (size > 4) "…" else ""

