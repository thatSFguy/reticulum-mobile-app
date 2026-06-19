package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.transport.hexToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The originator stamp (FIELD_CUSTOM_DATA `0xFC` = the reactor's
 * `source_hash`) is UNAUTHENTICATED. [ReticulumEngine.resolveReactor]
 * MUST:
 *  - honor it ONLY when the reaction arrived via the relay that
 *    delivered the reacted-to message (carrying source ==
 *    target.arrivedViaDest) — else a direct peer could forge a reactor;
 *  - resolve the honored `source_hash` to an IDENTITY hash, never key a
 *    reaction by a destination hash;
 *  - fall back to the carrying source's identity for a spoofed,
 *    unresolvable, or absent stamp.
 *
 * Mirrors the Columba #1006 gate semantics so the two clients agree.
 */
class ResolveReactorTest {

    private val relayDest = "aa".repeat(16)
    private val relayId = "a1".repeat(16)
    private val reactorDest = "bb".repeat(16)
    private val reactorId = "b1".repeat(16)
    private val attackerDest = "cc".repeat(16)
    private val attackerId = "c1".repeat(16)
    private val victimDest = "dd".repeat(16)
    private val msgId = "ee".repeat(32)

    private fun dest(destHex: String, idHex: String) = StoredDestination(
        hash = destHex, identityHash = idHex, publicKey = ByteArray(64),
        destHash = destHex.hexToBytes(), nameHash = ByteArray(0), ratchetPub = null,
        displayName = "x", appName = "lxmf.delivery", appLabel = null, telemetry = null,
        lat = null, lon = null, appDataHex = "", lastSeen = 0, rssi = null,
        favorite = false, source = "test",
    )

    private fun reaction(override: String?) =
        ReactionOrReply.Reaction(reactionTo = msgId, emoji = "👍", reactorOverride = override)

    private fun withEngine(
        body: suspend (ReticulumEngine, InMemoryDestRepo, InMemoryMsgRepo) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val destRepo = InMemoryDestRepo()
        val msgRepo = InMemoryMsgRepo()
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = InMemoryIdentityRepo(),
            destinationRepo = destRepo,
            messageRepo = msgRepo,
            scope = scope,
            nowMs = { 1_700_000_000_000L },
            displayNameProvider = { "test" },
        )
        try {
            body(engine, destRepo, msgRepo)
        } finally {
            scope.cancel()
        }
    }

    /** Persist the reacted-to message as delivered via [relayHex]. */
    private suspend fun saveTarget(msgRepo: InMemoryMsgRepo, relayHex: String) {
        msgRepo.save(
            StoredMessage(
                contactHash = relayHex,
                direction = "incoming",
                content = "hi",
                timestamp = 0,
                messageId = msgId,
                arrivedViaDest = relayHex,
            ),
        )
    }

    @Test fun trustedRelayStampHonoredAndResolvedToIdentity() = withEngine { engine, destRepo, msgRepo ->
        saveTarget(msgRepo, relayDest)
        destRepo.upsertFromAnnounce(dest(reactorDest, reactorId))
        // Arrived via relayDest (== target.arrivedViaDest) → honor the
        // stamp; the stamped source_hash resolves to the reactor identity.
        assertEquals(reactorId, engine.resolveReactor(reaction(reactorDest), relayDest))
    }

    @Test fun spoofedStampOnDirectReactionIgnored() = withEngine { engine, destRepo, msgRepo ->
        saveTarget(msgRepo, relayDest)
        destRepo.upsertFromAnnounce(dest(attackerDest, attackerId))
        // Attacker direct-sends (carrying source attackerDest != relayDest)
        // claiming victimDest reacted. Gate fails → override ignored →
        // attributed to the ACTUAL sender, never the victim.
        assertEquals(attackerId, engine.resolveReactor(reaction(victimDest), attackerDest))
    }

    @Test fun noOverrideUsesSourceIdentity() = withEngine { engine, destRepo, msgRepo ->
        saveTarget(msgRepo, relayDest)
        destRepo.upsertFromAnnounce(dest(relayDest, relayId))
        assertEquals(relayId, engine.resolveReactor(reaction(null), relayDest))
    }

    @Test fun trustedButUnresolvableOverrideFallsBackToSource() = withEngine { engine, destRepo, msgRepo ->
        saveTarget(msgRepo, relayDest)
        destRepo.upsertFromAnnounce(dest(relayDest, relayId))
        // Gate passes but the stamped source_hash isn't in our table →
        // can't resolve → fall back to the carrying source's identity.
        assertEquals(relayId, engine.resolveReactor(reaction("ff".repeat(16)), relayDest))
    }
}
