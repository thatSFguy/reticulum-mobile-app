package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.LinkState
import io.github.thatsfguy.reticulum.protocol.CTX_REQUEST
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_ADV
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_PRF
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_REQ
import io.github.thatsfguy.reticulum.protocol.CTX_RESPONSE
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
import io.github.thatsfguy.reticulum.resource.Resource
import io.github.thatsfguy.reticulum.resource.ResourceAdvertisement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [LinkSession]'s REQUEST/RESPONSE flow — the layer
 * NomadNet page fetch and propagation /get sit on. Without these
 * tests, a regression that broke msgpack envelope shape or response
 * routing would manifest as "fetchNomadPage hangs forever" — exactly
 * the silent-loss class of bug Tier 2 is targeting.
 */
class LinkSessionTest {

    @Test fun `request packet has correct flags + dest + context + msgpack envelope`() = runTest {
        val (session, link, sentPackets) = newActiveLinkSession()
        val pathHash = ByteArray(16) { (it + 1).toByte() }  // 16 bytes per spec §11.1
        val body = "hello".encodeToByteArray()

        // Fire request in background so we can inspect the sent packet
        // without waiting for a (never-arriving) response.
        val req = async { session.request(pathHash, body, timeoutMs = 100) }
        testScheduler.advanceUntilIdle()

        assertEquals(1, sentPackets.size, "request should have written exactly one packet")
        val raw = sentPackets.first()
        val parsed = parsePacket(raw)
        assertNotNull(parsed)
        assertEquals(PACKET_DATA, parsed.packetType, "request must be DATA, not LINKREQ")
        assertContentEquals(link.linkId, parsed.destHash, "request must target the link_id")
        assertEquals(CTX_REQUEST, parsed.context, "request must use CTX_REQUEST (0x09)")
        assertEquals(
            DEST_LINK, parsed.destType,
            "spec §12.5.2: a packet addressed to a link_id must have dest_type=LINK, " +
            "otherwise the relay's link_table lookup never fires and the packet is silently dropped"
        )

        // Decrypt the encrypted payload and inspect the msgpack envelope.
        val tokenCrypto = TokenCrypto(TestVectors.crypto)
        val plain = tokenCrypto.decryptWithDerivedKey(parsed.payload, link.derivedKey!!)
        val decoded = MessagePack.decode(plain)
        assertTrue(decoded is List<*>, "request payload must decode to a list")
        val list = decoded as List<*>
        assertEquals(3, list.size, "request envelope must be [timestamp, pathHash, body]")
        assertContentEquals(pathHash, list[1] as ByteArray, "envelope[1] must be the path hash")
        assertEquals(16, (list[1] as ByteArray).size, "spec §11.1: path_hash on the wire must be 16 bytes")
        assertContentEquals(body, list[2] as ByteArray, "envelope[2] must be the request body")

        // Let the in-flight request time out cleanly.
        req.await()
    }

    @Test fun `request returns response bytes when matching CTX_RESPONSE arrives`() = runTest {
        val (session, link, sentPackets) = newActiveLinkSession()
        val pathHash = ByteArray(16) { (it + 7).toByte() }
        val tokenCrypto = TokenCrypto(TestVectors.crypto)

        // Background: send the request and suspend on the responseDeferred.
        // We must NOT call advanceUntilIdle() here — that would advance
        // virtual time past the 30s timeout, fire withTimeout, and complete
        // the request with null before our hand-crafted response arrives.
        // runCurrent() runs the request's path up to its first suspension
        // (at responseDeferred.await()) without advancing the clock.
        val req = async { session.request(pathHash, null, timeoutMs = 30_000) }
        testScheduler.runCurrent()

        // Spec §11.2: response element [0] carries the 16-byte request_id =
        // SHA-256(packed_request)[:16]. Pre-v0.1.54 the session ignored this
        // and resolved the in-flight deferred on any RESPONSE; now it must
        // match. Derive the expected id from the captured outbound packet.
        val requestId = expectedRequestIdOf(sentPackets.first(), link)
        val responsePayload = MessagePack.encode(listOf<Any?>(
            requestId,
            "the page body".encodeToByteArray(),
        ))
        val ciphertext = tokenCrypto.encryptWithDerivedKey(responsePayload, link.derivedKey!!)
        val responsePacket = buildPacket(
            packetType = PACKET_DATA,
            destHash = link.linkId!!,
            context = CTX_RESPONSE,
            payload = ciphertext,
        )
        session.handlePacket(parsePacket(responsePacket)!!)
        testScheduler.runCurrent()

        val result = req.await()
        assertNotNull(result, "request should have returned non-null bytes")
        assertEquals("the page body", result.decodeToString())
    }

    // v0.1.54 — request_id verification (security):
    //
    // Spec §11.2 mandates RESPONSE element [0] = SHA-256(packed_request)[:16].
    // Pre-v0.1.54 the session resolved the in-flight deferred on ANY
    // RESPONSE that landed on the link_id. Latent bug today (we only run
    // one in-flight request per link), but a real footgun the moment we
    // add link reuse / partials. Worse: a misbehaving or compromised
    // transit relay can replay a stale RESPONSE from an earlier request
    // and we'd accept it as the answer to whatever's currently pending.
    // This is exactly the kind of confused-deputy bug the spec field was
    // designed to prevent — Mark Qvist makes the same point at SPEC.md:2120.
    @Test fun `RESPONSE with mismatched request_id is rejected then matching one resolves`() = runTest {
        val (session, link, sentPackets) = newActiveLinkSession()
        val pathHash = ByteArray(16) { (it + 7).toByte() }
        val tokenCrypto = TokenCrypto(TestVectors.crypto)

        val req = async { session.request(pathHash, null, timeoutMs = 30_000) }
        testScheduler.runCurrent()

        // First: deliver a RESPONSE with a wrong request_id (all 0xFFs).
        // Must NOT resolve the deferred — that's the security property.
        val wrongId = ByteArray(16) { 0xFF.toByte() }
        val wrongPayload = MessagePack.encode(listOf<Any?>(
            wrongId, "from a previous request".encodeToByteArray(),
        ))
        val wrongPacket = buildPacket(
            packetType = PACKET_DATA,
            destHash = link.linkId!!,
            context = CTX_RESPONSE,
            payload = tokenCrypto.encryptWithDerivedKey(wrongPayload, link.derivedKey!!),
        )
        session.handlePacket(parsePacket(wrongPacket)!!)
        testScheduler.runCurrent()
        assertTrue(req.isActive, "deferred must still be pending after a wrong-id RESPONSE")

        // Now deliver the RESPONSE with the correct id. Must resolve.
        val correctId = expectedRequestIdOf(sentPackets.first(), link)
        val goodPayload = MessagePack.encode(listOf<Any?>(
            correctId, "the right response".encodeToByteArray(),
        ))
        val goodPacket = buildPacket(
            packetType = PACKET_DATA,
            destHash = link.linkId!!,
            context = CTX_RESPONSE,
            payload = tokenCrypto.encryptWithDerivedKey(goodPayload, link.derivedKey!!),
        )
        session.handlePacket(parsePacket(goodPacket)!!)
        testScheduler.runCurrent()

        assertEquals("the right response", req.await()?.decodeToString())
    }

