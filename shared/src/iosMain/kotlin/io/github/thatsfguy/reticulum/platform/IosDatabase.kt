package io.github.thatsfguy.reticulum.platform

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.github.thatsfguy.reticulum.storage.ReticulumIosDatabase
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * iOS storage actual. Bundles a [NativeSqliteDriver]-backed
 * [ReticulumIosDatabase] with the four [IdentityRepository] /
 * [DestinationRepository] / [MessageRepository] /
 * [NomadPageCacheRepository] implementations the engine consumes.
 *
 * Mirrors the structure of `androidApp/.../storage/Repositories.kt`
 * — same shape, same `observe*` Flow methods — so the iOS app shell
 * (Phase 4) can wire it identically to how `ReticulumService` wires
 * the Android Room version.
 *
 * Schema is defined in
 * `shared/src/commonMain/sqldelight/.../ReticulumIosDatabase.sq` and
 * matches the Android Room v8 schema column-for-column.
 */
/**
 * iOS storage actual.
 *
 * History note (2026-05-09): we used to expose live queries via
 * SQLDelight's `Query.asFlow().mapToList(...)`. The
 * [IosMessageRepoFlowTest] in iosTest proved that on Kotlin/Native the
 * registered `Query.Listener` does NOT fire after a UPDATE, even though
 * the generated `updateMessageState` correctly calls
 * `notifyQueries(...) { emit("messages") }` and `addListener("messages",
 * listener)` is registered on the same driver. The test passes the
 * initial-snapshot emission and times out waiting for the post-update
 * one. Symptom in the wild was a sender's conversation row stuck on
 * the ⏳ glyph (state="pending") even though the engine wrote
 * state="delivered" and the recipient received the message.
 *
 * We work around it by driving observe* flows from manually-emitted
 * change ticks ([messagesChanges] / [destinationsChanges] /
 * [nomadCacheChanges]). Each repository implementation calls the
 * corresponding `onChange` lambda after every mutation; observe*
 * fetches a fresh snapshot via the synchronous repo.getAll() / getForContact()
 * paths and re-emits on each tick. This bypasses the SQLDelight
 * notification surface entirely.
 */
