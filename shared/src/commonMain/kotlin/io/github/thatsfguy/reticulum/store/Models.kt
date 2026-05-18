package io.github.thatsfguy.reticulum.store

/**
 * Data models for persistence. Platform-independent; storage backend
 * (Room on Android, SQLDelight on iOS) implements the Repository interfaces.
 */

data class StoredIdentity(
    val encPrivKey: ByteArray,
    val sigPrivKey: ByteArray,
    val ratchetPrivKey: ByteArray?,
)

/**
 * Every announced (or manually-added) Reticulum destination lives here.
 * lxmf.delivery destinations can be messaged once their public key is known;
 * other destinations (telemetry beacons, transport broadcasts, etc.) are
 * just observed.
 */
data class StoredDestination(
    val hash: String,                     // destination hash hex — primary key
    val identityHash: String,             // hex; empty until a public key is known
    val publicKey: ByteArray,             // 64 bytes (X25519 || Ed25519); empty for manual stubs
    val destHash: ByteArray,              // 16 bytes — same as hash, decoded
    val nameHash: ByteArray,              // 10 bytes; empty for manual stubs until announce
    val ratchetPub: ByteArray?,           // 32 bytes; null if announce had no ratchet
    val displayName: String,
    val appName: String?,                 // looked up from name_hash (e.g. "lxmf.delivery")
    val appLabel: String?,                // human label for appName
    val telemetry: Map<String, String>?,  // parsed key=value pairs (RLR)
    val lat: Double?,
    val lon: Double?,
    val appDataHex: String,
    val lastSeen: Long,
    val rssi: Int?,
    val favorite: Boolean,                // user-starred → promoted to Messages tab
    val source: String,                   // "announce" | "manual" | "qr"
    val hidden: Boolean = false,          // soft-delete: kept in DB but filtered from lists; auto-cleared on re-announce
    val hopCount: Int = 0,                // hops byte from the most recent announce (0 = directly attached, higher = further)
    val nextHop: ByteArray? = null,       // 16-byte transport_id captured from the most recent HEADER_2 announce; null if we only saw a HEADER_1 (direct) announce. Required for §2.3 originator HEADER_1→HEADER_2 conversion when sending DATA through a transit transport — without it, upstream RNS Transport drops our outbound at RNS/Transport.py:1497.
    val userLabel: String? = null,        // local-only nickname the user assigned to this contact. Wins over [displayName] for rendering everywhere. Persisted across announces (upsertFromAnnounce/applyIdentityCard preserve it). Never sent on the wire.
) {
    /** A destination is messagable if we have its public key and it's an LXMF delivery dest. */
    val isMessagable: Boolean
        get() = publicKey.size == 64 && appName == "lxmf.delivery"

    /**
     * Name to render in the UI: [userLabel] if the user set one, else the
     * announce-derived [displayName]. Trims blanks so an empty user-label
     * input doesn't shadow a real announced name.
     */
    val effectiveDisplayName: String
        get() = userLabel?.takeIf { it.isNotBlank() } ?: displayName
}

