package io.github.thatsfguy.reticulum.rrc

import io.github.thatsfguy.reticulum.codec.Cbor

/**
 * An RRC protocol envelope — the CBOR map every RRC message is wrapped
 * in. Mirrors `rrcd/envelope.py` (`make_envelope` / `validate_envelope`).
 *
 * Wire form: a CBOR map with unsigned-integer keys [Rrc.K_V] … [Rrc.K_NICK],
 * plus the optional extension keys 64..66 (`rrc-extensions.md` v1).
 * Keys are emitted in ascending order, which for this key set is also
 * CBOR's canonical map-key order, so [encode] is byte-identical to the
 * hub's. (Canonical order sorts by *encoded* bytes: keys 0..23 encode
 * in one byte and 24+ in two, so every extension key sorts after every
 * core key whatever its number. That stops coinciding with ascending
 * numeric order the moment RRC core assigns a key above 23 — see
 * `rrc-extensions.md` §7.)
 *
 * [src] is the sender's RNS **identity hash** (16 bytes) and is opaque —
 * never decode-and-re-encode it; copy the bytes through verbatim. (Same
 * identity-hash-vs-destination-hash trap as LXMF; see CLAUDE.md "Key
 * bugs" §3.)
 *
 * NOTE: like `LxmfMessage`, this is a `data class` holding `ByteArray`
 * fields, so the generated `equals`/`hashCode` use reference identity
 * for those fields. Compare envelopes via [encode] bytes, not `==`.
 */
