package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.store.RrcRepository
import io.github.thatsfguy.reticulum.store.StoredRrcMessage
import io.github.thatsfguy.reticulum.store.StoredRrcRoom

/**
 * Bridges an [RrcSession]'s [RrcEvent] stream into the [RrcRepository]
 * so RRC room history and hub state survive an app restart.
 *
 * Scope is deliberately narrow — only the events whose persistence is
 * *unambiguous* are handled here:
 *
 *  - [RrcEvent.Welcomed] stamps the hub's last-connected time;
 *  - [RrcEvent.RoomMessage] (always an inbound message fanned out by
 *    the hub) is saved as an `incoming` row, deduped by envelope id;
 *  - [RrcEvent.RoomSystemMessage] — a `/`-command, a hub reply, or any
 *    NOTICE / ERROR the hub attributed to a room — is saved as a
 *    `system` / `error` row so it renders inline in the timeline;
 *  - [RrcEvent.Joined] / [RrcEvent.Parted] with `isSelf` set, which is
 *    the hub telling us *our own* membership changed.
 *
 * The engine still persists membership from its own explicit `join` /
 * `part` calls — those are the common path and know the intent up
 * front. What the events add is the case the engine cannot see: a
 * hub-side add (an invite) or removal (a `/kick`, a ban), where the
 * room row would otherwise stay wrong until the user noticed. A
 * roster-less `Joined` / `Parted` for somebody else carries
 * `isSelf = false` and is ignored here. Outgoing messages are likewise
 * persisted by the engine at send time via [recordOutgoing] —
 * [RrcSession] emits no event for our own sends.
 */
