package io.github.thatsfguy.reticulum.rrc

/**
 * Reticulum Relay Chat (RRC) protocol constants.
 *
 * Transcribed verbatim from the reference hub `rrcd/constants.py`
 * (github.com/kc1awv/rrcd). RRC is an IRC-style chat protocol layered
 * on Reticulum: a client opens one RNS Link to a hub, identifies on the
 * link, and exchanges CBOR-encoded envelopes (see [io.github.thatsfguy
 * .reticulum.codec.Cbor]). Authoritative spec: https://rrc.kc1awv.net/.
 *
 * Numeric keys are part of the wire format — do not renumber.
 */
object Rrc {

    /** RRC protocol version carried in envelope key [K_V]. */
    const val VERSION = 1

    // ---- Envelope keys (CBOR map, unsigned-integer keys) ---------------

    const val K_V = 0      // protocol version (int)
    const val K_T = 1      // message type (int)
    const val K_ID = 2     // message id (8 random bytes)
    const val K_TS = 3     // timestamp, ms since epoch (uint)
    const val K_SRC = 4    // sender identity hash (16 bytes) — opaque, never re-encode
    const val K_ROOM = 5   // room name (string, optional)
    const val K_BODY = 6   // body (type-specific, optional)
    const val K_NICK = 7   // nickname (string, optional)

    // ---- Message types -------------------------------------------------

    const val T_HELLO = 1
    const val T_WELCOME = 2
    const val T_JOIN = 10
    const val T_JOINED = 11
    const val T_PART = 12
    const val T_PARTED = 13
    const val T_MSG = 20
    const val T_NOTICE = 21
    const val T_ACTION = 22   // `/me`-style message; routed identically to MSG
    const val T_PING = 30
    const val T_PONG = 31
    const val T_ERROR = 40
    const val T_RESOURCE_ENVELOPE = 50

    // ---- Extension keys (>= 64) ---------------------------------------
    //
    // `reticulum-relay-chat/docs/rrc-extensions.md` v1. Keys 0..7 are RRC
    // core, 8..63 are RESERVED for a future core (a hub MUST drop those,
    // so a client must never use them), and 64+ are extensions a hub
    // relays verbatim without interpreting. All three below ride an
    // ordinary MSG (type 20) — no new message type, because an unknown
    // type is dropped silently by existing hubs and would be invisible
    // rather than merely un-threaded.

    /** K_REPLY_TO — the [K_ID] of the message this one replies to. */
    const val K_REPLY_TO = 64

    /** K_REACT_TO — the [K_ID] this reacts to; [K_BODY] is the emoji. */
    const val K_REACT_TO = 65

    /** K_REACT_OP — 0 applies (default, may be omitted), 1 retracts. */
    const val K_REACT_OP = 66

    /** [K_REACT_OP] value: add this reaction. */
    const val REACT_OP_APPLY = 0

    /** [K_REACT_OP] value: remove it. Apply and retract are idempotent
     *  by design — Reticulum is lossy and a duplicate delivery of a
     *  *toggle* would flip twice and land in the wrong state. */
    const val REACT_OP_RETRACT = 1

    /**
     * Cap on the total encoded size of the extension keys on one
     * envelope (§5, "128 bytes is recommended").
     *
     * A hub is a fan-out amplifier — one inbound frame becomes one per
     * room member — so bytes it relays without reading are multiplied
     * by the room size. The hub MUST reject rather than truncate past
     * this; we hold ourselves to the same bound on both send and
     * receive so we can never be the client that gets a frame refused.
     */
    const val MAX_EXT_BYTES = 128

    // ---- HELLO body keys ----------------------------------------------

    const val B_HELLO_NAME = 0
    const val B_HELLO_VER = 1
    const val B_HELLO_CAPS = 2

    // ---- WELCOME body keys --------------------------------------------

    const val B_WELCOME_HUB = 0
    const val B_WELCOME_VER = 1
    const val B_WELCOME_CAPS = 2
    const val B_WELCOME_LIMITS = 3

    // ---- Hub limits map keys (inside WELCOME body key B_WELCOME_LIMITS) -

    const val B_LIMIT_MAX_NICK_BYTES = 0
    const val B_LIMIT_MAX_ROOM_NAME_BYTES = 1
    const val B_LIMIT_MAX_MSG_BODY_BYTES = 2
    const val B_LIMIT_MAX_ROOMS_PER_SESSION = 3
    const val B_LIMIT_RATE_LIMIT_MSGS_PER_MINUTE = 4

    // ---- Capability map keys (values advisory) ------------------------

    const val CAP_RESOURCE_ENVELOPE = 0

    // ---- RESOURCE_ENVELOPE body keys ----------------------------------

    const val B_RES_ID = 0
    const val B_RES_KIND = 1
    const val B_RES_SIZE = 2
    const val B_RES_SHA256 = 3
    const val B_RES_ENCODING = 4

    // ---- Resource kinds (string values) -------------------------------

    const val RES_KIND_NOTICE = "notice"
    const val RES_KIND_MOTD = "motd"
    const val RES_KIND_BLOB = "blob"

    /** Message id length — `os.urandom(8)` in `rrcd/envelope.py`. */
    const val MSG_ID_LENGTH = 8

    /**
     * Structural ceilings on the free-text fields of an envelope, in
     * UTF-16 units.
     *
     * Not the same thing as the hub's advertised `RrcLimits` — those
     * are what the hub says it will accept from US, and a hub is free
     * to advertise whatever it likes. These are what WE will accept
     * from IT, and they exist because nothing bounded these fields at
     * all before (audit 2026-08-31 F7): `K_ID` and the reply/react
     * anchors were bounded 1..64, `K_ROOM` / `K_NICK` were not, so a
     * room name or nickname arrived at whatever length a frame allowed
     * and went straight into a database row, a room key and a
     * notification title.
     *
     * Both are an order of magnitude above the hub's own defaults (64
     * and 32 bytes), so nothing legitimate is near them: they are a
     * backstop against a hostile hub, not a protocol limit.
     */
    const val MAX_ROOM_NAME_CHARS = 512
    const val MAX_NICK_CHARS = 512
}
