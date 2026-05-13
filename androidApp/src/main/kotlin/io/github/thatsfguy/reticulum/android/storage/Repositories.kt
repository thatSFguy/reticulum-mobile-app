package io.github.thatsfguy.reticulum.android.storage

import android.content.Context
import io.github.thatsfguy.reticulum.crypto.IdentityVault
import io.github.thatsfguy.reticulum.store.DestinationRepository
import io.github.thatsfguy.reticulum.store.IdentityRepository
import io.github.thatsfguy.reticulum.store.MessageRepository
import io.github.thatsfguy.reticulum.store.NomadPageCacheRepository
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredIdentity
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.store.StoredNomadPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class Repositories private constructor(
    val identity: IdentityRepository,
    val destinations: DestinationRepository,
    val messages: MessageRepository,
    val nomadPageCache: NomadPageCacheRepository,
    private val db: ReticulumDatabase,
) {
    fun observeDestinations(): Flow<List<StoredDestination>> =
        db.destinationDao().observeAll().map { rows -> rows.map { it.toModel() } }

    fun observeMessagesForContact(contactHash: String): Flow<List<StoredMessage>> =
        db.messageDao().observeForContact(contactHash).map { rows -> rows.map { it.toModel() } }

    /** Hashes of every sender we've received at least one incoming
     *  message from. Drives the Messages-tab Inbox section so senders
     *  who haven't been favorited yet are still reachable. */
    fun observeIncomingContactHashes(): Flow<List<String>> =
        db.messageDao().observeIncomingContactHashes()

    /** destHashes for which the cache has at least one page entry.
     *  UI uses this for the Nomad-list cached-indicator + filter. */
    fun observeCachedNomadDestHashes(): Flow<List<String>> =
        db.nomadPageCacheDao().observeCachedDestHashes()

    companion object {
        fun create(context: Context): Repositories {
            val db = ReticulumDatabase.get(context)
            // HIGH-1 follow-up: identity private keys at rest are now
            // wrapped with an Android Keystore-backed AES-256-GCM key.
            // The vault is injected so unit tests can swap in an
            // in-memory pass-through implementation; production always
            // uses the Keystore-bound impl. Audit reference:
            // 2026-05-13.
            val vault = AndroidKeystoreIdentityVault()
            return Repositories(
                identity       = IdentityRepoImpl(db.identityDao(), vault),
                destinations   = DestinationRepoImpl(db.destinationDao()),
                messages       = MessageRepoImpl(db.messageDao()),
                nomadPageCache = NomadPageCacheRepoImpl(db.nomadPageCacheDao()),
                db = db,
            )
        }
    }
}

private class IdentityRepoImpl(
    private val dao: IdentityDao,
    private val vault: IdentityVault,
) : IdentityRepository {
    override suspend fun save(identity: StoredIdentity) {
        // Encrypt all three private key columns. Plaintext columns
        // are written as empty arrays — a sentinel for "this row's
        // keys live in the *Enc columns, the plaintext columns are
        // present only for schema compatibility with older builds."
        // Room's @Insert REPLACE strategy overwrites the entire row
        // so any prior plaintext content is overwritten in-place.
        val encEnc = vault.seal(identity.encPrivKey)
        val sigEnc = vault.seal(identity.sigPrivKey)
        val ratchetEnc = identity.ratchetPrivKey?.let { vault.seal(it) }
        dao.upsert(IdentityEntity(
            id = 0,
            encPrivKey = ByteArray(0),
            sigPrivKey = ByteArray(0),
            ratchetPrivKey = null,
            encPrivKeyEnc = encEnc,
            sigPrivKeyEnc = sigEnc,
            ratchetPrivKeyEnc = ratchetEnc,
        ))
    }

    override suspend fun load(): StoredIdentity? {
        val row = dao.load() ?: return null
        // Prefer the encrypted columns (post-1.1.27 writes).
        val encEnc = row.encPrivKeyEnc
        val sigEnc = row.sigPrivKeyEnc
        if (encEnc != null && encEnc.isNotEmpty() &&
            sigEnc != null && sigEnc.isNotEmpty()
        ) {
            return StoredIdentity(
                encPrivKey = vault.unseal(encEnc),
                sigPrivKey = vault.unseal(sigEnc),
                ratchetPrivKey = row.ratchetPrivKeyEnc
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { vault.unseal(it) },
            )
        }
        // Legacy plaintext columns. Hand them back as-is; the engine's
        // ensureIdentity path will re-save through this repository,
        // which encrypts on write and clears the plaintext columns.
        // After one successful save no row in the DB carries plaintext
        // keys.
        return row.toModel()
    }
}

