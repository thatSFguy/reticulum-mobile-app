package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.LinkState
import io.github.thatsfguy.reticulum.protocol.CTX_REQUEST
import io.github.thatsfguy.reticulum.protocol.CTX_RESPONSE
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
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
        val (session, link, _) = newActiveLinkSession()
        val pathHash = ByteArray(16) { (it + 7).toByte() }
        val tokenCrypto = TokenCrypto(TestVectors.crypto)

        // Background: send the request and suspend on the responseDeferred.
        // We must NOT call advanceUntilIdle() here — that would advance
        // virtual time past the 30s timeout, fire withTimeout, and complete
        // the request with null before our hand-crafted response arrives.
        // runCurrent() runs the request's path up to its first suspension
        // (at responseDeferred.await()) without advancing the clock.
        val req = async { session.request(pathHash, ByteArray(0), timeoutMs = 30_000) }
        testScheduler.runCurrent()

        // Construct a CTX_RESPONSE packet carrying [requestId, "page body"].
        // The session's response handler grabs decoded[1] as the body.
        val responsePayload = MessagePack.encode(listOf<Any?>(
            ByteArray(16),  // request id placeholder
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
