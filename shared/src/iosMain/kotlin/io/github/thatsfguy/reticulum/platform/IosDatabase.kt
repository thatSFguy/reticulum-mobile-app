package io.github.thatsfguy.reticulum.platform

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.github.thatsfguy.reticulum.storage.ReticulumIosDatabase
import io.github.thatsfguy.reticulum.store.DestinationRepository
import io.github.thatsfguy.reticulum.store.IdentityRepository
import io.github.thatsfguy.reticulum.store.MessageRepository
import io.github.thatsfguy.reticulum.store.NomadPageCacheRepository
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredIdentity
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.store.StoredNomadPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
class IosRepositories private constructor(
    val identity: IdentityRepository,
    val destinations: DestinationRepository,
    val messages: MessageRepository,
    val nomadPageCache: NomadPageCacheRepository,
    private val db: ReticulumIosDatabase,
) {
    /** Live stream of every observed/manual destination, sorted
     *  favorites-first then most-recently-seen. Mirrors the Room DAO's
     *  `observeAll()`. */
    fun observeDestinations(): Flow<List<StoredDestination>> =
        db.reticulumIosDatabaseQueries.observeAllDestinations()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toStoredDestination() } }

    /** Messages for a single conversation, oldest-first. */
    fun observeMessagesForContact(contactHash: String): Flow<List<StoredMessage>> =
        db.reticulumIosDatabaseQueries.observeMessagesForContact(contactHash)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toStoredMessage() } }

    /** Distinct sender dest hashes for every incoming message — drives
     *  the Messages-tab Inbox section. */
    fun observeIncomingContactHashes(): Flow<List<String>> =
        db.reticulumIosDatabaseQueries.observeIncomingContactHashes()
            .asFlow()
            .mapToList(Dispatchers.Default)

    /** destHashes that have at least one cached Nomad page. */
    fun observeCachedNomadDestHashes(): Flow<List<String>> =
        db.reticulumIosDatabaseQueries.observeCachedDestHashes()
            .asFlow()
            .mapToList(Dispatchers.Default)

    companion object {
        /** Build the singleton iOS repositories backed by an on-disk
         *  SQLite file at the standard NSDocumentDirectory location.
         *  [name] becomes the filename — useful for tests that want
         *  isolation. Production callers leave the default. */
        fun create(name: String = "reticulum.db"): IosRepositories {
            val driver = NativeSqliteDriver(ReticulumIosDatabase.Schema, name)
            val db = ReticulumIosDatabase(driver)
            return IosRepositories(
                identity = IosIdentityRepo(db),
                destinations = IosDestinationRepo(db),
                messages = IosMessageRepo(db),
                nomadPageCache = IosNomadPageCacheRepo(db),
                db = db,
            )
        }
    }
}

// ---- repository impls -------------------------------------------------

private class IosIdentityRepo(private val db: ReticulumIosDatabase) : IdentityRepository {
    private val q get() = db.reticulumIosDatabaseQueries
    override suspend fun save(identity: StoredIdentity) {
        q.upsertIdentity(identity.encPrivKey, identity.sigPrivKey, identity.ratchetPrivKey)
    }
    override suspend fun load(): StoredIdentity? =
        q.selectIdentity().executeAsOneOrNull()?.let {
            StoredIdentity(it.encPrivKey, it.sigPrivKey, it.ratchetPrivKey)
        }
}

private class IosDestinationRepo(
    private val db: ReticulumIosDatabase,
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
        }
    }

    override suspend fun get(hash: String): StoredDestination? =
        q.selectDestination(hash).executeAsOneOrNull()?.toStoredDestination()

    override suspend fun getAll(): List<StoredDestination> =
        q.selectAllDestinations().executeAsList().map { it.toStoredDestination() }

    override suspend fun setFavorite(hash: String, favorite: Boolean) {
        q.setFavorite(if (favorite) 1L else 0L, hash)
    }

    override suspend fun setUserLabel(hash: String, label: String?) {
        // Empty/blank label clears it (NULL); same semantics as the
        // Android DestinationRepoImpl.
        val normalized = label?.takeIf { it.isNotBlank() }?.trim()
        q.setUserLabel(normalized, hash)
    }

    override suspend fun delete(hash: String) {
        q.hideDestination(hash)  // soft-delete, matches Room impl
    }

    override suspend fun deleteAll() {
        q.deleteAllDestinations()
    }
}

private class IosMessageRepo(private val db: ReticulumIosDatabase) : MessageRepository {
    private val q get() = db.reticulumIosDatabaseQueries

    override suspend fun save(message: StoredMessage): Long {
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
        )
        return q.lastInsertRowId().executeAsOne()
    }

    override suspend fun getById(id: Long): StoredMessage? =
        q.selectMessageById(id).executeAsOneOrNull()?.toStoredMessage()

    override suspend fun getForContact(contactHash: String): List<StoredMessage> =
        q.selectMessagesForContact(contactHash).executeAsList().map { it.toStoredMessage() }

    override suspend fun getAll(): List<StoredMessage> =
        q.selectAllMessages().executeAsList().map { it.toStoredMessage() }

    override suspend fun getOutgoingByPacketHash(hash: String): StoredMessage? =
        q.selectOutgoingByPacketHash(hash).executeAsOneOrNull()?.toStoredMessage()

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
    }

    override suspend fun deleteForContact(contactHash: String) {
        q.deleteMessagesForContact(contactHash)
    }
}

private class IosNomadPageCacheRepo(
    private val db: ReticulumIosDatabase,
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
    }

    override suspend fun get(destHash: String, path: String): StoredNomadPage? =
        q.selectCachedPage(destHash, path).executeAsOneOrNull()?.let {
            StoredNomadPage(it.destHash, it.path, it.source, it.fetchedAt, it.byteSize.toInt())
        }

    override suspend fun anyCachedFor(destHash: String): Boolean =
        q.anyCachedForDest(destHash).executeAsOne()

    override suspend fun clear(destHash: String, path: String) {
        q.deleteCachedPage(destHash, path)
    }

    override suspend fun clearAllForDest(destHash: String) {
        q.deleteCachedForDest(destHash)
    }

    override suspend fun clearAll() {
        q.deleteAllCached()
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
        telemetry = telemetryJson?.let(::parseTelemetryJson),
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
