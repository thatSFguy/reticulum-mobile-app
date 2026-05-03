package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.LinkState
import io.github.thatsfguy.reticulum.protocol.CTX_LRPROOF
import io.github.thatsfguy.reticulum.protocol.CTX_LRRTT
import io.github.thatsfguy.reticulum.protocol.CTX_REQUEST
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_ADV
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_PRF
import io.github.thatsfguy.reticulum.protocol.CTX_RESPONSE
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.Packet
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.resource.Resource
import io.github.thatsfguy.reticulum.resource.ResourceAdvertisement
import io.github.thatsfguy.reticulum.resource.ResourceError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Common surface used by the engine pump to dispatch incoming packets to
 * either the initiator-side ([LinkSession]) or responder-side
 * ([io.github.thatsfguy.reticulum.engine.ResponderLinkSession]) driver
 * for an active link, keyed in the engine by link_id hex.
 */
interface LinkPump {
    suspend fun handlePacket(pkt: Packet)
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

    /** Active inbound resource the responder is delivering across CTX_RESOURCE
     *  packets. Set on CTX_RESOURCE_ADV, populated chunk-by-chunk on
     *  CTX_RESOURCE, finalized by [finalizeResource]. */
    private var pendingResource: Resource? = null

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
     * SHA-256 over the path string (e.g. `SHA256(":/page/index.mu")[:16]`).
     * Servers key their `request_handlers` dict on this 16-byte hash; a
     * 32-byte hash never matches, and a too-short hash collides too easily.
     *
     * Returns the raw response body bytes (typically UTF-8 micron text)
     * or null on timeout.
     */
    suspend fun request(
        pathHash: ByteArray,
        body: ByteArray = ByteArray(0),
        timeoutMs: Long,
    ): ByteArray? {
        check(link.state == LinkState.ACTIVE) { "Link not active (state=${link.state})" }
        require(pathHash.size == 16) {
            "pathHash must be 16 bytes per spec §11.1 (SHA-256(path)[:16]), got ${pathHash.size}"
        }

        val plaintext = MessagePack.encode(listOf(nowMs() / 1000.0, pathHash, body))
        val ciphertext = tokenCrypto.encryptWithDerivedKey(plaintext, link.derivedKey!!)
        val packet = buildPacket(
            packetType = PACKET_DATA,
            destHash = link.linkId!!,
            context = CTX_REQUEST,
            payload = ciphertext,
        )

        val d = CompletableDeferred<ByteArray>().also { responseDeferred = it }
        sender(packet)
        return try {
            withTimeout(timeoutMs) { d.await() }
        } catch (_: TimeoutCancellationException) {
            null
        } finally {
            responseDeferred = null
        }
    }

    /**
     * Engine pump → session entry point. Called whenever an inbound
     * packet's destHash matches this session's link_id.
     */
    override suspend fun handlePacket(pkt: Packet) {
        logger("session rx ctx=0x${pkt.context.toString(16).padStart(2, '0')} payload=${pkt.payload.size}B")
        when (pkt.context) {
            CTX_LRPROOF -> {
                val res = link.validateProof(pkt.payload, nowMs())
                when (res) {
                    is Link.LrProofResult.Success -> {
                        // LRRTT confirmation packet — caller doesn't await it.
                        val rttPkt = buildPacket(
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

            CTX_RESOURCE_ADV -> {
                val plain = runCatching {
                    tokenCrypto.decryptWithDerivedKey(pkt.payload, link.derivedKey!!)
                }.onFailure { logger("RESOURCE_ADV decrypt failed: ${it.message}") }.getOrNull() ?: return
                val adv = runCatching { ResourceAdvertisement.parse(plain, link.linkId!!) }
                    .onFailure { logger("RESOURCE_ADV parse failed: ${it.message}") }
                    .getOrNull() ?: run {
                        // Bail the awaiting request — there's no recoverable path.
                        responseDeferred?.complete(ByteArray(0))
                        return
                    }
                logger("RESOURCE_ADV t=${adv.transferSize}B parts=${adv.totalParts} compressed=${adv.compressed} encrypted=${adv.encrypted}")
                pendingResource = Resource(
                    advertisement = adv,
                    link = tokenCrypto,
                    linkKey = link.derivedKey!!,
                )
            }

            CTX_RESOURCE -> {
                val res = pendingResource ?: run {
                    logger("RESOURCE chunk arrived without prior ADV — dropping")
                    return
                }
                val plain = runCatching {
                    tokenCrypto.decryptWithDerivedKey(pkt.payload, link.derivedKey!!)
                }.onFailure { logger("RESOURCE chunk decrypt failed: ${it.message}") }.getOrNull() ?: return
                val accepted = runCatching { res.receivePart(plain, crypto) }
                    .onFailure { logger("receivePart threw: ${it.message}") }
                    .getOrDefault(false)
                if (!accepted) {
                    logger("RESOURCE chunk did not match any hashmap slot")
                    return
                }
                if (res.isComplete) finalizeResource(res)
            }

            CTX_RESPONSE -> {
                val plain = runCatching {
                    tokenCrypto.decryptWithDerivedKey(pkt.payload, link.derivedKey!!)
                }.onFailure { logger("RESPONSE decrypt failed: ${it.message}") }.getOrNull() ?: return
                val decoded = runCatching { MessagePack.decode(plain) }
                    .onFailure { logger("RESPONSE msgpack decode failed: ${it.message}") }
                    .getOrNull()
                if (decoded is List<*> && decoded.size >= 2) {
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
                } else {
                    logger("RESPONSE shape unexpected: ${decoded?.let { it::class.simpleName }}")
                }
            }

            // Other contexts (KEEPALIVE, LINKCLOSE, RESOURCE_REQ/HMU, etc.)
            // are not yet exercised by the page-fetch / propagation flow.
            // Silently ignore.
            else -> Unit
        }
    }

    /**
     * Run reassembly + integrity + proof emit on a completed inbound
     * resource, then resolve the awaiting [responseDeferred] with the
     * delivered bytes.
     *
     * Outer-decrypted plaintext for a request/response resource is a
     * msgpack `[request_id(16), response_data]` envelope — same shape
     * single-packet RESPONSE produces. We re-msgpack-encode complex
     * response_data so the caller always gets bytes (matching the
     * single-packet path).
     */
    private suspend fun finalizeResource(res: Resource) {
        pendingResource = null
        val plain = runCatching { res.assemble(crypto) }
            .onFailure {
                logger("resource assemble failed: ${it.message}")
                responseDeferred?.complete(ByteArray(0))
            }
            .getOrNull() ?: return

        // PRF emit (mandatory — without it the sender retransmits the
        // whole resource until MAX_RETRIES).
        runCatching {
            val proofPayload = res.buildProofPayload(plain, crypto)
            val proofPacket = buildPacket(
                headerType = HEADER_1,
                destType = DEST_LINK,
                packetType = PACKET_PROOF,
                destHash = link.linkId!!,
                context = CTX_RESOURCE_PRF,
                payload = proofPayload,
            )
            sender(proofPacket)
            logger("→ RESOURCE_PRF (${plain.size}B reassembled)")
        }.onFailure { logger("resource PRF send failed: ${it.message}") }

        // Deliver to the awaiting request like a single-packet RESPONSE.
        val decoded = runCatching { MessagePack.decode(plain) }
            .onFailure { logger("resource msgpack decode failed: ${it.message}") }
            .getOrNull()
        if (decoded is List<*> && decoded.size >= 2) {
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
}

