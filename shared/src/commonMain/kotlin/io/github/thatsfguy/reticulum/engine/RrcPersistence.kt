package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.store.RrcRepository
import io.github.thatsfguy.reticulum.store.StoredRrcMessage

/**
 * Bridges an [RrcSession]'s [RrcEvent] stream into the [RrcRepository]
 * so RRC room history and hub state survive an app restart.
 *
 * Scope is deliberately narrow — only the events whose persistence is
 * *unambiguous* are handled here:
 *
 *  - [RrcEvent.Welcomed] stamps the hub's last-connected time;
 *  - [RrcEvent.RoomMessage] (always an inbound message fanned out by
 *    the hub) is saved as an `incoming` row, deduped by envelope id.
 *
 * Room *membership* is NOT driven from events: an [RrcEvent.Joined] /
 * [RrcEvent.Parted] fires both for our own join/part AND for other
 * members coming and going, so it cannot tell "we joined" from
 * "someone else joined". The engine therefore persists membership
 * from its own explicit `join` / `part` calls, where the intent is
 * unambiguous. Outgoing messages are likewise persisted by the engine
 * at send time via [recordOutgoing] — [RrcSession] emits no event for
 * our own sends.
 */
class RrcPersistence(
    private val repo: RrcRepository,
    private val nowMs: () -> Long,
    private val logger: (String) -> Unit = {},
) {

    /** Direction tags — same vocabulary as the LXMF `messages` table. */
    private companion object {
        const val INCOMING = "incoming"
        const val OUTGOING = "outgoing"
    }

    /** Persist whatever [event] on [hubHash] warrants persistence. */
    suspend fun onEvent(hubHash: String, event: RrcEvent) {
        when (event) {
            is RrcEvent.Welcomed -> repo.setHubLastConnected(hubHash, nowMs())
            is RrcEvent.RoomMessage -> persistInbound(hubHash, event)
            // Notice / HubError / Joined / Parted / StateChanged are
            // transient or membership-driven — see the class kdoc.
            is RrcEvent.Notice,
            is RrcEvent.HubError,
            is RrcEvent.Joined,
            is RrcEvent.Parted,
            is RrcEvent.StateChanged -> Unit
        }
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
                nick = m.nick,
                text = m.text,
                timestamp = m.timestampMs,
                msgId = msgIdHex.ifEmpty { null },
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
     */
    suspend fun recordOutgoing(
        hubHash: String,
        room: String,
        senderIdHash: ByteArray,
        nick: String?,
        text: String,
        timestamp: Long,
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
                // Outgoing rows carry no msgId — we control our own
                // sends, so there is nothing to dedup them against.
                msgId = null,
            ),
        )
        repo.touchRoom(hubHash, room, timestamp)
        return id
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
