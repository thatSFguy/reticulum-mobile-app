package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.transport.hexToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Reaction attribution MUST key on the reactor's IDENTITY hash
 * (`SHA256(public_key)[:16]`), never the lxmf.delivery DESTINATION
 * hash. Mixing the two in one aggregation namespace double-counts the
 * same reactor — the dest-vs-identity gotcha (CLAUDE.md "Key bugs" §3)
 * that also bit the forwarding service.
 *
 * [ReticulumEngine.reactorIdentity] therefore returns the identity hash
 * when the source destination is known, and `null` otherwise — it must
 * NEVER fall back to the destination hash. Callers skip applying a
 * reaction they can't attribute by identity.
 */
class ReactorIdentityTest {

    private fun storedDest(destHex: String, identityHex: String, withKey: Boolean) =
        StoredDestination(
            hash = destHex,
            identityHash = identityHex,
            publicKey = if (withKey) ByteArray(64) else ByteArray(0),
            destHash = destHex.hexToBytes(),
            nameHash = ByteArray(0),
            ratchetPub = null,
            displayName = "peer",
            appName = "lxmf.delivery",
            appLabel = null,
            telemetry = null,
            lat = null, lon = null,
            appDataHex = "",
            lastSeen = 0,
            rssi = null,
            favorite = false,
            source = "test",
        )

    private fun withEngine(body: suspend (ReticulumEngine, InMemoryDestRepo) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val destRepo = InMemoryDestRepo()
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = InMemoryIdentityRepo(),
            destinationRepo = destRepo,
            messageRepo = InMemoryMsgRepo(),
            scope = scope,
            nowMs = { 1_700_000_000_000L },
            displayNameProvider = { "test" },
        )
        try {
            body(engine, destRepo)
        } finally {
            scope.cancel()
        }
    }

    @Test fun knownDestResolvesToIdentityHash() = withEngine { engine, destRepo ->
        val destHex = "aa".repeat(16)
        val idHex = "bb".repeat(16)
        destRepo.upsertFromAnnounce(storedDest(destHex, idHex, withKey = true))
        // Resolves to the IDENTITY hash, which is NOT the destination hash.
        assertEquals(idHex, engine.reactorIdentity(destHex))
        assertNotEquals(destHex, engine.reactorIdentity(destHex))
    }

    @Test fun unknownDestReturnsNullNotDestHash() = withEngine { engine, _ ->
        val destHex = "cc".repeat(16)
        // No row → null. Crucially NOT the destination hash itself.
        assertNull(engine.reactorIdentity(destHex))
    }

    @Test fun placeholderRowWithoutIdentityReturnsNull() = withEngine { engine, destRepo ->
        // A known destination we've only seen by hash (no public key yet)
        // has identityHash == "" — must still resolve to null, never the
        // destination hash.
        val destHex = "dd".repeat(16)
        destRepo.upsertFromAnnounce(storedDest(destHex, identityHex = "", withKey = false))
        assertNull(engine.reactorIdentity(destHex))
    }
}
