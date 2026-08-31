package io.github.thatsfguy.reticulum.android.storage

import android.content.Context
import android.util.Log
import io.github.thatsfguy.reticulum.crypto.IdentityVault
import io.github.thatsfguy.reticulum.store.DestinationRepository
import io.github.thatsfguy.reticulum.store.IdentityRepository
import io.github.thatsfguy.reticulum.store.MessageRepository
import io.github.thatsfguy.reticulum.store.NomadPageCacheRepository
import io.github.thatsfguy.reticulum.store.ReactionsJson
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

/**
 * What is waiting in one conversation — a direct-message thread, an RRC
 * room, or (summed) a hub or a whole tab: how many unread messages, and
 * how many of those name you.
 *
 * The split is what the badge colour keys off. A conversation quietly
 * filling up is not the same event as somebody singling you out in it,
 * and only the second one has earned red. Direct messages never set
 * [mentions] — a DM is already addressed to you, so if it counted, red
 * would be the normal state again and would stop meaning anything.
 */
/**
 * The key every per-room map in the app is indexed by — unread tallies,
 * composer drafts, reply anchors, posted notification ids.
 *
 * One function rather than a string template at each site, because a
 * template is easy to get subtly wrong and impossible to notice: a
 * mis-escaped one compiled to the LITERAL text `${hub.destHash}/$room`
 * and simply never matched, so the hub unread badge silently summed to
 * zero and the reply banner never appeared. Neither failed loudly.
 */
fun rrcRoomKey(hubHash: String, room: String): String = "$hubHash/$room"

/** Prefix matching every room key on one hub — for summing a hub's rooms. */
fun rrcHubKeyPrefix(hubHash: String): String = "$hubHash/"

/** One incoming message, reduced to what the unread count needs. */
data class IncomingUnread(val id: Long, val timestamp: Long)

data class UnreadTally(val total: Int = 0, val mentions: Int = 0) {
    val hasMention: Boolean get() = mentions > 0

