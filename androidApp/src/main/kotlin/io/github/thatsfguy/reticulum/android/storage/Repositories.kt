package io.github.thatsfguy.reticulum.android.storage

import android.content.Context
import io.github.thatsfguy.reticulum.store.ContactRepository
import io.github.thatsfguy.reticulum.store.IdentityRepository
import io.github.thatsfguy.reticulum.store.MessageRepository
import io.github.thatsfguy.reticulum.store.NodeRepository
import io.github.thatsfguy.reticulum.store.StoredContact
import io.github.thatsfguy.reticulum.store.StoredIdentity
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.store.StoredNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Concrete container for all four repositories. Use [Repositories.create]
 * with an Application Context.
 */
class Repositories private constructor(
    val identity: IdentityRepository,
    val contacts: ContactRepository,
    val messages: MessageRepository,
    val nodes: NodeRepository,
    private val db: ReticulumDatabase,
) {
    /** Flow of contacts ordered by lastSeen desc, for UI consumption. */
    fun observeContacts(): Flow<List<StoredContact>> =
        db.contactDao().observeAll().map { rows -> rows.map { it.toModel() } }

    fun observeMessagesForContact(contactHash: String): Flow<List<StoredMessage>> =
        db.messageDao().observeForContact(contactHash).map { rows -> rows.map { it.toModel() } }

    fun observeNodes(): Flow<List<StoredNode>> =
        db.nodeDao().observeAll().map { rows -> rows.map { it.toModel() } }

    companion object {
        fun create(context: Context): Repositories {
            val db = ReticulumDatabase.get(context)
            return Repositories(
                identity = IdentityRepoImpl(db.identityDao()),
                contacts = ContactRepoImpl(db.contactDao()),
                messages = MessageRepoImpl(db.messageDao()),
                nodes    = NodeRepoImpl(db.nodeDao()),
                db = db,
            )
        }
    }
}

private class IdentityRepoImpl(private val dao: IdentityDao) : IdentityRepository {
    override suspend fun save(identity: StoredIdentity) {
        dao.upsert(IdentityEntity(0, identity.encPrivKey, identity.sigPrivKey, identity.ratchetPrivKey))
    }
    override suspend fun load(): StoredIdentity? = dao.load()?.toModel()
}

private class ContactRepoImpl(private val dao: ContactDao) : ContactRepository {
    override suspend fun save(contact: StoredContact)             = dao.upsert(contact.toEntity())
    override suspend fun get(hash: String): StoredContact?        = dao.get(hash)?.toModel()
    override suspend fun getAll(): List<StoredContact>            = dao.getAll().map { it.toModel() }
    override suspend fun delete(hash: String)                     = dao.delete(hash)
}

private class MessageRepoImpl(private val dao: MessageDao) : MessageRepository {
    override suspend fun save(message: StoredMessage): Long       = dao.insert(message.toEntity())
    override suspend fun getById(id: Long): StoredMessage?        = dao.getById(id)?.toModel()
    override suspend fun getForContact(contactHash: String)       = dao.getForContact(contactHash).map { it.toModel() }
    override suspend fun getAll(): List<StoredMessage>            = dao.getAll().map { it.toModel() }
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

private class NodeRepoImpl(private val dao: NodeDao) : NodeRepository {
    override suspend fun save(node: StoredNode)                   = dao.upsert(node.toEntity())
    override suspend fun getAll(): List<StoredNode>               = dao.getAll().map { it.toModel() }
    override suspend fun delete(hash: String)                     = dao.delete(hash)
    override suspend fun deleteAll()                              = dao.deleteAll()
}

// ---- Mappers ----------------------------------------------------------

private fun IdentityEntity.toModel() = StoredIdentity(encPrivKey, sigPrivKey, ratchetPrivKey)

private fun ContactEntity.toModel() = StoredContact(
    hash, identityHash, publicKey, destHash, nameHash,
    ratchetPub, displayName, lastSeen, rssi,
)
private fun StoredContact.toEntity() = ContactEntity(
    hash, identityHash, publicKey, destHash, nameHash,
    ratchetPub, displayName, lastSeen, rssi,
)

private fun MessageEntity.toModel() = StoredMessage(
    id, contactHash, direction, content, title, timestamp, state, attempts,
    lastAttempt, lastError, rawPacket, packetHash, rssi,
)
private fun StoredMessage.toEntity() = MessageEntity(
    id, contactHash, direction, content, title, timestamp, state, attempts,
    lastAttempt, lastError, rawPacket, packetHash, rssi,
)

private fun NodeEntity.toModel() = StoredNode(
    hash, identityHash, nameHash, appName, appLabel, displayName,
    telemetry = telemetryJson?.let(::parseTelemetryJson),
    lat = lat, lon = lon,
    appDataHex = appDataHex, lastSeen = lastSeen, rssi = rssi,
)
private fun StoredNode.toEntity() = NodeEntity(
    hash, identityHash, nameHash, appName, appLabel, displayName,
    telemetryJson = telemetry?.let(::encodeTelemetryJson),
    lat = lat, lon = lon,
    appDataHex = appDataHex, lastSeen = lastSeen, rssi = rssi,
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
