package io.github.thatsfguy.reticulum.android.storage

import android.content.Context
import android.util.Log
import io.github.thatsfguy.reticulum.crypto.IdentityVault
import io.github.thatsfguy.reticulum.store.DestinationRepository
import io.github.thatsfguy.reticulum.store.IdentityRepository
import io.github.thatsfguy.reticulum.store.MessageRepository
import io.github.thatsfguy.reticulum.store.NomadPageCacheRepository
import io.github.thatsfguy.reticulum.store.RrcRepository
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredIdentity
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.store.StoredNomadPage
import io.github.thatsfguy.reticulum.store.StoredRrcHub
import io.github.thatsfguy.reticulum.store.StoredRrcMessage
import io.github.thatsfguy.reticulum.store.StoredRrcRoom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class Repositories private constructor(
    val identity: IdentityRepository,
    val destinations: DestinationRepository,
    val messages: MessageRepository,
    val nomadPageCache: NomadPageCacheRepository,
    val rrc: RrcRepository,
    private val db: ReticulumDatabase,
) {
    fun observeDestinations(): Flow<List<StoredDestination>> =
        db.destinationDao().observeAll().map { rows -> rows.map { it.toModel() } }

    fun observeMessagesForContact(contactHash: String): Flow<List<StoredMessage>> =
        db.messageDao().observeForContact(contactHash).map { rows -> rows.map { it.toModel() } }

    /** All known RRC hubs, most-recently-connected first. Drives the
     *  experimental Rooms screen's hub list. */
    fun observeRrcHubs(): Flow<List<StoredRrcHub>> =
        db.rrcDao().observeHubs().map { rows -> rows.map { it.toModel() } }

    /** Rooms on one hub, most-recently-active first. */
    fun observeRrcRooms(hubHash: String): Flow<List<StoredRrcRoom>> =
        db.rrcDao().observeRoomsForHub(hubHash).map { rows -> rows.map { it.toModel() } }

    /** Message history for one room, oldest first. */
    fun observeRrcMessages(hubHash: String, room: String): Flow<List<StoredRrcMessage>> =
        db.rrcDao().observeMessages(hubHash, room).map { rows -> rows.map { it.toModel() } }

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
                rrc            = RrcRepoImpl(db.rrcDao()),
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
        // Try the Keystore-backed vault first. If the device's
        // Keystore rejected every spec tier (KeystoreUnavailableException
        // from AndroidKeystoreIdentityVault.getOrCreateKey), the
        // app would otherwise crash on first save — a fresh install
        // OR a .rmid import would brick the user. Degrade to
        // legacy plaintext-column storage instead. Same threat
        // model as pre-1.1.27 (FBE + app-private storage + Auto
        // Backup off, but no per-app key isolation). Audit
        // reference: 2026-05-13 HIGH-1 follow-up; reported on
        // a Samsung A42 v1.1.27 install.
        val sealed = runCatching {
            Triple(
                vault.seal(identity.encPrivKey),
                vault.seal(identity.sigPrivKey),
                identity.ratchetPrivKey?.let { vault.seal(it) },
            )
        }.onFailure { err ->
            // Log loudly so adb logcat + the in-app diagnostics
            // panel both surface the fallback. Users on broken-
            // Keystore devices won't see a UI prompt yet — the
            // diagnostics log is the only visible signal until a
            // future release adds a UI banner.
            Log.w(
                "ReticulumEngine",
                "Keystore vault refused on this device — falling back to " +
                    "plaintext-column storage. Threat model degrades to " +
                    "pre-1.1.27 (FBE + app-private + Auto Backup off, but " +
                    "no per-app key isolation). Cause: " +
                    "${err::class.simpleName}: ${err.message}",
                err,
            )
        }.getOrNull()
        if (sealed != null) {
            // Keystore vault worked. Persist sealed BLOBs; empty
            // arrays in the legacy plaintext columns as the
            // "row migrated" sentinel.
            dao.upsert(IdentityEntity(
                id = 0,
                encPrivKey = ByteArray(0),
                sigPrivKey = ByteArray(0),
                ratchetPrivKey = null,
                encPrivKeyEnc = sealed.first,
                sigPrivKeyEnc = sealed.second,
                ratchetPrivKeyEnc = sealed.third,
            ))
        } else {
            // Keystore vault refused to operate on this device.
            // Persist plaintext to the legacy columns; leave the
            // *Enc columns null. load() will treat this row as a
            // legacy plaintext row going forward, and every future
            // save() will attempt the vault again — so if the user
            // later sets up a secure lock screen, the next call
            // through this repository will migrate the row to the
            // sealed columns automatically.
            dao.upsert(IdentityEntity(
                id = 0,
                encPrivKey = identity.encPrivKey,
                sigPrivKey = identity.sigPrivKey,
                ratchetPrivKey = identity.ratchetPrivKey,
                encPrivKeyEnc = null,
                sigPrivKeyEnc = null,
                ratchetPrivKeyEnc = null,
            ))
        }
    }

    override suspend fun load(): StoredIdentity? {
        val row = dao.load() ?: return null
        // Prefer the encrypted columns (post-1.1.27 writes).
        val encEnc = row.encPrivKeyEnc
        val sigEnc = row.sigPrivKeyEnc
        if (encEnc != null && encEnc.isNotEmpty() &&
            sigEnc != null && sigEnc.isNotEmpty()
        ) {
            // unseal can also throw on a Keystore-key-invalidated
            // device — same fallback shape: if unseal fails AND the
            // legacy plaintext columns are still populated, return
            // those. If unseal fails AND the plaintext columns are
            // empty, the identity is genuinely unrecoverable and we
            // surface that to the caller (ensureIdentity logs it).
            return runCatching {
                StoredIdentity(
                    encPrivKey = vault.unseal(encEnc),
                    sigPrivKey = vault.unseal(sigEnc),
                    ratchetPrivKey = row.ratchetPrivKeyEnc
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { vault.unseal(it) },
                )
            }.getOrNull() ?: legacyPlaintext(row)
                ?: throw IllegalStateException(
                    "Identity row exists but vault cannot unseal it and " +
                        "the legacy plaintext columns are empty. The wrapping " +
                        "key was likely invalidated (biometric enrollment / " +
                        "device wipe). Re-import a .rmid backup."
                )
        }
        // Legacy plaintext columns. Hand them back as-is; the engine's
        // ensureIdentity path will re-save through this repository,
        // which encrypts on write and clears the plaintext columns.
        // After one successful save no row in the DB carries plaintext
        // keys.
        return row.toModel()
    }

    private fun legacyPlaintext(row: IdentityEntity): StoredIdentity? {
        if (row.encPrivKey.isEmpty() || row.sigPrivKey.isEmpty()) return null
        return StoredIdentity(row.encPrivKey, row.sigPrivKey, row.ratchetPrivKey)
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

private class RrcRepoImpl(private val dao: RrcDao) : RrcRepository {
    override suspend fun upsertHub(hub: StoredRrcHub) = dao.upsertHub(hub.toEntity())
    override suspend fun getHub(destHash: String): StoredRrcHub? =
        dao.getHub(destHash)?.toModel()
    override suspend fun getAllHubs(): List<StoredRrcHub> =
        dao.getAllHubs().map { it.toModel() }
    override suspend fun setHubLastConnected(destHash: String, whenMs: Long) =
        dao.setHubLastConnected(destHash, whenMs)

    override suspend fun deleteHub(destHash: String) {
        // No Room foreign keys in this schema, so cascade explicitly.
        // Order is immaterial — each delete is scoped by hubHash.
        dao.deleteMessagesForHub(destHash)
        dao.deleteRoomsForHub(destHash)
        dao.deleteHub(destHash)
    }

    override suspend fun upsertRoom(room: StoredRrcRoom) = dao.upsertRoom(room.toEntity())
    override suspend fun getRoomsForHub(hubHash: String): List<StoredRrcRoom> =
        dao.getRoomsForHub(hubHash).map { it.toModel() }
    override suspend fun setRoomJoined(hubHash: String, name: String, joined: Boolean) =
        dao.setRoomJoined(hubHash, name, joined)
    override suspend fun touchRoom(hubHash: String, name: String, activityMs: Long) =
        dao.touchRoom(hubHash, name, activityMs)

    override suspend fun deleteRoom(hubHash: String, name: String) {
        dao.deleteMessagesForRoom(hubHash, name)
        dao.deleteRoom(hubHash, name)
    }

    override suspend fun saveMessage(message: StoredRrcMessage): Long =
        dao.insertMessage(message.toEntity())
    override suspend fun getMessages(hubHash: String, room: String): List<StoredRrcMessage> =
        dao.getMessages(hubHash, room).map { it.toModel() }
    override suspend fun hasMessageId(hubHash: String, msgId: String): Boolean =
        dao.hasMessageId(hubHash, msgId)
    override suspend fun deleteMessagesForRoom(hubHash: String, room: String) =
        dao.deleteMessagesForRoom(hubHash, room)
}

private class MessageRepoImpl(private val dao: MessageDao) : MessageRepository {
    override suspend fun save(message: StoredMessage): Long       = dao.insert(message.toEntity())
    override suspend fun getById(id: Long): StoredMessage?        = dao.getById(id)?.toModel()
    override suspend fun getForContact(contactHash: String)       = dao.getForContact(contactHash).map { it.toModel() }
    override suspend fun getAll(): List<StoredMessage>            = dao.getAll().map { it.toModel() }
    override suspend fun getOutgoingByPacketHash(hash: String): StoredMessage? =
        dao.getOutgoingByPacketHash(hash)?.toModel()
    override suspend fun getByMessageId(messageId: String): StoredMessage? =
        dao.getByMessageId(messageId)?.toModel()
    override suspend fun setMessageId(rowId: Long, messageId: String) {
        dao.setMessageId(rowId, messageId)
    }
    override suspend fun applyReaction(
        targetMessageId: String,
        emoji: String,
        senderHex: String,
    ): Boolean {
        // Read-merge-write. Not strictly atomic across reads, but
        // reactions are append-only so the worst case is two
        // concurrent senders racing on the same emoji — both end up
        // in the list either way. Idempotent on the
        // (emoji, sender) pair via ReactionsJson.applyReaction.
        val row = dao.getByMessageId(targetMessageId) ?: return false
        val (newJson, changed) = io.github.thatsfguy.reticulum.store
            .ReactionsJson.applyReaction(row.reactionsJson, emoji, senderHex)
        if (changed) dao.setReactionsJson(row.id, newJson)
        return true
    }
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

private fun RrcHubEntity.toModel() = StoredRrcHub(
    destHash, displayName, nick, lastConnectedAt, addedAt,
)
private fun StoredRrcHub.toEntity() = RrcHubEntity(
    destHash, displayName, nick, lastConnectedAt, addedAt,
)

private fun RrcRoomEntity.toModel() = StoredRrcRoom(
    hubHash, name, joined, lastActivityAt,
)
private fun StoredRrcRoom.toEntity() = RrcRoomEntity(
    hubHash, name, joined, lastActivityAt,
)

private fun RrcMessageEntity.toModel() = StoredRrcMessage(
    id, hubHash, room, direction, senderIdHash, nick, text, timestamp, msgId,
)
private fun StoredRrcMessage.toEntity() = RrcMessageEntity(
    id, hubHash, room, direction, senderIdHash, nick, text, timestamp, msgId,
)

private fun MessageEntity.toModel() = StoredMessage(
    id, contactHash, direction, content, title, timestamp, state, attempts,
    lastAttempt, lastError, rawPacket, packetHash, rssi, hopCount, imageBytes,
    messageId, replyToMessageId, reactionsJson, arrivedViaDest,
    attachmentName, attachmentBytes,
)
private fun StoredMessage.toEntity() = MessageEntity(
    id, contactHash, direction, content, title, timestamp, state, attempts,
    lastAttempt, lastError, rawPacket, packetHash, rssi, hopCount, imageBytes,
    messageId, replyToMessageId, reactionsJson, arrivedViaDest,
    attachmentName, attachmentBytes,
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
