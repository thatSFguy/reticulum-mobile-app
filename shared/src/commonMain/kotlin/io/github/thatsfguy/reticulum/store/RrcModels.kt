package io.github.thatsfguy.reticulum.store

/**
 * Persistence models + repository contract for Reticulum Relay Chat
 * (RRC) — see `rrc/RrcConstants.kt` for the wire protocol.
 *
 * RRC storage is deliberately a separate set of `rrc_*` tables rather
 * than reusing the LXMF [StoredMessage] / [StoredDestination] tables:
 *
 *  - an RRC message is room-scoped and carries a sender identity hash
 *    plus a nick, which the 1:1 LXMF [StoredMessage] has no slot for;
 *  - keeping RRC isolated means every existing Messages / Nodes query
 *    is untouched, so the experimental `experimentalRrc` flag can gate
 *    the whole feature without risk of leaking RRC rows into the LXMF
 *    contact list.
 *
 * Platform-independent; the storage backend (Room on Android,
 * SQLDelight on iOS) implements [RrcRepository].
 */

/**
 * A known RRC hub — one RNS destination the user has connected to (or
 * added manually). The session opens a Link to [destHash], identifies,
 * and exchanges CBOR envelopes; see `engine/RrcSession.kt`.
 */
data class StoredRrcHub(
    /** Hub RNS destination hash, hex (32 chars) — primary key. */
    val destHash: String,
    /**
     * Hub name to render in the Rooms list. Seeded from whatever the
     * user typed when adding the hub, then overwritten with the
     * authoritative `hubName` from the hub's WELCOME once connected.
     */
    val displayName: String,
    /** The nick we present on this hub. Null = let the hub assign one. */
    val nick: String? = null,
    /** Wall-clock ms of the most recent successful WELCOME; 0 if never. */
    val lastConnectedAt: Long = 0,
    /** Wall-clock ms when the user first added this hub. */
    val addedAt: Long,
)

/**
 * A room on a hub. Composite-keyed by [hubHash] + [name]. [joined]
 * tracks whether the user is a confirmed member so the session can
 * auto-rejoin every joined room after a reconnect.
 */
data class StoredRrcRoom(
    /** Owning hub's [StoredRrcHub.destHash]. */
    val hubHash: String,
    /** Room name (the RRC `K_ROOM` string). */
    val name: String,
    /** True once a JOINED for this room has confirmed our membership. */
    val joined: Boolean = false,
    /**
     * Wall-clock ms of the most recent message in or out of this room.
     * Drives the room-list sort so active rooms float to the top.
     */
    val lastActivityAt: Long = 0,
    /**
     * Highest [StoredRrcMessage.id] the user has seen in this room.
     * Anything above it is unread. Row id — not timestamp — because a
     * room's timestamps come from the mutually-skewed clocks of every
     * member (see [RrcRepository.getMessages]); the hub's fan-out
     * order is the only sequence all members agree on.
     */
    val lastReadMessageId: Long = 0,
    /**
     * When to raise a notification for this room: [NOTIFY_ALL],
     * [NOTIFY_MENTIONS] (only messages naming us — see `RrcMentions`),
     * or [NOTIFY_NONE]. Per room, because one busy room should not
     * cost the user every other one.
     */
    val notifyMode: String = NOTIFY_ALL,
) {
    companion object {
        const val NOTIFY_ALL = "all"
        const val NOTIFY_MENTIONS = "mentions"
        const val NOTIFY_NONE = "none"
    }
}

/**
 * One line in an RRC room's timeline: `incoming` (fanned out by the
 * hub), `outgoing` (sent by us), `system` (a `/`-command or a hub
 * NOTICE attributed to the room), or `error` (a hub refusal).
 * Persisted so room history survives an app restart.
 */