data class StoredMessage(
    val id: Long = 0,
    val contactHash: String,           // destHash hex of the conversation partner
    val direction: String,             // "incoming" or "outgoing"
    val content: String,
    val title: String = "",
    val timestamp: Long,
    val state: String? = null,
    val attempts: Int = 0,
    val lastAttempt: Long = 0,
    val lastError: String? = null,
    val rawPacket: ByteArray? = null,
    val packetHash: String? = null,
    val rssi: Int? = null,
    /** Hop count on the inbound packet that delivered this message.
     *  Persisted per-message so the chat view can show "RSSI -85 dBm
     *  · 2 hops" alongside each incoming bubble — gives the user
     *  per-message link quality at a glance instead of just the
     *  per-destination summary on the Nodes tab. Null on outgoing
     *  messages and on messages saved before v0.1.85. */
    val hopCount: Int? = null,
    /** Compressed JPEG bytes for an attached image, LXMF
     *  `FIELD_IMAGE` (integer msgpack key 6 — wire-compatible with
     *  Sideband + Columba). Outbound: filled by the picker in
     *  Phase 2 before `engine.sendMessage` saves the row. Inbound:
     *  populated from `fields[6]` in `handleIncomingLxmf` if the
     *  payload is ≤32 KB (a defensive ceiling against a hostile
     *  peer shipping a 10 MB blob; the sender-side ladder caps at
     *  20 KB so legitimate messages always fit). Null when no image
     *  was attached. */
    val imageBytes: ByteArray? = null,
    /** Canonical LXMF `message_id` (32-byte SHA-256 over
     *  `dest_hash || source_hash || packed_payload_4_elements`,
     *  see `LxmfStamp.computeMessageId`). Hex-encoded, 64 chars.
     *  Persisted on both inbound and outbound rows so reactions
     *  and replies — which target a message by its message_id —
     *  can find the right local row across devices. Null on rows
     *  saved before v1.1.33; reactions/replies that target such
     *  rows are silently dropped. */
    val messageId: String? = null,
    /** When this row is a reply (Columba/Sideband convention,
     *  LXMF field 16 sub-key `"reply_to"`), the `messageId` of
     *  the message being replied to. The reply preview at the
     *  top of the bubble is rendered by looking up that row
     *  locally — Columba doesn't embed the quoted text, just the
     *  reference. Null on normal (non-reply) messages. */
    val replyToMessageId: String? = null,
    /** Reactions aggregated onto this message, as a JSON string
     *  in the shape `{"👍":["sender_hex_16","sender_hex_16"],
     *  "❤️":["..."]}`. Encoded/decoded via the helpers in
     *  `store/ReactionsJson.kt`. Each incoming LXMF reaction
     *  (a separate empty-body message with field 16
     *  `{"reaction_to":...,"emoji":...,"sender":...}`) merges
     *  one sender into the matching emoji's list. Null when no
     *  reactions are present. */
    val reactionsJson: String? = null,
    /** lxmf.delivery destHash hex (16 B) of the link this inbound
     *  message arrived over, when that peer differs from the LXMF
     *  body's `source_hash`. The fwdsvc relay case: link is from
     *  fwdsvc → us, LXMF source = the original group sender. We tag
     *  the row with `arrivedViaDest = fwdsvc` so a later tap-back
     *  reaction or swipe-reply routes through fwdsvc for fanout
     *  instead of egressing direct to the original sender (which
     *  would bypass the group entirely). Populated only when the
     *  carrying link's initiator sent a valid SPEC §6.6 LINKIDENTIFY
     *  (context 0xFB) — without that we can't trust the link peer's
     *  identity, so we keep the legacy direct-to-source routing. */
    val arrivedViaDest: String? = null,
    /** Sanitised file name of a received LXMF `FIELD_FILE_ATTACHMENTS`
     *  (key 5) attachment — see SPEC §5.9.7 and
     *  `engine/extractFileAttachments`. Null when the message carried
     *  no file. When a message carries multiple files only the first
     *  is persisted (Sideband sends one per message). */
    val attachmentName: String? = null,
    /** Raw bytes of the received file attachment, ≤ 256 KB (the
     *  `INBOUND_FILE_MAX_BYTES` receive cap). Null when no file was
     *  attached. The UI offers tap-to-save; the bytes are never
     *  auto-opened. */
    val attachmentBytes: ByteArray? = null,
)

interface IdentityRepository {
    suspend fun save(identity: StoredIdentity)
    suspend fun load(): StoredIdentity?
}

/**
 * Unified repository for every observed/manual destination. Replaces the
 * old separate Contact/Node repositories — partition is by query, not by
 * table. Implementation must MERGE on conflict (preserve favorite flag and
 * source field on inbound announces) rather than wholesale replace.
 */
interface DestinationRepository {
    /** Upsert an announce-derived row, merging with any existing record. */
    suspend fun upsertFromAnnounce(record: StoredDestination)

    /** Insert a manual stub if no row exists; no-op if one already does. */
    suspend fun upsertManualStub(record: StoredDestination)