    operator fun plus(other: UnreadTally) =
        UnreadTally(total + other.total, mentions + other.mentions)
}

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

    /**
     * Every destination announcing [appName], uncapped.
     *
     * [observeDestinations] is a recency WINDOW, not the table, so any
     * list built by filtering it inherits that window — see
     * `DestinationDao.observeByAppName` for the measurements and the
     * discovery bug it caused.
     */
    fun observeDestinationsByAppName(appName: String): Flow<List<StoredDestination>> =
        db.destinationDao().observeByAppName(appName).map { rows -> rows.map { it.toModel() } }

    /** Destinations we've received a message from, resolved from their
     *  preserved rows even when they've fallen out of [observeDestinations]'s
     *  top-1000 recency window — so inbox sender names never degrade to
     *  "(unknown sender)" on a busy mesh. */
    fun observeIncomingSenderDestinations(): Flow<List<StoredDestination>> =
        db.destinationDao().observeIncomingSenderDestinations()
            .map { rows -> rows.map { it.toModel() } }

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

    /**
     * Unread tally for every room that has one, keyed `hubHash/room`.
     * Rooms with nothing unread are absent, so the UI can use a
     * presence check. Drives the room- and hub-list badges and the
     * Rooms tab's bottom-nav badge.
     */
    fun observeUnreadTally(): Flow<Map<String, UnreadTally>> =
        db.rrcDao().observeUnreadCounts().map { rows ->
            rows.associate { rrcRoomKey(it.hubHash, it.room) to UnreadTally(it.unread, it.mentions) }
        }

    /** Mark [room] read up to its newest message. Called when the user
     *  opens it (and on each new message while it is open). */
    suspend fun markRrcRoomRead(hubHash: String, room: String) {
        val dao = db.rrcDao()
        val newest = dao.maxMessageId(hubHash, room) ?: return
        dao.setRoomLastRead(hubHash, room, newest)
    }

    /** Set one room's notification mode — [StoredRrcRoom.NOTIFY_ALL],
     *  `NOTIFY_MENTIONS`, or `NOTIFY_NONE`. */
    suspend fun setRrcRoomNotifyMode(hubHash: String, room: String, mode: String) {
        db.rrcDao().setRoomNotifyMode(hubHash, room, mode)
    }

    /** One room row, or null when it isn't stored (the notification
     *  path needs its notify mode without an observer). */
    suspend fun getRrcRoom(hubHash: String, room: String): StoredRrcRoom? =
        db.rrcDao().getRoom(hubHash, room)?.toModel()

    /** Hashes of every sender we've received at least one incoming
     *  message from. Drives the Messages-tab Inbox section so senders
     *  who haven't been favorited yet are still reachable. */
    fun observeIncomingContactHashes(): Flow<List<String>> =
        db.messageDao().observeIncomingContactHashes()

    /** contactHash → last-message timestamp, for the Messages-tab
     *  recency sort. */
    fun observeLastMessageTimes(): Flow<Map<String, Long>> =
        db.messageDao().observeLastMessageTimes()
            .map { rows -> rows.associate { it.contactHash to it.lastTs } }

    /** contactHash → list of timestamps for every incoming message
     *  from that sender. Joined with the lastRead times in Preferences
     *  to compute the unread-count badge on each thread row. */
    fun observeIncomingUnreadRows(): Flow<Map<String, List<IncomingUnread>>> =
        db.messageDao().observeIncomingUnreadRows()
            .map { rows ->
                rows.groupBy({ it.contactHash }, { IncomingUnread(it.id, it.timestamp) })
            }

    /** Newest incoming message id for [contactHash], or null if none. */
    suspend fun newestIncomingId(contactHash: String): Long? =
        db.messageDao().maxIncomingId(contactHash)

    /** destHashes for which the cache has at least one page entry.
     *  UI uses this for the Nomad-list cached-indicator + filter. */
    fun observeCachedNomadDestHashes(): Flow<List<String>> =
        db.nomadPageCacheDao().observeCachedDestHashes()

    /** True while the identity's private keys are stored UNENCRYPTED in the
     *  legacy plaintext columns — the silent-degrade state when this device's
     *  Keystore refused the sealing vault (see [IdentityRepoImpl.save]). Drives
     *  the Settings security-warning banner; clears automatically once a save
     *  migrates the row into the Keystore-sealed columns. */
    fun observeKeysStoredPlaintext(): Flow<Boolean> =
        db.identityDao().observeKeysStoredPlaintext()

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

