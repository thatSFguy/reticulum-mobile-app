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
}