data class StoredRrcMessage(
    val id: Long = 0,
    /** Owning hub's [StoredRrcHub.destHash]. */
    val hubHash: String,
    /** Room name this message belongs to. */
    val room: String,
    /** "incoming", "outgoing", "system", or "error". */
    val direction: String,
    /**
     * Sender's RNS identity hash, hex (32 chars). For outgoing rows
     * this is our own identity hash; for incoming rows it is the
     * hub-verified `K_SRC` value.
     */
    val senderIdHash: String,
    /** Sender nick at send time, if the envelope carried one. */
    val nick: String? = null,
    /** Message text (RRC `K_BODY`). */
    val text: String,
    /** Envelope `K_TS` — sender's clock, ms since epoch. */
    val timestamp: Long,
    /**
     * Envelope `K_ID` (8 random bytes) hex-encoded, 16 chars. Used to
     * dedup the hub echoing our own message back to us, and to dedup
     * a replayed fan-out. Null on rows saved before this column or
     * when the envelope omitted an id.
     */
    val msgId: String? = null,
    /**
     * True when this line names us — `@nick` or `@hashprefix` in an
     * incoming message, or a mention alert the hub sent because we
     * were somewhere else (`client-parity.md` §8). Drives the
     * highlight and the mentions-only notification mode.
     */
    val mention: Boolean = false,
    /**
     * `K_ID` (hex) of the message this one replies to, or null
     * (`rrc-extensions.md` key 64). A reply whose target is not held
     * still renders as an ordinary message — the anchor is decoration,
     * not a precondition.
     */
    val replyToMsgId: String? = null,
    /**
     * Reactions aggregated onto this message, as [ReactionsJson] —
     * emoji → the identity hashes holding it. Nothing is aggregated on
     * the wire or by the hub; each reaction arrives as its own message
     * and is folded in here.
     */
    val reactionsJson: String? = null,
)

/**
 * Storage for the RRC feature: known hubs, joined rooms, and room
 * message history. Implementations must be safe to call from a single
 * coroutine context; no cross-method atomicity is assumed.
 */
interface RrcRepository {

    // ---- hubs ---------------------------------------------------------

    /** Insert or replace a hub row, keyed on [StoredRrcHub.destHash]. */
    suspend fun upsertHub(hub: StoredRrcHub)

    suspend fun getHub(destHash: String): StoredRrcHub?

    suspend fun getAllHubs(): List<StoredRrcHub>

    /** Stamp a hub's [StoredRrcHub.lastConnectedAt]; no-op if unknown. */
    suspend fun setHubLastConnected(destHash: String, whenMs: Long)

    /**
     * Delete a hub and cascade-delete its rooms and messages. Idempotent
     * — deleting an unknown hash is a no-op.
     */
    suspend fun deleteHub(destHash: String)

    // ---- rooms --------------------------------------------------------

    /** Insert or replace a room row, keyed on (hubHash, name). */
    suspend fun upsertRoom(room: StoredRrcRoom)

    suspend fun getRoomsForHub(hubHash: String): List<StoredRrcRoom>

    /** Set the [StoredRrcRoom.joined] flag; no-op if the room is unknown. */
    suspend fun setRoomJoined(hubHash: String, name: String, joined: Boolean)

    /**
     * Bump a room's [StoredRrcRoom.lastActivityAt] to [activityMs] when
     * that is newer than the stored value; no-op if the room is unknown.
     */
    suspend fun touchRoom(hubHash: String, name: String, activityMs: Long)

    /** Delete a room and its messages. Idempotent. */
    suspend fun deleteRoom(hubHash: String, name: String)

    // ---- messages -----------------------------------------------------

    /** Persist a message; returns the assigned row id. */
    suspend fun saveMessage(message: StoredRrcMessage): Long

    /**
     * Room history in arrival order (ascending row id) — NOT timestamp
     * order. The hub forwards each sender's own `K_TS` unchanged, so
     * timestamps come from mutually-skewed clocks and cannot order a
     * multi-party room; row id is the hub's single fan-out sequence.
     */
    suspend fun getMessages(hubHash: String, room: String): List<StoredRrcMessage>

    /**
     * True if a message with [msgId] already exists for [hubHash].
     * Lets the session drop a hub echo / replayed fan-out before it
     * reaches [saveMessage].
     */
    suspend fun hasMessageId(hubHash: String, msgId: String): Boolean

    /** Delete every message in a room. */
    suspend fun deleteMessagesForRoom(hubHash: String, room: String)

    /**
     * Apply (or with [retract], remove) [emoji] from [senderHex] on the
     * message with envelope id [msgId] **in [room]**, returning true if
     * a row was found and changed.
     *
     * Scoping to the room is a requirement, not an optimisation: a
     * `K_ID` is 8 sender-chosen random bytes that no hub enforces
     * uniqueness on, so resolving one across rooms would let a reaction
     * be steered onto an unrelated message (`rrc-extensions.md` §5).
     *
     * The default is a deliberate no-op for a backend with no reactions
     * column yet — iOS, until its own UI pass — so an inbound reaction
     * is dropped, which is what the spec says to do with one whose
     * target cannot be resolved.
     */
    suspend fun applyReaction(
        hubHash: String,
        room: String,
        msgId: String,
        emoji: String,
        senderHex: String,
        retract: Boolean,
    ): Boolean = false
}