class IosRepositories private constructor(
    val identity: IdentityRepository,
    val destinations: DestinationRepository,
    val messages: MessageRepository,
    val nomadPageCache: NomadPageCacheRepository,
    /** Reticulum Relay Chat storage — hubs, rooms, room history.
     *  Backs the (experimental) Rooms feature; null-safe to leave
     *  unused when the feature flag is off. */
    val rrc: RrcRepository,
    private val db: ReticulumIosDatabase,
    private val messagesChanges: MutableSharedFlow<Unit>,
    private val destinationsChanges: MutableSharedFlow<Unit>,
    private val nomadCacheChanges: MutableSharedFlow<Unit>,
    private val rrcChanges: MutableSharedFlow<Unit>,
) {
    /** Live stream of every observed/manual destination, sorted
     *  favorites-first then most-recently-seen. Mirrors the Room DAO's
     *  `observeAll()`. */
    fun observeDestinations(): Flow<List<StoredDestination>> =
        destinationsChanges.onStart { emit(Unit) }.map { destinations.getAll() }

    /** Messages for a single conversation, oldest-first. */
    fun observeMessagesForContact(contactHash: String): Flow<List<StoredMessage>> =
        messagesChanges.onStart { emit(Unit) }.map { messages.getForContact(contactHash) }

    /** Distinct sender dest hashes for every incoming message — drives
     *  the Messages-tab Inbox section. */
    fun observeIncomingContactHashes(): Flow<List<String>> =
        messagesChanges.onStart { emit(Unit) }.map {
            db.reticulumIosDatabaseQueries.observeIncomingContactHashes().executeAsList()
        }

    /** destHashes that have at least one cached Nomad page. */
    fun observeCachedNomadDestHashes(): Flow<List<String>> =
        nomadCacheChanges.onStart { emit(Unit) }.map {
            db.reticulumIosDatabaseQueries.observeCachedDestHashes().executeAsList()
        }

    /** Favorited messagable destinations — drives the Messages tab's
     *  pinned section. Mirrors the Android `ReticulumViewModel.favorites`
     *  derivation: passes manual stubs (`publicKey.isEmpty()`) through
     *  too so they appear in Messages while waiting for an announce. */
    fun observeFavorites(): Flow<List<StoredDestination>> =
        observeDestinations().map { rows ->
            rows.filter { it.favorite && (it.isMessagable || it.publicKey.isEmpty()) }
        }

    /** Senders we've received at least one message from but haven't
     *  favorited — drives the Messages tab's Inbox section. Combine of
     *  the destinations table and the messages-by-direction projection,
     *  matching the Android shape so the iOS list renders identically. */
    fun observeInbox(): Flow<List<StoredDestination>> =
        combine(
            observeDestinations(),
            observeIncomingContactHashes(),
        ) { dests, incomingHashes ->
            val incomingSet = incomingHashes.toSet()
            dests.filter {
                it.isMessagable &&
                    !it.favorite &&
                    it.hash in incomingSet
            }
        }

    /** Live stream of known RRC hubs, most-recently-connected first.
     *  Mirrors the Android `observeRrcHubs()`. */
    fun observeRrcHubs(): Flow<List<StoredRrcHub>> =
        rrcChanges.onStart { emit(Unit) }.map { rrc.getAllHubs() }

    /** Live stream of the rooms known for [hubHash]. */
    fun observeRrcRooms(hubHash: String): Flow<List<StoredRrcRoom>> =
        rrcChanges.onStart { emit(Unit) }.map { rrc.getRoomsForHub(hubHash) }

    /** Live stream of one room's message history, oldest-first. */
    fun observeRrcMessages(hubHash: String, room: String): Flow<List<StoredRrcMessage>> =
        rrcChanges.onStart { emit(Unit) }.map { rrc.getMessages(hubHash, room) }

    companion object {
        /** Build the singleton iOS repositories backed by an on-disk
         *  SQLite file at the standard NSDocumentDirectory location.
         *  [name] becomes the filename — useful for tests that want
         *  isolation. Production callers leave the default. */
        fun create(name: String = "reticulum.db"): IosRepositories {
            val driver = NativeSqliteDriver(ReticulumIosDatabase.Schema, name)
            val db = ReticulumIosDatabase(driver)
            // extraBufferCapacity > 0 so tryEmit from a non-suspending
            // mutation always succeeds without dropping. Observers are
            // expected to be cheap (a single getForContact()/getAll()
            // call) so we don't conflate.
            val messagesChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
            val destinationsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
            val nomadCacheChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
            val rrcChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
            return IosRepositories(
                // Keychain-backed identity vault — seals the long-term
                // private keys with a master key that lives outside the
                // (exfiltratable) SQLite file. Closes the deferred half of
                // audit 2026-05-13 HIGH-1; pre-Keychain installs that wrote
                // raw plaintext into the *Enc columns are migrated in place
                // by IosIdentityRepo.load().
                identity = IosIdentityRepo(
                    db,
                    KeychainIdentityVault(),
                ),
                destinations = IosDestinationRepo(db) { destinationsChanges.tryEmit(Unit) },
                messages = IosMessageRepo(db) { messagesChanges.tryEmit(Unit) },
                nomadPageCache = IosNomadPageCacheRepo(db) { nomadCacheChanges.tryEmit(Unit) },
                rrc = IosRrcRepo(db) { rrcChanges.tryEmit(Unit) },
                db = db,
                messagesChanges = messagesChanges,
                destinationsChanges = destinationsChanges,
                nomadCacheChanges = nomadCacheChanges,
                rrcChanges = rrcChanges,
            )
        }
    }
}

// ---- repository impls -------------------------------------------------