    @Test fun `RESPONSE with malformed envelope (element 0 not 16-byte bytes) is rejected`() = runTest {
        // Defense-in-depth: even a well-encoded msgpack list whose [0] is
        // not a 16-byte ByteArray (a string, a short blob, null) must not
        // be accepted as a matching response. The check runs BEFORE we
        // hand the body up to the caller.
        val (session, link, _) = newActiveLinkSession()
        val tokenCrypto = TokenCrypto(TestVectors.crypto)

        val req = async { session.request(ByteArray(16), null, timeoutMs = 200) }
        testScheduler.runCurrent()

        val malformed = MessagePack.encode(listOf<Any?>(
            "not-a-16-byte-bytes",  // string, not ByteArray
            "body".encodeToByteArray(),
        ))
        val pkt = buildPacket(
            packetType = PACKET_DATA,
            destHash = link.linkId!!,
            context = CTX_RESPONSE,
            payload = tokenCrypto.encryptWithDerivedKey(malformed, link.derivedKey!!),
        )
        session.handlePacket(parsePacket(pkt)!!)
        testScheduler.runCurrent()
        assertTrue(req.isActive, "malformed RESPONSE must not complete the deferred")
        // Let it time out cleanly.
        assertNull(req.await())
    }

    // v0.1.53 — REQUEST envelope shape (security/compat):
    //
    // Spec §11.1 + upstream NomadNet Node.py:109-111 + LXMRouter.py /get:
    // element [2] of the outer msgpack envelope `[time, path_hash, data]`
    // is the request data ITSELF, not a pre-msgpack-encoded byte blob.
    // For NomadNet form posts that's a `dict` of `{ "field_<k>": "<v>", ... }`;
    // for propagation /get rounds it's a 2- or 3-element list; for plain
    // page GETs it's `None` / nil. Pre-v0.1.53 callers msgpack-encoded
    // these structures themselves and passed the bytes — which the engine
    // then wrapped as msgpack `bin` in element [2]. Server-side handlers
    // do `isinstance(data, dict)` / `isinstance(data, list)` and silently
    // fall through on bytes — every form submission no-op'd, every
    // propagation /get round delivered an empty payload.
    //
    // Failing pre-fix: element [2] decodes as ByteArray. After fix: as
    // Map / List / null per the structured input.
    @Test fun `request encodes form-data Map directly as msgpack map (no double-encode)`() = runTest {
        val (session, link, sentPackets) = newActiveLinkSession()
        val pathHash = ByteArray(16) { (it + 1).toByte() }
        val formData = mapOf("field_message" to "hello", "field_user" to "alice")

        val req = async { session.request(pathHash, formData, timeoutMs = 100) }
        testScheduler.advanceUntilIdle()

        val raw = sentPackets.first()
        val parsed = parsePacket(raw)!!
        val tokenCrypto = TokenCrypto(TestVectors.crypto)
        val plain = tokenCrypto.decryptWithDerivedKey(parsed.payload, link.derivedKey!!)
        val decoded = MessagePack.decode(plain) as List<*>

        val data = decoded[2]
        assertTrue(
            data is Map<*, *>,
            "envelope[2] must decode to a msgpack map for form posts " +
                "(upstream Node.py:109 does `isinstance(data, dict)` and silently " +
                "falls through if it sees `bytes`); got ${data?.let { it::class.simpleName }}",
        )
        assertEquals("hello", data["field_message"])
        assertEquals("alice", data["field_user"])

        req.await()
    }

    @Test fun `request encodes null as msgpack nil for plain GET`() = runTest {
        val (session, link, sentPackets) = newActiveLinkSession()
        val pathHash = ByteArray(16) { (it + 1).toByte() }

        val req = async { session.request(pathHash, data = null, timeoutMs = 100) }
        testScheduler.advanceUntilIdle()

        val raw = sentPackets.first()
        val parsed = parsePacket(raw)!!
        val tokenCrypto = TokenCrypto(TestVectors.crypto)
        val plain = tokenCrypto.decryptWithDerivedKey(parsed.payload, link.derivedKey!!)
        val decoded = MessagePack.decode(plain) as List<*>

        assertNull(
            decoded[2],
            "envelope[2] must be msgpack nil for a plain GET (matches upstream " +
                "Browser.py:1227 where request_data defaults to None), not an empty bin",
        )

        req.await()
    }

    @Test fun `request encodes List directly for propagation get rounds`() = runTest {
        // Propagation /get round 2 envelope element [2] is the list
        // `[wants, haves, transferLimitKb]` (per LXMF/LXMRouter.py).
        val (session, link, sentPackets) = newActiveLinkSession()
        val pathHash = ByteArray(16) { (it + 1).toByte() }
        val r2 = listOf(listOf(ByteArray(16) { 0x42 }), emptyList<ByteArray>(), 256)

        val req = async { session.request(pathHash, r2, timeoutMs = 100) }
        testScheduler.advanceUntilIdle()

        val raw = sentPackets.first()
        val parsed = parsePacket(raw)!!
        val tokenCrypto = TokenCrypto(TestVectors.crypto)
        val plain = tokenCrypto.decryptWithDerivedKey(parsed.payload, link.derivedKey!!)
        val decoded = MessagePack.decode(plain) as List<*>

        val data = decoded[2]
        assertTrue(
            data is List<*>,
            "envelope[2] must decode to a msgpack list for propagation /get, " +
                "not a bin-wrapped pre-encoded blob",
        )
        assertEquals(3, data.size, "round 2 envelope has 3 elements: [wants, haves, limitKb]")
        assertTrue(data[0] is List<*>, "wants[0] must be the wants-list, not bytes")
    }

    @Test fun `request still accepts ByteArray data and emits msgpack bin`() = runTest {
        // Backwards-compat: a caller that genuinely wants element [2] to be
        // raw bytes (e.g. an opaque application-layer blob) can still pass
        // a ByteArray. The encoder produces msgpack bin, decoder returns
        // ByteArray. Existing test `request packet has correct flags…`
        // exercises this; this test pins the contract explicitly.
        val (session, link, sentPackets) = newActiveLinkSession()
        val pathHash = ByteArray(16)
        val rawBytes = "raw".encodeToByteArray()

        val req = async { session.request(pathHash, rawBytes, timeoutMs = 100) }
        testScheduler.advanceUntilIdle()

        val raw = sentPackets.first()
        val parsed = parsePacket(raw)!!
        val tokenCrypto = TokenCrypto(TestVectors.crypto)
        val plain = tokenCrypto.decryptWithDerivedKey(parsed.payload, link.derivedKey!!)
        val decoded = MessagePack.decode(plain) as List<*>
        assertContentEquals(rawBytes, decoded[2] as ByteArray)

        req.await()
    }