private class DestinationRepoImpl(private val dao: DestinationDao) : DestinationRepository {
    override suspend fun upsertFromAnnounce(record: StoredDestination) {
        // Engine has already merged with any existing row; just save.
        // hidden defaults to false on the merged record (engine doesn't
        // pass it through), so any prior soft-delete is automatically
        // cleared on re-announce — exactly what we want.
        dao.upsert(record.toEntity())
    }
    override suspend fun upsertManualStub(record: StoredDestination) {
        val existing = dao.get(record.hash)
        if (existing == null) {
            dao.upsert(record.toEntity())
        } else {
            // Preserve any data we already have; favorite + un-hide on
            // re-add (user's intent was clearly to bring it back). If
            // the user typed a fresh label this time, overwrite the
            // userLabel; blank input keeps whatever was already there.
            dao.upsert(existing.copy(
                favorite = true,
                hidden = false,
                userLabel = record.userLabel?.takeIf { it.isNotBlank() } ?: existing.userLabel,
            ))
        }
    }
    override suspend fun get(hash: String): StoredDestination? = dao.get(hash)?.toModel()
    override suspend fun getAll(): List<StoredDestination> = dao.getAll().map { it.toModel() }
    override suspend fun setFavorite(hash: String, favorite: Boolean) = dao.setFavorite(hash, favorite)
    override suspend fun setUserLabel(hash: String, label: String?) {
        // Empty/blank label means "clear it" — store as null so the
        // effectiveDisplayName fallback chain advances to displayName.
        val normalized = label?.takeIf { it.isNotBlank() }?.trim()
        dao.setUserLabel(hash, normalized)
    }
    override suspend fun delete(hash: String) = dao.hide(hash)
    override suspend fun deleteAll() = dao.deleteAll()
    override suspend fun evictUnfavoritedOldest(keepCount: Int): Int =
        dao.evictUnfavoritedOldest(keepCount)
}

private class NomadPageCacheRepoImpl(private val dao: NomadPageCacheDao) : NomadPageCacheRepository {
    override suspend fun put(page: StoredNomadPage) {
        dao.upsert(NomadPageCacheEntity(
            destHash  = page.destHash,
            path      = page.path,
            source    = page.source,
            fetchedAt = page.fetchedAt,
            byteSize  = page.byteSize,
        ))
    }
    override suspend fun get(destHash: String, path: String): StoredNomadPage? =
        dao.get(destHash, path)?.let {
            StoredNomadPage(it.destHash, it.path, it.source, it.fetchedAt, it.byteSize)
        }
    override suspend fun anyCachedFor(destHash: String): Boolean = dao.anyForDest(destHash)
    override suspend fun clear(destHash: String, path: String) = dao.delete(destHash, path)
    override suspend fun clearAllForDest(destHash: String) = dao.deleteAllForDest(destHash)
    override suspend fun clearAll() = dao.deleteAll()
}