private class IosIdentityRepo(
    private val db: ReticulumIosDatabase,
    private val vault: io.github.thatsfguy.reticulum.crypto.IdentityVault,
) : IdentityRepository {
    private val q get() = db.reticulumIosDatabaseQueries

    override suspend fun save(identity: StoredIdentity) {
        // Seal the private keys with the Keychain-backed vault before
        // persisting. If the Keychain refuses the master key (device
        // locked at first launch, access denied), degrade to plaintext-
        // column storage rather than crashing — mirrors the Android
        // Keystore-unavailable fallback. Every future save() re-attempts
        // the vault, so the row migrates to sealed automatically once the
        // Keychain becomes available. Audit reference: 2026-05-13 HIGH-1.
        val sealed = runCatching {
            Triple(
                vault.seal(identity.encPrivKey),
                vault.seal(identity.sigPrivKey),
                identity.ratchetPrivKey?.let { vault.seal(it) },
            )
        }.onFailure { e ->
            // Goes to the iOS device console (Xcode → Devices). The same
            // degraded state is what the load() path then reads back.
            println(
                "[IosIdentityRepo] Keychain vault refused — storing identity " +
                    "keys in plaintext columns (threat model degrades to pre-" +
                    "vault). Cause: ${e::class.simpleName}: ${e.message}",
            )
        }.getOrNull()

        if (sealed != null) {
            q.upsertIdentity(
                // Legacy plaintext columns empty as the "keys live in the
                // *Enc columns" sentinel.
                encPrivKey = ByteArray(0),
                sigPrivKey = ByteArray(0),
                ratchetPrivKey = null,
                encPrivKeyEnc = sealed.first,
                sigPrivKeyEnc = sealed.second,
                ratchetPrivKeyEnc = sealed.third,
            )
        } else {
            // Vault unavailable — persist plaintext to the base columns,
            // leave *Enc null. load() treats this as a legacy plaintext row.
            q.upsertIdentity(
                encPrivKey = identity.encPrivKey,
                sigPrivKey = identity.sigPrivKey,
                ratchetPrivKey = identity.ratchetPrivKey,
                encPrivKeyEnc = null,
                sigPrivKeyEnc = null,
                ratchetPrivKeyEnc = null,
            )
        }
    }

    override suspend fun load(): StoredIdentity? {
        val row = q.selectIdentity().executeAsOneOrNull() ?: return null
        val encEnc = row.encPrivKeyEnc
        val sigEnc = row.sigPrivKeyEnc
        if (encEnc != null && encEnc.isNotEmpty() &&
            sigEnc != null && sigEnc.isNotEmpty()
        ) {
            // Path A — sealed by KeychainIdentityVault. Try to unseal.
            runCatching {
                StoredIdentity(
                    encPrivKey = vault.unseal(encEnc),
                    sigPrivKey = vault.unseal(sigEnc),
                    ratchetPrivKey = row.ratchetPrivKeyEnc
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { vault.unseal(it) },
                )
            }.getOrNull()?.let { return it }

            // Path B — pre-Keychain iOS builds used PlaintextIdentityVault,
            // which wrote the *raw* 32-byte private keys straight into the
            // *Enc columns. A real private key is exactly 32 bytes; a
            // sealed blob is always longer (97 B for a 32-byte key), so
            // length cleanly disambiguates. Adopt the raw keys and re-save
            // through the vault to migrate this row to sealed in place.
            if (encEnc.size == KeychainIdentityVault.RAW_PRIV_KEY_LEN &&
                sigEnc.size == KeychainIdentityVault.RAW_PRIV_KEY_LEN
            ) {
                val migrated = StoredIdentity(
                    encPrivKey = encEnc.copyOf(),
                    sigPrivKey = sigEnc.copyOf(),
                    ratchetPrivKey = row.ratchetPrivKeyEnc
                        ?.takeIf { it.size == KeychainIdentityVault.RAW_PRIV_KEY_LEN }
                        ?.copyOf(),
                )
                runCatching { save(migrated) }  // best-effort upgrade to sealed
                return migrated
            }

            // Path C — base plaintext columns still populated (the save()
            // vault-unavailable fallback, or a very old row).
            legacyBaseColumns(row)?.let { return it }

            // Sealed, but the vault can't unseal it and there's no plaintext
            // fallback — the master key was lost (Keychain reset, or restore
            // onto a different device under ThisDeviceOnly). Surface it; do
            // NOT silently regenerate a new on-mesh identity.
            throw IllegalStateException(
                "Identity row exists but the Keychain vault cannot unseal it " +
                    "and no plaintext fallback is present. The wrapping key was " +
                    "likely removed (Keychain reset / restore to a new device). " +
                    "Re-import a .rmid backup.",
            )
        }
        // Base plaintext columns only (oldest path). Adopt and migrate to
        // sealed on the way out.
        val base = legacyBaseColumns(row) ?: return null
        runCatching { save(base) }
        return base
    }

    private fun legacyBaseColumns(
        row: io.github.thatsfguy.reticulum.storage.Identity,
    ): StoredIdentity? {
        if (row.encPrivKey.isEmpty() || row.sigPrivKey.isEmpty()) return null
        return StoredIdentity(row.encPrivKey, row.sigPrivKey, row.ratchetPrivKey)
    }
}