class RrcPersistence(
    private val repo: RrcRepository,
    private val nowMs: () -> Long,
    private val logger: (String) -> Unit = {},
) {

    /** The most recent system line stored per `hub/room`, so the same
     *  line repeated back-to-back is stored once. The hub re-sends its
     *  room-info NOTICE on every JOIN, and we auto-rejoin every room on
     *  every reconnect — without this a flaky link slowly fills the
     *  timeline with identical lines. In-memory by design: one
     *  duplicate across an app restart is not worth a schema read on
     *  every notice. */
    private val lastSystemLine = mutableMapOf<String, String>()

    /** Direction tags — `incoming` / `outgoing` mirror the LXMF
     *  `messages` table; `system` and `error` are RRC-only (a
     *  `/`-command line, and the hub's refusal of one). */
    private companion object {
        const val INCOMING = "incoming"
        const val OUTGOING = "outgoing"
        const val SYSTEM = "system"
        const val ERROR = "error"
    }

    /** Persist whatever [event] on [hubHash] warrants persistence. */
    suspend fun onEvent(hubHash: String, event: RrcEvent) {
        when (event) {
            is RrcEvent.Welcomed -> {
                repo.setHubLastConnected(hubHash, nowMs())
                // Refresh the row's displayName with the hub's
                // authoritative `hubName` from WELCOME. Pre-fix, an
                // old StoredRrcHub created against a pre-CBOR-aware
                // engine (android-v1.2.2 and earlier) could keep the
                // bogus `"epr"` literal forever — the announce path
                // updates StoredDestination but never propagated to
                // the rrc_hubs row. Tester report: "Rooms page shows
                // 'epr' until the user connects." Now: connect once
                // and the row repairs itself with the hub's self-
                // declared name. Idempotent — guarded against blank
                // hubName (some hubs ship a WELCOME without one).
                val hubName = event.hubName?.takeIf { it.isNotBlank() }
                if (hubName != null) {
                    val existing = repo.getHub(hubHash)
                    if (existing != null && existing.displayName != hubName) {
                        repo.upsertHub(existing.copy(displayName = hubName))
                    }
                }
            }
            is RrcEvent.RoomMessage -> persistInbound(hubHash, event)
            is RrcEvent.RoomSystemMessage -> persistSystem(hubHash, event)
            is RrcEvent.Joined -> if (event.isSelf) {
                // The hub put us in a room. Usually our own JOIN, which
                // the engine has already written — upserting is
                // idempotent; what matters is the case it has not, e.g.
                // an invite, where without this the messages would
                // arrive for a room with no row to open.
                val existing = repo.getRoomsForHub(hubHash).firstOrNull { it.name == event.room }
                repo.upsertRoom(
                    (existing ?: StoredRrcRoom(hubHash = hubHash, name = event.room))
                        .copy(joined = true),
                )
            }
            is RrcEvent.Parted -> if (event.isSelf) {
                repo.setRoomJoined(hubHash, event.room, false)
            }
            is RrcEvent.RoomReaction -> {
                // Reactions are aggregated onto their target and never
                // stored as a line of their own. A target we don't hold
                // is dropped silently: a client that joined recently
                // legitimately does not have it, and a stray emoji in
                // the transcript is worse than nothing
                // (`rrc-extensions.md` §3).
                val changed = repo.applyReaction(
                    hubHash = hubHash,
                    room = event.room,
                    msgId = event.targetMsgId,
                    emoji = event.emoji,
                    senderHex = event.senderIdHash,
                    retract = event.retract,
                )
                if (!changed) {
                    logger("RRC reaction on unheld/unchanged ${event.targetMsgId} in ${event.room}")
                }
            }
            // Notice / HubError / StateChanged, the room topic/mode
            // updates and the member roster are transient — see the
            // class kdoc. Topic/modes/members live in volatile UI state
            // (the hub re-announces them on every JOIN).
            is RrcEvent.Notice,
            is RrcEvent.HubError,
            is RrcEvent.RoomTopic,
            is RrcEvent.RoomModes,
            is RrcEvent.RoomMembers,
            is RrcEvent.RoomRoster,
            is RrcEvent.RoomList,
            is RrcEvent.StateChanged,
            // Nothing reconnected, so nothing to stamp — see the event's
            // own doc for why it is separate from Welcomed.
            is RrcEvent.SessionResumed -> Unit
        }
    }

    /**
     * Persist a system line — a `/`-command the user ran or the hub's
     * reply to it — as a `system`-direction row so it renders inline in
     * the room timeline. No sender, no msgId (nothing to dedup against).
     */
    private suspend fun persistSystem(hubHash: String, m: RrcEvent.RoomSystemMessage) {
        val key = "$hubHash/${m.room}"
        if (lastSystemLine[key] == m.text) {
            logger("RRC dedup: repeated system line in ${m.room}")
            return
        }
        lastSystemLine[key] = m.text
        repo.saveMessage(
            StoredRrcMessage(
                hubHash = hubHash,
                room = m.room,
                direction = if (m.isError) ERROR else SYSTEM,
                senderIdHash = "",
                nick = null,
                text = boundStoredText(m.text),
                timestamp = nowMs(),
                msgId = null,
                mention = m.isMention,
            ),
        )
        repo.touchRoom(hubHash, m.room, nowMs())
    }

    private suspend fun persistInbound(hubHash: String, m: RrcEvent.RoomMessage) {
        val msgIdHex = m.msgId.toHex()
        if (msgIdHex.isNotEmpty() && repo.hasMessageId(hubHash, msgIdHex)) {
            // The hub echoed our own message back, or a transit relay
            // replayed the fan-out. Either way we already have it.
            logger("RRC dedup: dropped repeat msg $msgIdHex in ${m.room}")
            return
        }
        repo.saveMessage(
            StoredRrcMessage(
                hubHash = hubHash,
                room = m.room,
                direction = INCOMING,
                senderIdHash = m.senderIdHash.toHex(),
                // SECURITY (audit 2026-08-31 F7): the LXMF store path
                // has bounded stored text since audit 2026-07-28; the
                // RRC one never did. Everything here is hub-supplied —
                // a `notice`/`motd` Resource can carry up to
                // RRC_MAX_RESOURCE_BYTES of it — and a row is forever.
                nick = m.nick?.let { boundStoredText(it) },
                text = boundStoredText(m.text),
                timestamp = m.timestampMs,
                msgId = msgIdHex.ifEmpty { null },
                mention = m.isMention,
                replyToMsgId = m.replyToMsgId,
            ),
        )
        // No-op when the room row doesn't exist yet — the engine
        // creates it on join, so this only ever bumps a real row.
        repo.touchRoom(hubHash, m.room, m.timestampMs)
    }

    /**
     * Persist a message we just sent. [RrcSession] emits no event for
     * our own sends, so the engine calls this from its send path.
     * Returns the new row id.
     *
     * [msgId] is the envelope `K_ID` of the message that was sent. The
     * hub fans every message out to all room members — *including the
     * sender* — and that echo carries the same `K_ID`. Storing it here
     * lets [persistInbound] dedup the echo against this row instead of
     * saving the message a second time.
     */
    suspend fun recordOutgoing(
        hubHash: String,
        room: String,
        senderIdHash: ByteArray,
        nick: String?,
        text: String,
        timestamp: Long,
        msgId: ByteArray,
        replyToMsgId: String? = null,
    ): Long {
        val id = repo.saveMessage(
            StoredRrcMessage(
                hubHash = hubHash,
                room = room,
                direction = OUTGOING,
                senderIdHash = senderIdHash.toHex(),
                nick = nick,
                text = text,
                timestamp = timestamp,
                msgId = msgId.toHex().ifEmpty { null },
                replyToMsgId = replyToMsgId,
            ),
        )
        repo.touchRoom(hubHash, room, timestamp)
        return id
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