private class MessageRepoImpl(private val dao: MessageDao) : MessageRepository {
    override suspend fun save(message: StoredMessage): Long       = dao.insert(message.toEntity())
    override suspend fun getById(id: Long): StoredMessage?        = dao.getById(id)?.toModel()
    override suspend fun getForContact(contactHash: String)       = dao.getForContact(contactHash).map { it.toModel() }
    override suspend fun getAll(): List<StoredMessage>            = dao.getAll().map { it.toModel() }
    override suspend fun getOutgoingByPacketHash(hash: String): StoredMessage? =
        dao.getOutgoingByPacketHash(hash)?.toModel()
    override suspend fun deleteForContact(contactHash: String)    = dao.deleteForContact(contactHash)
    override suspend fun updateState(
        id: Long,
        state: String?,
        attempts: Int?,
        lastAttempt: Long?,
        lastError: String?,
        packetHash: String?,
    ) {
        dao.updateState(id, state, attempts, lastAttempt, lastError, packetHash)
    }
}

// ---- Mappers ----------------------------------------------------------

private fun IdentityEntity.toModel() = StoredIdentity(encPrivKey, sigPrivKey, ratchetPrivKey)

internal fun DestinationEntity.toModel() = StoredDestination(
    hash, identityHash, publicKey, destHash, nameHash,
    ratchetPub, displayName, appName, appLabel,
    telemetry = telemetryJson?.let(::parseTelemetryJson),
    lat = lat, lon = lon, appDataHex = appDataHex,
    lastSeen = lastSeen, rssi = rssi, favorite = favorite, source = source,
    hidden = hidden, hopCount = hopCount, nextHop = nextHop,
    userLabel = userLabel,
)
internal fun StoredDestination.toEntity() = DestinationEntity(
    hash, identityHash, publicKey, destHash, nameHash,
    ratchetPub, displayName, appName, appLabel,
    telemetryJson = telemetry?.let(::encodeTelemetryJson),
    lat = lat, lon = lon, appDataHex = appDataHex,
    lastSeen = lastSeen, rssi = rssi, favorite = favorite, source = source,
    hidden = hidden, hopCount = hopCount, nextHop = nextHop,
    userLabel = userLabel,
)

private fun MessageEntity.toModel() = StoredMessage(
    id, contactHash, direction, content, title, timestamp, state, attempts,
    lastAttempt, lastError, rawPacket, packetHash, rssi, hopCount, imageBytes,
)
private fun StoredMessage.toEntity() = MessageEntity(
    id, contactHash, direction, content, title, timestamp, state, attempts,
    lastAttempt, lastError, rawPacket, packetHash, rssi, hopCount, imageBytes,
)

private fun encodeTelemetryJson(map: Map<String, String>): String =
    map.entries.joinToString(",", "{", "}") { (k, v) ->
        "${jsonString(k)}:${jsonString(v)}"
    }

private fun parseTelemetryJson(json: String): Map<String, String> {
    if (json.length <= 2) return emptyMap()
    val out = LinkedHashMap<String, String>()
    var i = 1
    while (i < json.length - 1) {
        if (json[i] != '"') { i++; continue }
        val (key, next1) = readJsonString(json, i)
        var j = next1
        while (j < json.length && json[j] != ':') j++
        j++
        while (j < json.length && json[j] != '"') j++
        val (value, next2) = readJsonString(json, j)
        out[key] = value
        i = next2
        while (i < json.length && (json[i] == ',' || json[i].isWhitespace())) i++
    }
    return out
}

private fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"'  -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(c)
    }
    append('"')
}

private fun readJsonString(json: String, start: Int): Pair<String, Int> {
    require(json[start] == '"')
    val sb = StringBuilder()
    var i = start + 1
    while (i < json.length) {
        val c = json[i]
        if (c == '"') return sb.toString() to (i + 1)
        if (c == '\\' && i + 1 < json.length) {
            when (json[i + 1]) {
                '"'  -> sb.append('"')
                '\\' -> sb.append('\\')
                'n'  -> sb.append('\n')
                'r'  -> sb.append('\r')
                't'  -> sb.append('\t')
                else -> sb.append(json[i + 1])
            }
            i += 2
            continue
        }
        sb.append(c)
        i++
    }
    throw IllegalArgumentException("Unterminated JSON string at $start")
}