private class IosDestinationRepo(
    private val db: ReticulumIosDatabase,
    private val onChange: () -> Unit,
) : DestinationRepository {
    private val q get() = db.reticulumIosDatabaseQueries

    override suspend fun upsertFromAnnounce(record: StoredDestination) {
        q.upsertDestination(
            hash = record.hash,
            identityHash = record.identityHash,
            publicKey = record.publicKey,
            destHash = record.destHash,
            nameHash = record.nameHash,
            ratchetPub = record.ratchetPub,
            displayName = record.displayName,
            appName = record.appName,
            appLabel = record.appLabel,
            telemetryJson = record.telemetry?.let(::encodeTelemetryJson),
            lat = record.lat,
            lon = record.lon,
            appDataHex = record.appDataHex,
            lastSeen = record.lastSeen,
            rssi = record.rssi?.toLong(),
            favorite = if (record.favorite) 1L else 0L,
            source = record.source,
            hidden = if (record.hidden) 1L else 0L,
            hopCount = record.hopCount.toLong(),
            nextHop = record.nextHop,
            userLabel = record.userLabel,
        )
        onChange()
    }

    override suspend fun upsertManualStub(record: StoredDestination) {
        // Mirrors Android impl: if an existing row is there, preserve
        // its data and only update favorite/hidden/userLabel; otherwise
        // insert the manual stub as-is.
        val existing = q.selectDestination(record.hash).executeAsOneOrNull()
        if (existing == null) {
            upsertFromAnnounce(record)
        } else {
            q.upsertDestination(
                hash = existing.hash,
                identityHash = existing.identityHash,
                publicKey = existing.publicKey,
                destHash = existing.destHash,
                nameHash = existing.nameHash,
                ratchetPub = existing.ratchetPub,
                displayName = existing.displayName,
                appName = existing.appName,
                appLabel = existing.appLabel,
                telemetryJson = existing.telemetryJson,
                lat = existing.lat,
                lon = existing.lon,
                appDataHex = existing.appDataHex,
                lastSeen = existing.lastSeen,
                rssi = existing.rssi,
                favorite = 1L,
                source = existing.source,
                hidden = 0L,
                hopCount = existing.hopCount,
                nextHop = existing.nextHop,
                userLabel = record.userLabel?.takeIf { it.isNotBlank() } ?: existing.userLabel,
            )
            onChange()
        }
    }

    override suspend fun get(hash: String): StoredDestination? =
        q.selectDestination(hash).executeAsOneOrNull()?.toStoredDestination()

    override suspend fun getAll(): List<StoredDestination> =
        q.selectAllDestinations().executeAsList().map { it.toStoredDestination() }

    override suspend fun setFavorite(hash: String, favorite: Boolean) {
        q.setFavorite(if (favorite) 1L else 0L, hash)
        onChange()
    }

    override suspend fun setUserLabel(hash: String, label: String?) {
        // Empty/blank label clears it (NULL); same semantics as the
        // Android DestinationRepoImpl.
        val normalized = label?.takeIf { it.isNotBlank() }?.trim()
        q.setUserLabel(normalized, hash)
        onChange()
    }

    override suspend fun delete(hash: String) {
        q.hideDestination(hash)  // soft-delete, matches Room impl
        onChange()
    }

    override suspend fun deleteAll() {
        q.deleteAllDestinations()
        onChange()
    }

    override suspend fun evictUnfavoritedOldest(keepCount: Int): Int {
        // SQLDelight's generated delete returns Unit (not the affected
        // row count). We approximate "deleted count" via before/after
        // counts using selectAllDestinations().size — fine for
        // diagnostics-only logging; the actual eviction happens
        // regardless. Audit reference: 2026-05-13 MED-2.
        val before = q.selectAllDestinations().executeAsList().size
        q.evictUnfavoritedOldest(keepCount.toLong())
        val after = q.selectAllDestinations().executeAsList().size
        val deleted = (before - after).coerceAtLeast(0)
        if (deleted > 0) onChange()
        return deleted
    }
}