data class RrcEnvelope(
    val type: Int,
    val msgId: ByteArray,
    val timestampMs: Long,
    val src: ByteArray,
    val room: String? = null,
    val body: Any? = null,
    val nick: String? = null,
    val version: Int = Rrc.VERSION,
    /** K_REPLY_TO (64) — the [msgId] this message replies to. */
    val replyTo: ByteArray? = null,
    /** K_REACT_TO (65) — the [msgId] this message reacts to; [body] is
     *  then the reaction emoji rather than chat text. */
    val reactTo: ByteArray? = null,
    /** K_REACT_OP (66) — [Rrc.REACT_OP_RETRACT] to remove the reaction.
     *  Null / [Rrc.REACT_OP_APPLY] adds it. */
    val reactOp: Int? = null,
) {
    /**
     * Encode to canonical CBOR wire bytes. Optional fields (room / body
     * / nick) are omitted entirely when null — matching `make_envelope`,
     * which only inserts those keys when their value is non-null.
     */
    fun encode(): ByteArray {
        val map = LinkedHashMap<Any?, Any?>()
        map[Rrc.K_V] = version
        map[Rrc.K_T] = type
        map[Rrc.K_ID] = msgId
        map[Rrc.K_TS] = timestampMs
        map[Rrc.K_SRC] = src
        if (room != null) map[Rrc.K_ROOM] = room
        if (body != null) map[Rrc.K_BODY] = body
        if (nick != null) map[Rrc.K_NICK] = nick
        // Extensions last, in ascending key order — canonical, and the
        // order the published test vectors are written in.
        if (replyTo != null) map[Rrc.K_REPLY_TO] = replyTo
        if (reactTo != null) map[Rrc.K_REACT_TO] = reactTo
        if (reactOp != null) map[Rrc.K_REACT_OP] = reactOp
        return Cbor.encode(map)
    }

    companion object {
        /**
         * Decode + validate RRC wire bytes into an [RrcEnvelope].
         * Throws [IllegalArgumentException] on any structural violation
         * (mirrors `validate_envelope`): non-integer / negative keys, a
         * missing required key, an unsupported version, or a
         * wrong-typed required value.
         */
        fun decode(bytes: ByteArray): RrcEnvelope {
            val decoded = Cbor.decode(bytes)
            require(decoded is Map<*, *>) { "RRC envelope must be a CBOR map" }
            return fromMap(decoded)
        }

        /** Validate an already-decoded CBOR map and project it. */
        fun fromMap(m: Map<*, *>): RrcEnvelope {
            // CBOR map keys must all be unsigned integers.
            for (k in m.keys) {
                val ki = (k as? Number)?.toLong()
                require(ki != null && ki >= 0) { "envelope keys must be unsigned integers" }
            }

            val version = requireInt(m, Rrc.K_V, "protocol version")
            require(version == Rrc.VERSION) { "unsupported RRC version $version" }

            val type = requireInt(m, Rrc.K_T, "message type")
            val msgId = requireBytes(m, Rrc.K_ID, "message id")
            // SECURITY (audit F8): an empty id defeats the RrcPersistence
            // echo-dedup; an absurdly large one is wasteful. rrcd uses 8.
            require(msgId.size in 1..64) { "message id length ${msgId.size} out of range" }
            val ts = requireLong(m, Rrc.K_TS, "timestamp")
            require(ts >= 0) { "timestamp must be unsigned" }
            val src = requireBytes(m, Rrc.K_SRC, "sender identity")

            // Extensions (rrc-extensions.md). Malformed ones are DROPPED
            // rather than rejected: they are optional decoration on an
            // otherwise valid message, and refusing the whole envelope
            // would let a peer suppress chat by attaching a bad anchor.
            val replyTo = optAnchor(m, Rrc.K_REPLY_TO)
            var reactTo = optAnchor(m, Rrc.K_REACT_TO)
            // "K_REPLY_TO and K_REACT_TO are mutually exclusive. A
            // message carrying both is malformed; a receiver SHOULD
            // treat it as a reply and ignore the reaction." (§2)
            if (replyTo != null) reactTo = null
            val reactOp = (valueOf(m, Rrc.K_REACT_OP) as? Number)
                ?.toInt()
                ?.takeIf { it == Rrc.REACT_OP_APPLY || it == Rrc.REACT_OP_RETRACT }

            // SECURITY (audit 2026-08-31 F7): the free-text fields had
            // no ceiling of any kind. A room name keys UI state and a
            // database row; a nick is rendered on every line and stamped
            // into a notification. Rejecting an absurd room name is
            // right — nothing that long is a room, and the envelope is
            // not usable without one — while an absurd nick is dropped
            // rather than thrown, the same way a malformed extension is:
            // the message itself is still worth showing, just unsigned
            // by a name.
            val room = optString(m, Rrc.K_ROOM, "room name")
            require(room == null || room.length <= Rrc.MAX_ROOM_NAME_CHARS) {
                "room name length ${room?.length} exceeds ${Rrc.MAX_ROOM_NAME_CHARS}"
            }
            val nick = optString(m, Rrc.K_NICK, "nickname")
                ?.takeIf { it.length <= Rrc.MAX_NICK_CHARS }

            return RrcEnvelope(
                type = type,
                msgId = msgId,
                timestampMs = ts,
                src = src,
                room = room,
                body = valueOf(m, Rrc.K_BODY),
                nick = nick,
                version = version,
                replyTo = replyTo,
                reactTo = reactTo,
                reactOp = reactOp,
            )
        }

        /**
         * Look a value up by numeric key. Tolerates both `Int` keys
         * (maps we built) and `Long` keys (maps from [Cbor.decode]).
         */
        private fun valueOf(m: Map<*, *>, key: Int): Any? {
            for ((k, v) in m) {
                if ((k as? Number)?.toLong() == key.toLong()) return v
            }
            return null
        }

        private fun requireInt(m: Map<*, *>, key: Int, what: String): Int {
            val v = valueOf(m, key)
            require(v is Number && v !is Double && v !is Float) { "missing or non-integer $what" }
            return v.toInt()
        }

        private fun requireLong(m: Map<*, *>, key: Int, what: String): Long {
            val v = valueOf(m, key)
            require(v is Number && v !is Double && v !is Float) { "missing or non-integer $what" }
            return v.toLong()
        }

        private fun requireBytes(m: Map<*, *>, key: Int, what: String): ByteArray {
            val v = valueOf(m, key)
            require(v is ByteArray) { "missing or non-bytes $what" }
            return v
        }

        /**
         * An extension key holding a message anchor — the `K_ID` of
         * another message. Bounded the same way [Rrc.K_ID] is, and
         * dropped (null) rather than throwing when absent or malformed.
         */
        private fun optAnchor(m: Map<*, *>, key: Int): ByteArray? {
            val v = valueOf(m, key) as? ByteArray ?: return null
            return v.takeIf { it.size in 1..64 }
        }

        private fun optString(m: Map<*, *>, key: Int, what: String): String? {
            val v = valueOf(m, key) ?: return null
            require(v is String) { "$what must be a string" }
            return v
        }
    }
}
