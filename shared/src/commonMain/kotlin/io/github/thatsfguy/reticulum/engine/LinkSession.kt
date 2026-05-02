package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.LinkState
import io.github.thatsfguy.reticulum.protocol.CTX_LRPROOF
import io.github.thatsfguy.reticulum.protocol.CTX_LRRTT
import io.github.thatsfguy.reticulum.protocol.CTX_REQUEST
import io.github.thatsfguy.reticulum.protocol.CTX_RESPONSE
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.Packet
import io.github.thatsfguy.reticulum.protocol.buildPacket
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
     * Send a REQUEST packet for [pathHash] (the SHA-256 of a path string
     * like ":/page/index.mu"; full 32 bytes) and suspend until the
     * matching RESPONSE arrives or [timeoutMs] elapses.
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
        require(pathHash.size == 32) { "pathHash must be 32 bytes (SHA-256), got ${pathHash.size}" }

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
    suspend fun handlePacket(pkt: Packet) {
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

            CTX_RESPONSE -> {
                val plain = runCatching {
                    tokenCrypto.decryptWithDerivedKey(pkt.payload, link.derivedKey!!)
                }.onFailure { logger("RESPONSE decrypt failed: ${it.message}") }.getOrNull() ?: return
                val decoded = runCatching { MessagePack.decode(plain) }
                    .onFailure { logger("RESPONSE msgpack decode failed: ${it.message}") }
                    .getOrNull()
                if (decoded is List<*> && decoded.size >= 2) {
                    val body = decoded[1]
                    val bytes = when (body) {
                        is ByteArray -> body
                        is String    -> body.encodeToByteArray()
                        else         -> ByteArray(0)
                    }
                    responseDeferred?.complete(bytes)
                } else {
                    logger("RESPONSE shape unexpected: ${decoded?.let { it::class.simpleName }}")
                }
            }

            // Other contexts (KEEPALIVE, LINKCLOSE, RESOURCE_*, etc.) are
            // not yet exercised by the page-fetch flow. Silently ignore.
            else -> Unit
        }
    }
}