private class IosMessageRepo(
    private val db: ReticulumIosDatabase,
    private val onChange: () -> Unit,
) : MessageRepository {
    private val q get() = db.reticulumIosDatabaseQueries

    /**
     * INSERT + lastInsertRowId MUST run inside a SQLDelight transaction
     * on iOS, or `lastInsertRowId()` returns 0/stale because
     * NativeSqliteDriver has separate reader/writer connections —
     * the SELECT routes to a reader where `last_insert_rowid()`
     * scoped to that reader connection has never seen our INSERT,
     * so save() returns a junk id. The engine then calls
     * `updateState(thatJunkId, state="delivered", ...)`, the UPDATE's
     * `WHERE id = ?` matches no row, and the conversation view stays
     * pinned to state="pending" (the in-the-wild "hourglass stays
     * after delivery" symptom). [IosMessageRepoFlowTest] caught this
     * via emissions=[null, null] over a list that had size=1 both
     * times — the row was there, just with the real auto-increment
     * id, not the 0 we'd returned from save().
     */
    override suspend fun save(message: StoredMessage): Long =
        db.transactionWithResult {
            q.insertMessage(
                contactHash = message.contactHash,
                direction = message.direction,
                content = message.content,
                title = message.title,
                timestamp = message.timestamp,
                state = message.state,
                attempts = message.attempts.toLong(),
                lastAttempt = message.lastAttempt,
                lastError = message.lastError,
                rawPacket = message.rawPacket,
                packetHash = message.packetHash,
                rssi = message.rssi?.toLong(),
                hopCount = message.hopCount?.toLong(),
                imageBytes = message.imageBytes,
                messageId = message.messageId,
                replyToMessageId = message.replyToMessageId,
                reactionsJson = message.reactionsJson,
                arrivedViaDest = message.arrivedViaDest,
                attachmentName = message.attachmentName,
                attachmentBytes = message.attachmentBytes,
                imageToken = message.imageToken,
                imageSize = message.imageSize?.toLong(),
                attachmentToken = message.attachmentToken,
                attachmentSize = message.attachmentSize?.toLong(),
                audioMode = message.audioMode?.toLong(),
            )
            val id = q.lastInsertRowId().executeAsOne()
            afterCommit { onChange() }
            id
        }

    override suspend fun getById(id: Long): StoredMessage? =
        q.selectMessageById(id).executeAsOneOrNull()?.toStoredMessage()

    override suspend fun getForContact(contactHash: String): List<StoredMessage> =
        q.selectMessagesForContact(contactHash).executeAsList().map { it.toStoredMessage() }

    override suspend fun getAll(): List<StoredMessage> =
        q.selectAllMessages().executeAsList().map { it.toStoredMessage() }

    override suspend fun getOutgoingByPacketHash(hash: String): StoredMessage? =
        q.selectOutgoingByPacketHash(hash).executeAsOneOrNull()?.toStoredMessage()

    override suspend fun getByMessageId(messageId: String): StoredMessage? =
        q.selectMessageByMessageId(messageId).executeAsOneOrNull()?.toStoredMessage()

    override suspend fun setMessageId(rowId: Long, messageId: String) {
        q.setMessageId(messageId = messageId, rowId = rowId)
        onChange()
    }

    override suspend fun applyReaction(
        targetMessageId: String,
        emoji: String,
        senderHex: String,
    ): Boolean {
        // Same read-merge-write as the Android repo. SQLDelight
        // doesn't expose a single-statement upsert for "merge into
        // a JSON column" so we wrap in a transaction for atomicity
        // against concurrent reactors.
        return db.transactionWithResult {
            val row = q.selectMessageByMessageId(targetMessageId).executeAsOneOrNull()
                ?: return@transactionWithResult false
            val (newJson, changed) = io.github.thatsfguy.reticulum.store
                .ReactionsJson.applyReaction(row.reactionsJson, emoji, senderHex)
            if (changed) {
                q.setReactionsJson(json = newJson, rowId = row.id)
                afterCommit { onChange() }
            }
            true
        }
    }

    override suspend fun updateState(
        id: Long,
        state: String?,
        attempts: Int?,
        lastAttempt: Long?,
        lastError: String?,
        packetHash: String?,
    ) {
        q.updateMessageState(
            state = state,
            attempts = attempts?.toLong(),
            lastAttempt = lastAttempt,
            lastError = lastError,
            packetHash = packetHash,
            id = id,
        )
        onChange()
    }

    override suspend fun deleteForContact(contactHash: String) {
        q.deleteMessagesForContact(contactHash)
        onChange()
    }

    override suspend fun deleteById(id: Long) {
        q.deleteMessageById(id)
        onChange()
    }
}