internal class IdentityRepoImpl(
    private val dao: IdentityDao,
    private val vault: IdentityVault,
) : IdentityRepository {
    override suspend fun save(identity: StoredIdentity) {
        // Seal every private key with the Keystore-backed vault. The
        // fallback policy is deliberately ASYMMETRIC by failure type —
        // this is the fix for 2026-07-28 security audit H1:
        //
        //  - KeystoreUnavailableException means this device cannot bring
        //    up a Keystore-backed key on ANY spec tier (see
        //    AndroidKeystoreIdentityVault.getOrCreateKey — it only throws
        //    this after every tier's kg.init() failed). That is a
        //    permanent property of the device, so degrading to legacy
        //    plaintext-column storage is the only way to keep the app
        //    usable. The Settings security banner
        //    (observeKeysStoredPlaintext) surfaces the degraded state.
        //    Same threat model as pre-1.1.27 (FBE + app-private + Auto
        //    Backup off, but no per-app key isolation). Originally
        //    reported on a Samsung A42 v1.1.27 install.
        //
        //  - ANY OTHER seal failure is TRANSIENT. The important case is a
        //    use-while-locked failure: tiers 1-2 create the wrapping key
        //    with setUnlockedDeviceRequired(true), so seal() throws
        //    (UserNotAuthenticatedException / vendor KeyStoreException)
        //    whenever the screen is locked — and this app persists the
        //    identity on the announce path (ratchet rotation) while the
        //    phone sits locked in a pocket via the foreground service.
        //    The OLD code caught every Throwable and wrote the long-term
        //    private keys to plaintext columns, so a single locked-period
        //    rotation silently downgraded an already-sealed identity to
        //    cleartext (recoverable by forensic imaging of a seized,
        //    powered-on, locked device — defeating the exact protection
        //    setUnlockedDeviceRequired was chosen for). We now NEVER write
        //    plaintext on a transient failure: keep any existing sealed
        //    row untouched and defer this write. The next successful save
        //    (next unlock, next rotation) persists the current state.
        //    Losing one ratchet-rotation persist is strictly better than
        //    persisting the identity in cleartext.
        val sealed = try {
            SealedKeys(
                enc = vault.seal(identity.encPrivKey),
                sig = vault.seal(identity.sigPrivKey),
                ratchet = identity.ratchetPrivKey?.let { vault.seal(it) },
                previousRatchet = identity.previousRatchetPrivKey?.let { vault.seal(it) },
            )
        } catch (permanent: KeystoreUnavailableException) {
            Log.w(
                "ReticulumEngine",
                "Keystore vault unavailable on this device — falling back to " +
                    "plaintext-column storage. Threat model degrades to " +
                    "pre-1.1.27 (FBE + app-private + Auto Backup off, but " +
                    "no per-app key isolation). Cause: ${permanent.message}",
                permanent,
            )
            // Device genuinely cannot seal. Persist plaintext to the
            // legacy columns; leave the *Enc columns null. Every future
            // save() re-attempts the vault, so if a secure lock screen
            // later appears the row migrates to the sealed columns.
            dao.upsert(IdentityEntity(
                id = 0,
                encPrivKey = identity.encPrivKey,
                sigPrivKey = identity.sigPrivKey,
                ratchetPrivKey = identity.ratchetPrivKey,
                encPrivKeyEnc = null,
                sigPrivKeyEnc = null,
                ratchetPrivKeyEnc = null,
                previousRatchetPrivKey = identity.previousRatchetPrivKey,
                previousRatchetPrivKeyEnc = null,
                lastRatchetRotationMs = identity.lastRatchetRotationMs,
            ))
            return
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (transient: Throwable) {
            // Transient seal failure (device locked, vendor Keystore
            // flake). Do NOT write plaintext. If a sealed row already
            // exists it stays intact; if none exists yet (first save on
            // a fresh install while locked — a narrow, self-healing
            // window: the in-memory identity stays usable and persists on
            // the next successful save), we still skip rather than leak.
            Log.w(
                "ReticulumEngine",
                "Transient Keystore seal failure — deferring identity save " +
                    "(keeping any existing sealed row; NOT writing plaintext). " +
                    "Cause: ${transient::class.simpleName}: ${transient.message}",
                transient,
            )
            return
        }
        // Seal succeeded. Persist sealed BLOBs; empty arrays in the
        // legacy plaintext columns as the "row migrated" sentinel.
        dao.upsert(IdentityEntity(
            id = 0,
            encPrivKey = ByteArray(0),
            sigPrivKey = ByteArray(0),
            ratchetPrivKey = null,
            encPrivKeyEnc = sealed.enc,
            sigPrivKeyEnc = sealed.sig,
            ratchetPrivKeyEnc = sealed.ratchet,
            previousRatchetPrivKey = null,
            previousRatchetPrivKeyEnc = sealed.previousRatchet,
            lastRatchetRotationMs = identity.lastRatchetRotationMs,
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
                    previousRatchetPrivKey = row.previousRatchetPrivKeyEnc
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { vault.unseal(it) },
                    lastRatchetRotationMs = row.lastRatchetRotationMs,
                )
            }.getOrNull() ?: legacyPlaintext(row)
                ?: throw IllegalStateException(
                    "Identity row exists but vault cannot unseal it and the " +
                        "legacy plaintext columns are empty. Two causes are " +
                        "possible and we deliberately throw (never regenerate) " +
                        "so a recoverable identity is preserved: (1) TRANSIENT — " +
                        "the device is locked and the wrapping key requires an " +
                        "unlocked device (tiers 1-2 set setUnlockedDeviceRequired); " +
                        "retry after unlocking. (2) PERMANENT — the key was " +
                        "invalidated (biometric enrollment / device wipe); " +
                        "re-import a .rmid backup."
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
        return StoredIdentity(
            row.encPrivKey,
            row.sigPrivKey,
            row.ratchetPrivKey,
            row.previousRatchetPrivKey,
            row.lastRatchetRotationMs,
        )
    }

    /** Vault-sealed forms of every private key on the identity row.
     *  Grouped so the Keystore fallback decision (seal all or none)
     *  stays a single runCatching. */
    private data class SealedKeys(
        val enc: ByteArray,
        val sig: ByteArray,
        val ratchet: ByteArray?,
        val previousRatchet: ByteArray?,
    )
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

    /**
     * Insert or replace a room row, keeping the columns the caller does
     * not know about.
     *
     * The engine re-upserts a room on every join and auto-rejoin, and
     * builds the model from the wire — where `lastReadMessageId` and
     * `notifyMode` have no representation, so they arrive at their
     * defaults. Room's REPLACE strategy would then quietly reset the
     * user's read marker and per-room notification setting on every
     * reconnect. Carry the stored values forward instead; an explicit
     * change goes through [setRoomLastRead] / [setRoomNotifyMode].
     */
    override suspend fun upsertRoom(room: StoredRrcRoom) {
        val existing = dao.getRoom(room.hubHash, room.name)
        val merged =
            if (existing == null) room
            else room.copy(
                lastReadMessageId = maxOf(room.lastReadMessageId, existing.lastReadMessageId),
                notifyMode = existing.notifyMode,
            )
        dao.upsertRoom(merged.toEntity())
    }
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

    /**
     * Read-merge-write, scoped to the room. Not atomic across the read
     * and the write, but reactions are a set: two concurrent reactors
     * racing on the same emoji both end up in the list either way, and
     * apply / retract are idempotent on the (emoji, sender) pair.
     */
    override suspend fun applyReaction(
        hubHash: String,
        room: String,
        msgId: String,
        emoji: String,
        senderHex: String,
        retract: Boolean,
    ): Boolean {
        val row = dao.getMessageByMsgId(hubHash, room, msgId) ?: return false
        val (json, changed) =
            if (retract) ReactionsJson.removeReaction(row.reactionsJson, emoji, senderHex)
            else ReactionsJson.applyReaction(row.reactionsJson, emoji, senderHex)
        if (changed) dao.setReactionsJson(row.id, json)
        return changed
    }
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
    override suspend fun deleteById(id: Long)                     = dao.deleteById(id)
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

private fun IdentityEntity.toModel() = StoredIdentity(
    encPrivKey,
    sigPrivKey,
    ratchetPrivKey,
    previousRatchetPrivKey,
    lastRatchetRotationMs,
)

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
    hubHash, name, joined, lastActivityAt, lastReadMessageId, notifyMode,
)
private fun StoredRrcRoom.toEntity() = RrcRoomEntity(
    hubHash, name, joined, lastActivityAt, lastReadMessageId, notifyMode,
)

private fun RrcMessageEntity.toModel() = StoredRrcMessage(
    id, hubHash, room, direction, senderIdHash, nick, text, timestamp, msgId, mention,
    replyToMsgId, reactionsJson,
)
private fun StoredRrcMessage.toEntity() = RrcMessageEntity(
    id, hubHash, room, direction, senderIdHash, nick, text, timestamp, msgId, mention,
    replyToMsgId, reactionsJson,
)

private fun MessageEntity.toModel() = StoredMessage(
    id, contactHash, direction, content, title, timestamp, state, attempts,
    lastAttempt, lastError, rawPacket, packetHash, rssi, hopCount, imageBytes,
    messageId, replyToMessageId, reactionsJson, arrivedViaDest,
    attachmentName, attachmentBytes,
    imageToken, imageSize, attachmentToken, attachmentSize,
    audioMode,
)
private fun StoredMessage.toEntity() = MessageEntity(
    id, contactHash, direction, content, title, timestamp, state, attempts,
    lastAttempt, lastError, rawPacket, packetHash, rssi, hopCount, imageBytes,
    messageId, replyToMessageId, reactionsJson, arrivedViaDest,
    attachmentName, attachmentBytes,
    imageToken, imageSize, attachmentToken, attachmentSize,
    audioMode,
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