    suspend fun get(hash: String): StoredDestination?
    suspend fun getAll(): List<StoredDestination>
    suspend fun setFavorite(hash: String, favorite: Boolean)
    /** Set or clear the user's local nickname for this contact. Pass null
     *  or blank to clear; the row's [StoredDestination.displayName] then
     *  becomes the effective label again. */
    suspend fun setUserLabel(hash: String, label: String?)
    suspend fun delete(hash: String)
    suspend fun deleteAll()

    /**
     * MED-2 announce-flood eviction. Delete unfavorited, non-user-
     * labeled, non-hidden rows past [keepCount] when sorted by
     * `lastSeen` DESC. Favorited contacts, user-renamed entries,
     * and soft-deleted rows are preserved regardless of count.
     *
     * Returns the number of rows deleted so the engine can log it.
     * Implementations should be idempotent (deleting zero rows when
     * already under the cap is fine).
     *
     * Audit reference: 2026-05-13 MED-2.
     */
    suspend fun evictUnfavoritedOldest(keepCount: Int): Int
}

/**
 * Cache of fetched NomadNet pages. Keyed by `(destHash, path)`. Stores the
 * raw response bytes (decoded micron source) plus when we got it, so the
 * UI can render the previous version while a fresh fetch is in flight and
 * show "last pulled Xm ago" timestamps. Cleared explicitly via the page
 * view's "Clear cache" button or implicitly when a fresh fetch succeeds
 * (the new bytes overwrite the old).
 */
data class StoredNomadPage(
    val destHash: String,           // hex (32 chars) — composite-key with path
    val path: String,               // e.g. "/page/index.mu"
    val source: String,             // micron source, UTF-8 decoded
    val fetchedAt: Long,            // wall-clock ms when fetched
    val byteSize: Int,              // raw bytes received (pre-decode)
)

interface NomadPageCacheRepository {
    suspend fun put(page: StoredNomadPage)
    suspend fun get(destHash: String, path: String): StoredNomadPage?
    /** Latest cached page per destHash — used for the Nomad list "has cache" indicator. */
    suspend fun anyCachedFor(destHash: String): Boolean
    suspend fun clear(destHash: String, path: String)
    suspend fun clearAllForDest(destHash: String)
    suspend fun clearAll()
}

interface MessageRepository {
    suspend fun save(message: StoredMessage): Long
    suspend fun getById(id: Long): StoredMessage?
    suspend fun getForContact(contactHash: String): List<StoredMessage>
    suspend fun getAll(): List<StoredMessage>
    suspend fun getOutgoingByPacketHash(hash: String): StoredMessage?

    /** Find a message by its canonical LXMF [messageId] (32-byte
     *  hex hash). Returns the row that was sent or received with
     *  this id, or null if it never arrived locally. Used by the
     *  reaction-dispatch path to locate the target of an inbound
     *  reaction, and by the reply-preview render to look up the
     *  quoted message. */
    suspend fun getByMessageId(messageId: String): StoredMessage?

    /** Persist [messageId] on the row identified by [rowId]. The
     *  engine computes the LXMF message_id during pack (outbound)
     *  or unpack (inbound) and writes it back here so reactions
     *  and replies can target the row across devices. */
    suspend fun setMessageId(rowId: Long, messageId: String)

    /** Merge an inbound reaction into the target row's
     *  `reactionsJson`. [targetMessageId] is the LXMF message_id
     *  the reaction is for; [emoji] is the unicode glyph;
     *  [senderHex] is the source identity hash (16-byte hex) of
     *  whoever reacted. Idempotent — re-applying the same
     *  (target, emoji, sender) triple is a no-op. Returns true if
     *  the target row was found and the reaction was applied
     *  (or already present), false if no row matches [targetMessageId]
     *  (reaction is silently dropped — matching Columba's behavior;
     *  future versions could buffer pending reactions keyed by
     *  message_id). */
    suspend fun applyReaction(
        targetMessageId: String,
        emoji: String,
        senderHex: String,
    ): Boolean

    suspend fun updateState(
        id: Long,
        state: String? = null,
        attempts: Int? = null,
        lastAttempt: Long? = null,
        lastError: String? = null,
        packetHash: String? = null,
    )
    suspend fun deleteForContact(contactHash: String)
}