private class IosNomadPageCacheRepo(
    private val db: ReticulumIosDatabase,
    private val onChange: () -> Unit,
) : NomadPageCacheRepository {
    private val q get() = db.reticulumIosDatabaseQueries

    override suspend fun put(page: StoredNomadPage) {
        q.upsertCachedPage(
            destHash = page.destHash,
            path = page.path,
            source = page.source,
            fetchedAt = page.fetchedAt,
            byteSize = page.byteSize.toLong(),
        )
        onChange()
    }

    override suspend fun get(destHash: String, path: String): StoredNomadPage? =
        q.selectCachedPage(destHash, path).executeAsOneOrNull()?.let {
            StoredNomadPage(it.destHash, it.path, it.source, it.fetchedAt, it.byteSize.toInt())
        }

    override suspend fun anyCachedFor(destHash: String): Boolean =
        q.anyCachedForDest(destHash).executeAsOne()

    override suspend fun clear(destHash: String, path: String) {
        q.deleteCachedPage(destHash, path)
        onChange()
    }

    override suspend fun clearAllForDest(destHash: String) {
        q.deleteCachedForDest(destHash)
        onChange()
    }

    override suspend fun clearAll() {
        q.deleteAllCached()
        onChange()
    }
}

/**
 * RRC storage actual — hubs, rooms, room message history. Backs the
 * `rrc_hub` / `rrc_room` / `rrc_message` tables. `joined` is an INTEGER
 * 0/1 column mapped to Boolean here, same as `destinations.favorite`.
 *
 * `deleteHub` / `deleteRoom` cascade by hand (SQLite has no ON DELETE
 * CASCADE configured for these tables) — matching the cascade contract
 * documented on the [RrcRepository] interface.
 */