    @Test fun `request returns null on timeout`() = runTest {
        val (session, _, _) = newActiveLinkSession()
        val pathHash = ByteArray(16)

        val result = session.request(pathHash, ByteArray(0), timeoutMs = 50)
        assertNull(result, "request must return null when no response arrives within timeout")
    }

    @Test fun `request rejects non-16-byte pathHash`() = runTest {
        val (session, _, _) = newActiveLinkSession()
        // Pre-spec-fix code accepted 32 bytes; spec §11.1 mandates 16. Also
        // exercises 8 (too short) for symmetric coverage.
        val tooLong = ByteArray(32)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            session.request(tooLong, ByteArray(0), timeoutMs = 100)
        }
        val tooShort = ByteArray(8)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            session.request(tooShort, ByteArray(0), timeoutMs = 100)
        }
    }

    @Test fun `request fails if link is not ACTIVE`() = runTest {
        val (session, link, _) = newActiveLinkSession()
        link.state = LinkState.CLOSED
        kotlin.test.assertFailsWith<IllegalStateException> {
            session.request(ByteArray(16), ByteArray(0), timeoutMs = 100)
        }
    }

    // v0.1.49 diagnostic surface — turn "no LRPROOF received" into something
    // useful. The session counts inbound packets per context and surfaces
    // a one-line summary the engine can fold into its timeout message.

    @Test fun `diagnosticSummary is empty when no packets arrived`() = runTest {
        val (session, _, _) = newActiveLinkSession()
        assertEquals("", session.diagnosticSummary(),
            "no rx → empty string lets caller substitute 'no inbound packets' message")
    }

    @Test fun `diagnosticSummary surfaces context histogram and parts received`() = runTest {
        val (session, link, _) = newActiveLinkSession()
        // Use CTX_LRRTT and CTX_REQUEST — both fall through `else -> Unit`
        // in handlePacket so we exercise the histogram bumping without
        // tripping validateProof / decrypt paths that need ECDH-real
        // payloads. The histogram is bumped BEFORE the `when` branch so
        // unhandled contexts still contribute.
        val rttPkt = buildPacket(
            packetType = PACKET_DATA,
            destHash = link.linkId!!,
            context = io.github.thatsfguy.reticulum.protocol.CTX_LRRTT,
            payload = ByteArray(8),
        )
        session.handlePacket(parsePacket(rttPkt)!!)

        val reqPkt = buildPacket(
            packetType = PACKET_DATA,
            destHash = link.linkId!!,
            context = CTX_REQUEST,
            payload = ByteArray(0),
        )
        session.handlePacket(parsePacket(reqPkt)!!)

        val summary = session.diagnosticSummary()
        assertTrue("LRRTT×1" in summary, "expected named LRRTT entry; got: $summary")
        assertTrue("REQUEST×1" in summary, "expected named REQUEST entry; got: $summary")
        assertTrue("rx [" in summary && summary.startsWith("rx ["),
            "summary should lead with 'rx [...]' shape; got: $summary")
    }


    // ---- Helpers -----------------------------------------------------------

    /**
     * Server-side per upstream RNS `Link.handle_request:1286` —
     * `request_id = packet.getTruncatedHash()`, computed from the
     * packet's hashable_part (low nibble of flags || everything after
     * dest_hash slot for HEADER_2 / after flags+hops for HEADER_1).
     * NOT a hash of the inner plaintext. Re-derive it the same way
     * from a captured outbound packet so tests construct matching
     * RESPONSEs without coupling to internal session state.
     */
    // ---------------------------------------------------------------------
    // sendResource() — outbound Resource sender for LXMF image attachments.
    // Spec §10.2 step 1-3 (ADV + chunk stream) + §10.5 PRF wait.
    // ---------------------------------------------------------------------

    @Test fun `sendResource emits only the ADV before any RESOURCE_REQ — pull-style`() = runTest {
        // v1.1.18 protocol change: per SPEC §10.5, the sender emits the
        // ADV and WAITS for the receiver's RESOURCE_REQ before sending
        // chunks. Pre-v1.1.18 was push-style — we blasted all chunks
        // immediately after ADV — which broke Sideband interop because
        // upstream RNS drops unsolicited chunks that arrive before its
        // own REQ goes out.
        val (session, link, sentPackets) = newActiveLinkSession()
        val payload = ByteArray(2_000) { (it % 251).toByte() }

        val send = async { session.sendResource(payload, timeoutMs = 5_000) }
        testScheduler.runCurrent()

        assertEquals(1, sentPackets.size,
            "sendResource must emit exactly one packet (the ADV) before a REQ arrives — " +
                "if chunks were sent here, we'd be back to the push-style behavior that broke Sideband")
        val advParsed = parsePacket(sentPackets.first())!!
        assertEquals(PACKET_DATA, advParsed.packetType, "ADV is DATA")
        assertEquals(CTX_RESOURCE_ADV, advParsed.context, "first packet must be CTX_RESOURCE_ADV")
        assertEquals(DEST_LINK, advParsed.destType, "spec §12.5.2: link-addressed → DEST_LINK")
        assertContentEquals(link.linkId, advParsed.destHash, "ADV must target link_id")

        // Let the send time out (we never deliver a REQ or PRF here).
        assertEquals(false, send.await(), "no REQ + no PRF delivered → sendResource returns false")
    }

    @Test fun `sendResource onProgress reflects peer's consecutive position, not REQ burst size`() = runTest {
        // Observed v1.2.24: progress jumped straight to 80%+ on the
        // first REQ. Root cause was `pct = (totalParts - requestedCount) / totalParts`
        // — that's only correct when the receiver REQs for the WHOLE
        // missing set at once (legacy fixed-window=96). With v1.2.23's
        // windowed REQ (4 at a time), `requestedCount` is the burst
        // size, not the missing-set size, so a fresh transfer's first
        // REQ for 4 parts of 5 looked like "80% done" before any chunk
        // had been delivered.
        //
        // Correct math: the LOWEST slot index in the REQ corresponds
        // to the peer's first missing slot (per RNS/Resource.py:865
        // `nextRequestBatch` starts from `consecutive_completed_height`),
        // so peer has slots [0, lowest_idx) filled and progress is
        // `lowest_idx / total`. This test pins that formula.
        val (session, link, sentPackets) = newActiveLinkSession()
        val tokenCrypto = TokenCrypto(TestVectors.crypto)
        val payload = ByteArray(3_000) { (it * 7).toByte() }
        val progressEvents = mutableListOf<Int>()

        val send = async {
            session.sendResource(payload, timeoutMs = 60_000, onProgress = { progressEvents += it })
        }
        testScheduler.runCurrent()

        val advParsed = parsePacket(sentPackets.first())!!
        val advPlain = tokenCrypto.decryptWithDerivedKey(advParsed.payload, link.derivedKey!!)
        val adv = ResourceAdvertisement.parse(advPlain, link.linkId!!)
        val advHash = adv.hash
        val nParts = adv.hashmap.size
        assertTrue(nParts >= 5, "test needs ≥5 parts; got $nParts")

        // Reset the progress log to ignore the initial 0% emit at
        // sendResource start (which is correct but distracts from
        // what the REQ-driven math should do).
        progressEvents.clear()

        suspend fun deliverReqForSlots(slots: List<Int>) {
            val hashmapBytes = ByteArray(slots.size * 4)
            for ((i, slot) in slots.withIndex()) {
                adv.hashmap[slot].copyInto(hashmapBytes, i * 4)
            }
            val reqBody = ByteArray(1 + advHash.size + hashmapBytes.size).also {
                it[0] = 0x00
                advHash.copyInto(it, 1)
                hashmapBytes.copyInto(it, 1 + advHash.size)
            }
            val reqCipher = tokenCrypto.encryptWithDerivedKey(reqBody, link.derivedKey!!)
            val reqPacket = buildPacket(
                destType = DEST_LINK,
                packetType = PACKET_DATA,
                destHash = link.linkId!!,
                context = CTX_RESOURCE_REQ,
                payload = reqCipher,
            )
            session.handlePacket(parsePacket(reqPacket)!!)
            testScheduler.runCurrent()
        }

        // First REQ: peer asks for slots [0, 1, 2, 3] — they have NOTHING.
        // Progress must report 0%, NOT a misleading high number derived
        // from the small burst size.
        deliverReqForSlots(listOf(0, 1, 2, 3))
        assertTrue(progressEvents.lastOrNull() == null || progressEvents.last() == 0,
            "fresh REQ for low slots must NOT emit any non-zero progress — peer has nothing yet. " +
                "Got: $progressEvents")

        // Peer's next REQ starts at slot 4 → they have slots 0-3 (4 of N parts).
        progressEvents.clear()
        val expectedPctAfter4 = 4 * 100 / nParts
        deliverReqForSlots(listOf(4, 5))
        assertEquals(expectedPctAfter4, progressEvents.lastOrNull() ?: -1,
            "REQ starting at slot 4 means peer has slots [0..4) filled → ${expectedPctAfter4}%. " +
                "Got: $progressEvents")

        // Peer's next REQ starts at slot 6 → they have slots 0-5 (6 of N parts).
        progressEvents.clear()
        val expectedPctAfter6 = 6 * 100 / nParts
        deliverReqForSlots(listOf(6))
        assertEquals(expectedPctAfter6, progressEvents.lastOrNull() ?: -1,
            "REQ starting at slot 6 means peer has slots [0..6) filled → ${expectedPctAfter6}%. " +
                "Got: $progressEvents")

        // Monotonicity: a stale-looking REQ for an earlier slot (e.g.
        // retransmit picked up slot 2 because parts[2] showed null
        // transiently) must NOT regress the progress bar.
        progressEvents.clear()
        deliverReqForSlots(listOf(2, 3))
        assertTrue(progressEvents.isEmpty(),
            "lower-slot REQ must NOT regress monotonic progress. Got: $progressEvents")

        send.cancel()
    }

    @Test fun `sendResource emits requested chunks in response to a CTX_RESOURCE_REQ — v1_1_18 pull-style`() = runTest {
        // The actual interop fix: a Sideband-style REQ comes in listing
        // all chunk hashes; our sender must look them up and emit the
        // matching CTX_RESOURCE packets. Without this the chunks would
        // never reach Sideband and the Resource never assembles.
        val (session, link, sentPackets) = newActiveLinkSession()
        val tokenCrypto = TokenCrypto(TestVectors.crypto)
        // 2 KB payload → splits into multiple chunks at DEFAULT_SDU=464.
        val payload = ByteArray(2_000) { (it * 13).toByte() }

        val send = async { session.sendResource(payload, timeoutMs = 30_000) }
        testScheduler.runCurrent()

        val advParsed = parsePacket(sentPackets.first())!!
        val advPlain = tokenCrypto.decryptWithDerivedKey(advParsed.payload, link.derivedKey!!)
        val adv = ResourceAdvertisement.parse(advPlain, link.linkId!!)
        val advHash = adv.hash
        // hashmap[i] = 4-byte hash of chunk i. Build a REQ asking for
        // every chunk — same shape our own LinkResourceReceiver emits
        // on advertisement receipt.
        val hashmapBytes = ByteArray(adv.hashmap.sumOf { it.size })
        var off = 0
        for (entry in adv.hashmap) {
            entry.copyInto(hashmapBytes, off)
            off += entry.size
        }
        val reqBody = ByteArray(1 + advHash.size + hashmapBytes.size).also {
            it[0] = 0x00  // HASHMAP_IS_NOT_EXHAUSTED
            advHash.copyInto(it, 1)
            hashmapBytes.copyInto(it, 1 + advHash.size)
        }
        val reqCipher = tokenCrypto.encryptWithDerivedKey(reqBody, link.derivedKey!!)
        val reqPacket = buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_DATA,
            destHash   = link.linkId!!,
            context    = CTX_RESOURCE_REQ,
            payload    = reqCipher,
        )

        // Snapshot the count BEFORE delivering the REQ so we can isolate
        // what the REQ caused.
        val beforeReq = sentPackets.size
        session.handlePacket(parsePacket(reqPacket)!!)
        testScheduler.runCurrent()

        val emittedInResponse = sentPackets.size - beforeReq
        assertEquals(adv.hashmap.size, emittedInResponse,
            "REQ for all ${adv.hashmap.size} parts must produce exactly ${adv.hashmap.size} chunks back, " +
                "got $emittedInResponse")

        // Every emitted chunk must be CTX_RESOURCE / DEST_LINK / link_id.
        for (raw in sentPackets.drop(beforeReq)) {
            val p = parsePacket(raw)!!
            assertEquals(CTX_RESOURCE, p.context, "chunk emitted in response to REQ must be CTX_RESOURCE")
            assertEquals(DEST_LINK, p.destType, "chunk dest_type must be LINK")
            assertContentEquals(link.linkId, p.destHash, "chunk must target link_id")
        }

        // Now deliver the PRF so the test exits cleanly without
        // exercising the timeout path.
        val prfPayload = ByteArray(64).also {
            advHash.copyInto(it, 0)
            val proofInput = ByteArray(payload.size + advHash.size).also { buf ->
                payload.copyInto(buf, 0)
                advHash.copyInto(buf, payload.size)
            }
            kotlinx.coroutines.runBlocking { TestVectors.crypto.sha256(proofInput) }.copyInto(it, 32)
        }
        val prfPacket = buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_PROOF,
            destHash   = link.linkId!!,
            context    = CTX_RESOURCE_PRF,
            payload    = prfPayload,
        )
        session.handlePacket(parsePacket(prfPacket)!!)
        testScheduler.runCurrent()

        assertEquals(true, send.await(),
            "PRF after REQ-driven chunk emit must resolve sendResource → true")
    }

    @Test fun `handleResourceReq resets emit cap on decreasing requestedCount — forward-progress signal`() = runTest {
        // Observed 2026-05-24: image send over RNode/LoRa fails and
        // drops to opportunistic (text-only). Root cause in the logs:
        //
        //   ← RESOURCE_REQ for 39 parts → 39 chunks sent     (chunksSent=39)
        //   ← RESOURCE_REQ for 39 parts → 39 chunks sent     (chunksSent=78)
        //   ← RESOURCE_REQ for 39 parts → 39 chunks sent     (chunksSent=117 = cap)
        //   ← RESOURCE_REQ for 27 parts → "emit cap reached"  ← peer was converging!
        //   ← RESOURCE_REQ for 15 parts → "emit cap reached"
        //   msg #N: ✗ link DATA proof timeout — falling back to opportunistic
        //
        // The peer was making forward progress (39 → 27 → 15 missing
        // parts as our re-sends got through), but the 3× cap fires at
        // 117 and refuses the last two rounds. Link times out, image
        // is stripped on the opportunistic fallback. The cap exists
        // to stop a malicious receiver REPLAYING the same REQ — a
        // converging receiver (strictly decreasing requestedCount)
        // is the opposite signal.
        //
        // Fix: when requestedCount < lastRequestedCount, the peer is
        // making progress — reset the per-segment emission counter.
        // Identical-or-increasing REQ counts still accumulate toward
        // the cap (the replay-attack defense).
        val (session, link, sentPackets) = newActiveLinkSession()
        val tokenCrypto = TokenCrypto(TestVectors.crypto)
        val payload = ByteArray(2_000) { (it * 17).toByte() }

        val send = async { session.sendResource(payload, timeoutMs = 60_000) }
        testScheduler.runCurrent()

        val advParsed = parsePacket(sentPackets.first())!!
        val advPlain = tokenCrypto.decryptWithDerivedKey(advParsed.payload, link.derivedKey!!)
        val adv = ResourceAdvertisement.parse(advPlain, link.linkId!!)
        val advHash = adv.hash
        val nParts = adv.hashmap.size
        assertTrue(nParts >= 2, "test needs ≥2 parts; got $nParts")

        suspend fun buildReq(parts: List<ByteArray>): ByteArray {
            val hashmapBytes = ByteArray(parts.sumOf { it.size })
            var off = 0
            for (entry in parts) { entry.copyInto(hashmapBytes, off); off += entry.size }
            val reqBody = ByteArray(1 + advHash.size + hashmapBytes.size).also {
                it[0] = 0x00
                advHash.copyInto(it, 1)
                hashmapBytes.copyInto(it, 1 + advHash.size)
            }
            val reqCipher = tokenCrypto.encryptWithDerivedKey(reqBody, link.derivedKey!!)
            return buildPacket(
                destType   = DEST_LINK,
                packetType = PACKET_DATA,
                destHash   = link.linkId!!,
                context    = CTX_RESOURCE_REQ,
                payload    = reqCipher,
            )
        }

        // Phase 1 — 3 full-count REQs each for all N parts. After this
        // chunksSentThisSegment = 3N, which equals the 3× cap exactly.
        repeat(3) {
            session.handlePacket(parsePacket(buildReq(adv.hashmap))!!)
            testScheduler.runCurrent()
        }

        // Phase 2 — receiver has now got SOMETHING through and asks
        // for only 1 missing part. Pre-fix: cap exhausted, 0 chunks
        // emitted, link timeout. Post-fix: cap resets on decrease, 1
        // chunk emitted.
        val beforeConverge = sentPackets.size
        val converged = adv.hashmap.take(1)
        session.handlePacket(parsePacket(buildReq(converged))!!)
        testScheduler.runCurrent()

        val emittedAfterConverge = sentPackets.size - beforeConverge
        assertEquals(converged.size, emittedAfterConverge,
            "after 3 cap-exhausting REQs, a smaller REQ (peer converging) must still be served — " +
                "expected ${converged.size} chunk(s), got $emittedAfterConverge. " +
                "Without the reset, the link send times out and the image drops to opportunistic.")

        // Sanity: every chunk emitted in Phase 2 is properly framed.
        for (raw in sentPackets.drop(beforeConverge)) {
            val p = parsePacket(raw)!!
            assertEquals(CTX_RESOURCE, p.context)
            assertEquals(DEST_LINK, p.destType)
            assertContentEquals(link.linkId, p.destHash)
        }

        // Deliver a PRF so the sendResource coroutine exits cleanly
        // (otherwise runTest reports an undispatched coroutine).
        val prfPayload = ByteArray(64).also {
            advHash.copyInto(it, 0)
            val proofInput = ByteArray(payload.size + advHash.size).also { buf ->
                payload.copyInto(buf, 0)
                advHash.copyInto(buf, payload.size)
            }
            kotlinx.coroutines.runBlocking { TestVectors.crypto.sha256(proofInput) }.copyInto(it, 32)
        }
        val prfPacket = buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_PROOF,
            destHash   = link.linkId!!,
            context    = CTX_RESOURCE_PRF,
            payload    = prfPayload,
        )
        session.handlePacket(parsePacket(prfPacket)!!)
        testScheduler.runCurrent()
        assertEquals(true, send.await())
    }

    @Test fun `handleResourceReq enforces cap when requestedCount does NOT decrease — replay defense`() = runTest {
        // Twin of the previous test: confirms the cap is still
        // enforced when the receiver replays the SAME REQ over and
        // over (the original audit-M2 concern — a peer abusing the
        // link to drain our retransmission bandwidth indefinitely).
        // After 3 identical full-count REQs the next identical REQ
        // gets 0 chunks back.
        val (session, link, sentPackets) = newActiveLinkSession()
        val tokenCrypto = TokenCrypto(TestVectors.crypto)
        val payload = ByteArray(2_000) { (it * 19).toByte() }

        val send = async { session.sendResource(payload, timeoutMs = 60_000) }
        testScheduler.runCurrent()

        val advParsed = parsePacket(sentPackets.first())!!
        val advPlain = tokenCrypto.decryptWithDerivedKey(advParsed.payload, link.derivedKey!!)
        val adv = ResourceAdvertisement.parse(advPlain, link.linkId!!)
        val advHash = adv.hash
        val nParts = adv.hashmap.size

        val hashmapBytes = ByteArray(adv.hashmap.sumOf { it.size })
        var off = 0
        for (entry in adv.hashmap) { entry.copyInto(hashmapBytes, off); off += entry.size }
        val reqBody = ByteArray(1 + advHash.size + hashmapBytes.size).also {
            it[0] = 0x00
            advHash.copyInto(it, 1)
            hashmapBytes.copyInto(it, 1 + advHash.size)
        }
        val reqCipher = tokenCrypto.encryptWithDerivedKey(reqBody, link.derivedKey!!)
        val reqPacket = buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_DATA,
            destHash   = link.linkId!!,
            context    = CTX_RESOURCE_REQ,
            payload    = reqCipher,
        )

        // 3 identical REQs → 3N emissions (cap = 3N exactly).
        repeat(3) {
            session.handlePacket(parsePacket(reqPacket)!!)
            testScheduler.runCurrent()
        }

        val beforeReplay = sentPackets.size
        session.handlePacket(parsePacket(reqPacket)!!)
        testScheduler.runCurrent()
        val emitted = sentPackets.size - beforeReplay
        assertEquals(0, emitted,
            "4th identical REQ (no decrease in requestedCount) must hit the cap — " +
                "expected 0 chunks, got $emitted. " +
                "Without this defense a malicious receiver could pin us to unbounded retransmission.")

        // Wind down. The cap-exhausted resource will time out on its
        // own virtual deadline; advance past it.
        testScheduler.advanceTimeBy(61_000L)
        testScheduler.runCurrent()
        assertEquals(false, send.await(),
            "cap-exhausted resource with no PRF → sendResource returns false on timeout")
    }

    @Test fun `sendResource returns true when matching CTX_RESOURCE_PRF arrives`() = runTest {
        val (session, link, sentPackets) = newActiveLinkSession()
        val tokenCrypto = TokenCrypto(TestVectors.crypto)
        val payload = "an image-shaped payload that crosses chunk boundaries".encodeToByteArray() +
                       ByteArray(1500) { (it % 251).toByte() }

        val send = async { session.sendResource(payload, timeoutMs = 30_000) }
        testScheduler.runCurrent()

        // Decrypt the emitted ADV to recover the integrity hash we need
        // to echo back in the PRF.
        val advParsed = parsePacket(sentPackets.first())!!
        val advPlain = tokenCrypto.decryptWithDerivedKey(advParsed.payload, link.derivedKey!!)
        val adv = ResourceAdvertisement.parse(advPlain, link.linkId!!)

        // §10.5 PRF payload: adv.h(32) || sha256(plain || adv.h)(32).
        // The sender doesn't verify the second 32 bytes (it doesn't keep
        // the plaintext around after send), so we can supply any 32 bytes
        // there. Construct minimally.
        val prfPayload = ByteArray(64).also {
            adv.hash.copyInto(it, 0)
            // second half = sha256(payload || adv.h) is what a real
            // receiver computes; supply it for completeness.
            val proofInput = ByteArray(payload.size + adv.hash.size).also { buf ->
                payload.copyInto(buf, 0)
                adv.hash.copyInto(buf, payload.size)
            }
            kotlinx.coroutines.runBlocking { TestVectors.crypto.sha256(proofInput) }.copyInto(it, 32)
        }
        val prfPacket = buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_PROOF,
            destHash   = link.linkId!!,
            context    = CTX_RESOURCE_PRF,
            payload    = prfPayload,
        )
        session.handlePacket(parsePacket(prfPacket)!!)
        testScheduler.runCurrent()

        assertEquals(true, send.await(), "matching PRF must resolve sendResource → true")
    }

    /**
     * v1.1.16 regression pin. Phase 1 (commit 92e937b) added a
     * PACKET_PROOF dispatch in [LinkSession.handlePacket] that
     * early-returned for any context not in {CTX_NONE, CTX_RESOURCE_PRF}
     * — including CTX_LRPROOF (the link-establishment proof). The
     * `when (pkt.context) { CTX_LRPROOF -> link.validateProof ... }`
     * block below it never ran, so initiator-side awaitProof never
     * completed, so every link send in v1.1.15 burned its full retry
     * budget (~4m30s for text, ~90s for image post-budget-cut). The
     * fix lets CTX_LRPROOF fall through to the existing handler. This
     * test pins the dispatch so a future refactor of the PACKET_PROOF
     * routing can't quietly re-introduce the same bug.
     *
     * Symptom in the wild: `→ LRPROOF for ... (responder, on Tcp)`
     * (we sent one as responder) paired with our own initiator-side
     * `✗ no LRPROOF within 45s` — same code path on both endpoints,
     * working in one direction (responder emit) and broken in the
     * other (initiator validate).
     */
    @Test fun `handlePacket routes CTX_LRPROOF to validateProof (v1_1_16 regression pin)`() = runTest {
        val crypto = TestVectors.crypto

        // Build an initiator-side Link via the real createInitiator
        // flow. The synthetic newActiveLinkSession() factory can't be
        // reused here because it skips the LRPROOF handshake — we
        // need a Link in PENDING state with isInitiator=true so
        // validateProof actually runs.
        val (initiator, requestData) = io.github.thatsfguy.reticulum.link.Link.createInitiator(
            peerLongTermSigPub = TestVectors.Bob.sigPub,
            peerDestHash       = TestVectors.Bob.destHash,
            crypto             = crypto,
            nowMs              = 1_700_000_000_000L,
        )
        val reqPkt = parsePacket(buildPacket(
            packetType = io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ,
            destHash   = TestVectors.Bob.destHash,
            payload    = requestData,
        ))!!
        initiator.setLinkIdFromPacket(reqPkt)
        val linkId = initiator.linkId!!

        // Build a valid 96-byte LRPROOF using Bob's long-term sig key,
        // mirroring LinkTest.validateProof_acceptsNoSignallingLrProof_96B
        // — that's the wire format the responder emits when it has no
        // signalling in the LRREQ body.
        val responderXPriv = crypto.generateX25519PrivateKey()
        val responderXPub  = crypto.x25519PublicKey(responderXPriv)
        val signedData = ByteArray(linkId.size + responderXPub.size + TestVectors.Bob.sigPub.size).also {
            linkId.copyInto(it, 0)
            responderXPub.copyInto(it, linkId.size)
            TestVectors.Bob.sigPub.copyInto(it, linkId.size + responderXPub.size)
        }
        val signature = crypto.ed25519Sign(signedData, TestVectors.Bob.sigPriv)
        val proofBody = ByteArray(signature.size + responderXPub.size).also {
            signature.copyInto(it, 0)
            responderXPub.copyInto(it, signature.size)
        }

        val session = LinkSession(
            link = initiator,
            crypto = crypto,
            sender = {},
            nowMs = { 1_700_000_001_000L },
            logger = {},
        )

        // Start awaiting the proof; runCurrent runs the deferred-setup
        // path up to its first suspension without advancing past the
        // timeout.
        val awaitJob = async { session.awaitProof(timeoutMs = 5_000L) }
        testScheduler.runCurrent()

        val lrproofPkt = parsePacket(buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_PROOF,
            destHash   = linkId,
            context    = io.github.thatsfguy.reticulum.protocol.CTX_LRPROOF,
            payload    = proofBody,
        ))!!
        session.handlePacket(lrproofPkt)
        testScheduler.runCurrent()

        val result = awaitJob.await()
        // The critical assertion: the deferred resolved with a real
        // ProofResult (Validated or Invalid) — NOT a Timeout. A Timeout
        // means the dispatch swallowed the LRPROOF before validateProof
        // ran, which is exactly the bug this test is pinning against.
        assertTrue(result !is LinkSession.ProofResult.Timeout,
            "CTX_LRPROOF was routed to the unhandled-PROOF branch (got Timeout) — " +
                "the PACKET_PROOF dispatch must let CTX_LRPROOF fall through to validateProof. " +
                "Same bug as v1.1.15 → v1.1.16 fix.")
        // Validated is the expected happy-path outcome since we built a
        // real LRPROOF; if validateProof drifts, Invalid is at least
        // ROUTED-TO and acceptable for this test's narrow purpose.
        assertTrue(result is LinkSession.ProofResult.Validated,
            "Expected Validated since the LRPROOF was constructed correctly; got $result")
    }

    @Test fun `sendResource ignores CTX_RESOURCE_PRF whose adv hash doesn't match`() = runTest {
        val (session, link, sentPackets) = newActiveLinkSession()
        val payload = "short".encodeToByteArray()

        val send = async { session.sendResource(payload, timeoutMs = 100) }
        testScheduler.runCurrent()

        // Deliver a PRF carrying a completely unrelated 32-byte hash —
        // the sender must NOT treat it as proof.
        val wrongPrfPayload = ByteArray(64).also {
            for (i in 0 until 32) it[i] = 0xFF.toByte()
            for (i in 32 until 64) it[i] = 0xEE.toByte()
        }
        val wrongPrf = buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_PROOF,
            destHash   = link.linkId!!,
            context    = CTX_RESOURCE_PRF,
            payload    = wrongPrfPayload,
        )
        session.handlePacket(parsePacket(wrongPrf)!!)
        testScheduler.runCurrent()

        // Should still time out — wrong PRF didn't satisfy the sender.
        assertEquals(false, send.await(), "PRF with mismatched adv hash must not resolve sendResource")
        // Sanity: at least one packet was emitted before the wait.
        assertTrue(sentPackets.isNotEmpty(), "sendResource emitted at least the ADV before awaiting")
    }

    // ---- §6.7 KEEPALIVE -----------------------------------------------
    //
    // v1.1.21 added initiator-side KEEPALIVE so outbound links don't
    // tear down at the responder's 360 s stale threshold. The tests
    // here pin the wire-format details (single 0xFF body byte, CLEARTEXT
    // per §6.7.1 — NOT Token-encrypted — context = CTX_KEEPALIVE,
    // DEST_LINK / link_id dest) and the lifecycle (lastRxAt refresh
    // suppresses ping; dispose cancels the loop).

    @Test fun `startKeepalive emits a cleartext 0xFF ping after the keepalive window`() = runTest {
        val (session, link, sentPackets) = newActiveLinkSession()

        session.startKeepalive(this)
        // No inbound traffic ever, so lastRxAt stays at its sentinel
        // (-1). The loop's `if (lastRxAt > 0)` guard returns the full
        // keepaliveMs (= 360 s default since rtt is 0), so the first
        // ping won't fire until 360 s of virtual time elapses. Advance
        // enough to get past the first emission window.
        testScheduler.advanceTimeBy(361_000L)
        testScheduler.runCurrent()

        // Stop the loop so the test exits cleanly under runTest's
        // structured-concurrency check.
        session.stopKeepalive()

        assertTrue(sentPackets.isNotEmpty(), "no KEEPALIVE ping emitted")
        val pingParsed = parsePacket(sentPackets.first())!!
        assertEquals(PACKET_DATA, pingParsed.packetType, "ping is DATA")
        assertEquals(
            io.github.thatsfguy.reticulum.protocol.CTX_KEEPALIVE, pingParsed.context,
            "ping context must be CTX_KEEPALIVE (0xFA)",
        )
        assertEquals(DEST_LINK, pingParsed.destType,
            "§12.5.2: link-addressed → DEST_LINK so the relay routes via link_table")
        assertContentEquals(link.linkId, pingParsed.destHash, "ping must target link_id")

        // §6.7.1: KEEPALIVE is NOT Token-encrypted — the body is the bare
        // single 0xFF sentinel byte in the clear. (Token-encrypting it made
        // upstream RNS peers ignore the ping — they match on
        // `data == bytes([0xFF])` — so they never ponged; live-found vs
        // Sideband 2026-06-24, masked mobile↔mobile by symmetric crypto.)
        assertContentEquals(byteArrayOf(0xFF.toByte()), pingParsed.payload,
            "ping body must be a bare cleartext 0xFF")
    }

    @Test fun `startKeepalive is idempotent`() = runTest {
        val (session, _, _) = newActiveLinkSession()
        session.startKeepalive(this)
        // Second call must not spawn a second loop.
        session.startKeepalive(this)
        session.stopKeepalive()
        // No assertion beyond "doesn't throw or hang" — if the second
        // start spawned a duplicate, the test would either see double
        // pings post-advance OR the stop wouldn't fully cancel.
    }

    @Test fun `keepalive throttles by self-emit time when no pong arrives — v1_1_22 fix`() = runTest {
        // Observed on-device 2026-05-13: outbound link to a Sideband
        // peer behind a transit relay that dropped pongs spawned a
        // ping every ~30s instead of ~360s. Root cause was throttling
        // by lastRxAt alone — pure-inbound clock never advanced, so
        // every post-ping loop iteration saw "stale enough, ping
        // again" and the inner 30s grace delay set the cadence. Fix
        // (this test pins it): throttle by max(lastRxAt,
        // lastKeepaliveSentAt) so our own emit also counts as a
        // cadence anchor.
        //
        // The test rig's `nowMs` is a fixed constant (the existing
        // newActiveLinkSession factory doesn't track virtual time), so
        // we can't observe a second-ping firing on the far side of
        // another keepalive window. What we CAN observe — and what
        // the on-device bug actually surfaced — is that no second
        // ping fires soon after the first. Pre-fix this would be
        // ~30s; post-fix lastKeepaliveSentAt suppresses every short-
        // window re-ping.
        val (session, _, sentPackets) = newActiveLinkSession()
        session.startKeepalive(this)

        // Advance past the first keepalive window — one ping fires.
        testScheduler.advanceTimeBy(361_000L)
        testScheduler.runCurrent()
        val countAfterFirst = sentPackets.size
        assertTrue(countAfterFirst >= 1,
            "first ping should fire after ~360s (idle from sentinel-init)")

        // Advance ANOTHER 60s without any inbound. Pre-v1.1.22 would
        // re-ping at ~30s after the first one (2x within this
        // window). The fix throttles to ~one per 360s so no NEW ping
        // fires here.
        testScheduler.advanceTimeBy(60_000L)
        testScheduler.runCurrent()
        assertEquals(countAfterFirst, sentPackets.size,
            "with no pong arriving, a second ping must NOT fire 60s after the first — " +
                "throttle by lastKeepaliveSentAt prevents the 30s ping spam observed in v1.1.21")

        session.stopKeepalive()
    }

    @Test fun `dispose cancels the keepalive loop`() = runTest {
        val (session, _, sentPackets) = newActiveLinkSession()
        session.startKeepalive(this)
        // Dispose before the first window completes — should cancel
        // cleanly. Advance time past the would-be first ping and
        // assert no packet was emitted.
        session.dispose()
        testScheduler.advanceTimeBy(400_000L)
        testScheduler.runCurrent()
        assertEquals(0, sentPackets.size,
            "dispose() must cancel the keepalive loop — got ${sentPackets.size} packets")
    }

    private fun expectedRequestIdOf(sentPacket: ByteArray, @Suppress("UNUSED_PARAMETER") link: Link): ByteArray {
        val parsed = parsePacket(sentPacket)!!
        return kotlinx.coroutines.runBlocking {
            io.github.thatsfguy.reticulum.link.computePacketFullHash(parsed, TestVectors.crypto)
                .copyOfRange(0, 16)
        }
    }

    private data class TestRig(
        val session: LinkSession,
        val link: Link,
        val sentPackets: MutableList<ByteArray>,
    )

    /**
     * Build a LinkSession with a synthetic active link. The link skips the
     * full LRPROOF handshake by setting linkId + derivedKey + state directly
     * (these fields are `internal set` so the test from inside the same
     * module can reach them). The derived key is HKDF over a fixed
     * known input so encryption is deterministic across runs.
     */
    // ---- generic CTX_NONE link DATA (the RRC path) --------------------

    @Test fun `inbound CTX_NONE link DATA is decrypted, delivered to onLinkData, and proofed`() = runTest {
        val received = CompletableDeferred<ByteArray>()
        val identity = io.github.thatsfguy.reticulum.crypto.Identity(TestVectors.crypto).apply {
            loadFromPrivateKeys(TestVectors.Alice.encPriv, TestVectors.Alice.sigPriv)
        }
        val (session, link, sentPackets) = newActiveLinkSession(
            ourIdentity = identity,
            onLinkData = { received.complete(it) },
        )
        val tokenCrypto = TokenCrypto(TestVectors.crypto)
        val plaintext = "rrc hub frame".encodeToByteArray()
        val packet = buildPacket(
            destType = DEST_LINK,
            packetType = PACKET_DATA,
            destHash = link.linkId!!,
            context = io.github.thatsfguy.reticulum.protocol.CTX_NONE,
            payload = tokenCrypto.encryptWithDerivedKey(plaintext, link.derivedKey!!),
        )
        session.handlePacket(parsePacket(packet)!!)

        assertContentEquals(plaintext, received.await(), "onLinkData must get the decrypted plaintext")
        // Exactly one §6.5 receipt: PACKET_PROOF, CTX_NONE, 96-byte payload.
        assertEquals(1, sentPackets.size, "one PROOF receipt expected")
        val proof = parsePacket(sentPackets[0])!!
        assertEquals(PACKET_PROOF, proof.packetType)
        assertEquals(96, proof.payload.size, "explicit §6.5.1 proof is hash(32)+sig(64)")
        assertContentEquals(link.linkId, proof.destHash)
    }

    @Test fun `sendData emits encrypted CTX_NONE link DATA addressed to the link_id`() = runTest {
        val (session, link, sentPackets) = newActiveLinkSession()
        val plaintext = "outbound rrc frame".encodeToByteArray()
        session.sendData(plaintext)

        assertEquals(1, sentPackets.size)
        val parsed = parsePacket(sentPackets[0])!!
        assertEquals(PACKET_DATA, parsed.packetType)
        assertEquals(io.github.thatsfguy.reticulum.protocol.CTX_NONE, parsed.context)
        assertContentEquals(link.linkId, parsed.destHash)
        val tokenCrypto = TokenCrypto(TestVectors.crypto)
        assertContentEquals(
            plaintext,
            tokenCrypto.decryptWithDerivedKey(parsed.payload, link.derivedKey!!),
        )
    }

    private fun TestScope.newActiveLinkSession(
        ourIdentity: io.github.thatsfguy.reticulum.crypto.Identity? = null,
        onLinkData: (suspend (ByteArray) -> Unit)? = null,
    ): TestRig {
        val sentPackets = mutableListOf<ByteArray>()
        val crypto = TestVectors.crypto

        val link = Link(crypto)
        link.linkId = ByteArray(16) { (it + 0xa0).toByte() }
        // Use a fixed pseudo-shared-secret so the test is reproducible.
        // Real link uses ECDH(ourEph, peerEph). For tests we just need a
        // consistent 32 input to HKDF.
        val pseudoShared = ByteArray(32) { (it + 0xc0).toByte() }
        // Compute HKDF synchronously via runBlocking — wraps the suspend
        // call so the test setup isn't itself a coroutine.
        link.derivedKey = kotlinx.coroutines.runBlocking {
            crypto.hkdfDerive(pseudoShared, link.linkId!!, ByteArray(0), 64)
        }
        link.state = LinkState.ACTIVE

        val session = LinkSession(
            link = link,
            crypto = crypto,
            sender = { packet -> sentPackets.add(packet) },
            nowMs = { 1_700_000_000_000L },
            logger = { },
            ourIdentity = ourIdentity,
            onLinkData = onLinkData,
        )
        return TestRig(session, link, sentPackets)
    }
}
