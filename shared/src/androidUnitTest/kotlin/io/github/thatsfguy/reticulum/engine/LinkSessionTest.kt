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

    @Test fun `sendResource emits ADV plus N chunks all addressed to link_id with DEST_LINK`() = runTest {
        val (session, link, sentPackets) = newActiveLinkSession()
        val payload = ByteArray(2_000) { (it % 251).toByte() }

        val send = async { session.sendResource(payload, timeoutMs = 5_000) }
        testScheduler.runCurrent()

        // First packet must be the ADV.
        assertTrue(sentPackets.size >= 2, "expected ADV + at least 1 chunk, got ${sentPackets.size}")
        val advParsed = parsePacket(sentPackets.first())!!
        assertEquals(PACKET_DATA, advParsed.packetType, "ADV is DATA")
        assertEquals(CTX_RESOURCE_ADV, advParsed.context, "first packet must be CTX_RESOURCE_ADV")
        assertEquals(DEST_LINK, advParsed.destType, "spec §12.5.2: link-addressed → DEST_LINK")
        assertContentEquals(link.linkId, advParsed.destHash, "ADV must target link_id")

        // Remaining packets must be chunks.
        for (raw in sentPackets.drop(1)) {
            val p = parsePacket(raw)!!
            assertEquals(PACKET_DATA, p.packetType, "chunks are DATA")
            assertEquals(CTX_RESOURCE, p.context, "chunks use CTX_RESOURCE")
            assertEquals(DEST_LINK, p.destType, "chunk dest_type must be LINK")
            assertContentEquals(link.linkId, p.destHash, "chunk must target link_id")
        }

        // Let the send time out (we never deliver a PRF here).
        assertEquals(false, send.await(), "no PRF delivered → sendResource returns false")
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
    private fun TestScope.newActiveLinkSession(): TestRig {
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
        )
        return TestRig(session, link, sentPackets)
    }
}