private class IosRrcRepo(
    private val db: ReticulumIosDatabase,
    private val onChange: () -> Unit,
) : RrcRepository {
    private val q get() = db.reticulumIosDatabaseQueries

    override suspend fun upsertHub(hub: StoredRrcHub) {
        q.upsertRrcHub(
            destHash = hub.destHash,
            displayName = hub.displayName,
            nick = hub.nick,
            lastConnectedAt = hub.lastConnectedAt,
            addedAt = hub.addedAt,
        )
        onChange()
    }

    override suspend fun getHub(destHash: String): StoredRrcHub? =
        q.selectRrcHub(destHash).executeAsOneOrNull()?.let {
            StoredRrcHub(it.destHash, it.displayName, it.nick, it.lastConnectedAt, it.addedAt)
        }

    override suspend fun getAllHubs(): List<StoredRrcHub> =
        q.selectAllRrcHubs().executeAsList().map {
            StoredRrcHub(it.destHash, it.displayName, it.nick, it.lastConnectedAt, it.addedAt)
        }

    override suspend fun setHubLastConnected(destHash: String, whenMs: Long) {
        q.setRrcHubLastConnected(whenMs = whenMs, destHash = destHash)
        onChange()
    }

    override suspend fun deleteHub(destHash: String) {
        // Cascade — the hub row, its rooms, and all their messages.
        q.deleteRrcMessagesForHub(destHash)
        q.deleteRrcRoomsForHub(destHash)
        q.deleteRrcHub(destHash)
        onChange()
    }

    override suspend fun upsertRoom(room: StoredRrcRoom) {
        q.upsertRrcRoom(
            hubHash = room.hubHash,
            name = room.name,
            joined = if (room.joined) 1L else 0L,
            lastActivityAt = room.lastActivityAt,
        )
        onChange()
    }

    override suspend fun getRoomsForHub(hubHash: String): List<StoredRrcRoom> =
        q.selectRrcRoomsForHub(hubHash).executeAsList().map {
            StoredRrcRoom(it.hubHash, it.name, it.joined != 0L, it.lastActivityAt)
        }

    override suspend fun setRoomJoined(hubHash: String, name: String, joined: Boolean) {
        q.setRrcRoomJoined(joined = if (joined) 1L else 0L, hubHash = hubHash, name = name)
        onChange()
    }

    override suspend fun touchRoom(hubHash: String, name: String, activityMs: Long) {
        q.touchRrcRoom(activityMs = activityMs, hubHash = hubHash, name = name)
        onChange()
    }

    override suspend fun deleteRoom(hubHash: String, name: String) {
        // Cascade — the room row plus its messages.
        q.deleteRrcMessagesForRoom(hubHash, name)
        q.deleteRrcRoom(hubHash, name)
        onChange()
    }

    override suspend fun saveMessage(message: StoredRrcMessage): Long =
        // INSERT + last_insert_rowid() must share a transaction — see the
        // IosMessageRepo.save kdoc for the reader/writer-connection trap.
        db.transactionWithResult {
            q.insertRrcMessage(
                hubHash = message.hubHash,
                room = message.room,
                direction = message.direction,
                senderIdHash = message.senderIdHash,
                nick = message.nick,
                text = message.text,
                timestamp = message.timestamp,
                msgId = message.msgId,
            )
            val id = q.lastInsertRowId().executeAsOne()
            afterCommit { onChange() }
            id
        }

    override suspend fun getMessages(hubHash: String, room: String): List<StoredRrcMessage> =
        q.selectRrcMessages(hubHash, room).executeAsList().map {
            StoredRrcMessage(
                id = it.id,
                hubHash = it.hubHash,
                room = it.room,
                direction = it.direction,
                senderIdHash = it.senderIdHash,
                nick = it.nick,
                text = it.text,
                timestamp = it.timestamp,
                msgId = it.msgId,
            )
        }

    override suspend fun hasMessageId(hubHash: String, msgId: String): Boolean =
        q.rrcMessageIdExists(hubHash = hubHash, msgId = msgId).executeAsOne()

    override suspend fun deleteMessagesForRoom(hubHash: String, room: String) {
        q.deleteRrcMessagesForRoom(hubHash, room)
        onChange()
    }
}

// ---- mappers ----------------------------------------------------------

// SQLDelight generates a row class named after the table when SELECTs
// return all columns. Our queries do `SELECT *`, so the row type is
// `Destinations` / `Messages` / etc. — same shape as the table.

private fun io.github.thatsfguy.reticulum.storage.Destinations.toStoredDestination(): StoredDestination =
    StoredDestination(
        hash = hash,
        identityHash = identityHash,
        publicKey = publicKey,
        destHash = destHash,
        nameHash = nameHash,
        ratchetPub = ratchetPub,
        displayName = displayName,
        appName = appName,
        appLabel = appLabel,
        // runCatching here is load-bearing (2026-05-10): a single
        // corrupted/truncated telemetryJson row was throwing
        // "Unterminated JSON string at 1" out of our hand-rolled
        // parseTelemetryJson, which propagated up the
        // `observeDestinations().map { destinations.getAll() }`
        // collect coroutine, killed the subscriber via the v1.0.15
        // crashGuard, and made the entire Nodes list disappear from
        // the iOS UI until app restart. Tester's log showed three
        // copies of the throw right after a sleep/wake cycle, then
        // the destinations list went empty. A parse failure on ONE
        // row now degrades to `telemetry = null` for that row;
        // every other row renders normally and the offending row
        // at worst loses its telemetry sub-line. Println goes to
        // the iOS device console (Xcode "Devices and Simulators"
        // → Open Console) so the underlying corruption is still
        // diagnosable when it recurs.
        telemetry = telemetryJson?.let { json ->
            runCatching { parseTelemetryJson(json) }
                .onFailure { e ->
                    println("[IosDatabase] parseTelemetryJson failed for hash=$hash: ${e::class.simpleName}: ${e.message}; raw=${json.take(80)}")
                }
                .getOrNull()
        },
        lat = lat,
        lon = lon,
        appDataHex = appDataHex,
        lastSeen = lastSeen,
        rssi = rssi?.toInt(),
        favorite = favorite != 0L,
        source = source,
        hidden = hidden != 0L,
        hopCount = hopCount.toInt(),
        nextHop = nextHop,
        userLabel = userLabel,
    )

private fun io.github.thatsfguy.reticulum.storage.Messages.toStoredMessage(): StoredMessage =
    StoredMessage(
        id = id,
        contactHash = contactHash,
        direction = direction,
        content = content,
        title = title,
        timestamp = timestamp,
        state = state,
        attempts = attempts.toInt(),
        lastAttempt = lastAttempt,
        lastError = lastError,
        rawPacket = rawPacket,
        packetHash = packetHash,
        rssi = rssi?.toInt(),
        hopCount = hopCount?.toInt(),
        imageBytes = imageBytes,
        messageId = messageId,
        replyToMessageId = replyToMessageId,
        reactionsJson = reactionsJson,
        arrivedViaDest = arrivedViaDest,
        attachmentName = attachmentName,
        attachmentBytes = attachmentBytes,
        imageToken = imageToken,
        imageSize = imageSize?.toInt(),
        attachmentToken = attachmentToken,
        attachmentSize = attachmentSize?.toInt(),
        audioMode = audioMode?.toInt(),
    )

// Telemetry JSON encode/decode — deliberately the same trivial encoder
// the Android side uses (`androidApp/.../storage/Repositories.kt`)
// because pulling kotlinx.serialization in just for this
// flat-string-map field is overkill. Format: `{"k":"v","k2":"v2"}`.

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
