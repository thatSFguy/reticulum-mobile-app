package io.github.thatsfguy.reticulum.rrc

import kotlin.random.Random

/**
 * Typed RRC message builders (client → hub) and a parser (hub → client)
 * over [RrcEnvelope]. Mirrors the message handling in `rrcd/router.py`
 * and `rrcd/messages.py`.
 *
 * Builders take an explicit `timestampMs` — the time source belongs to
 * the session layer, not here — and default `msgId` to 8 fresh random
 * bytes (`os.urandom(8)` in `rrcd/envelope.py`).
 */
object RrcMessages {

    // ---- builders: client → hub ---------------------------------------

    /**
     * HELLO — the first message after the link is up. Body is a CBOR
     * map `{B_HELLO_CAPS: caps}` plus optional client name / version.
     * The hub replies WELCOME.
     */
    fun hello(
        src: ByteArray,
        timestampMs: Long,
        nick: String? = null,
        clientName: String? = null,
        clientVersion: String? = null,
        resourceCapable: Boolean = true,
        msgId: ByteArray = freshId(),
    ): RrcEnvelope {
        val caps = LinkedHashMap<Any?, Any?>()
        // Capability values are advisory; presence of the key is what counts.
        if (resourceCapable) caps[Rrc.CAP_RESOURCE_ENVELOPE] = 1
        val body = LinkedHashMap<Any?, Any?>()
        if (clientName != null) body[Rrc.B_HELLO_NAME] = clientName
        if (clientVersion != null) body[Rrc.B_HELLO_VER] = clientVersion
        body[Rrc.B_HELLO_CAPS] = caps
        return RrcEnvelope(Rrc.T_HELLO, msgId, timestampMs, src, body = body, nick = nick)
    }

    /** JOIN a room. [key] is supplied only for keyed (`+k`) rooms. */
    fun join(
        src: ByteArray,
        timestampMs: Long,
        room: String,
        key: String? = null,
        nick: String? = null,
        msgId: ByteArray = freshId(),
    ): RrcEnvelope =
        RrcEnvelope(Rrc.T_JOIN, msgId, timestampMs, src, room = room, body = key, nick = nick)

    /** PART a room. */
    fun part(
        src: ByteArray,
        timestampMs: Long,
        room: String,
        nick: String? = null,
        msgId: ByteArray = freshId(),
    ): RrcEnvelope =
        RrcEnvelope(Rrc.T_PART, msgId, timestampMs, src, room = room, nick = nick)

    /**
     * MSG — a chat message to a room. The hub rewrites K_SRC to the
     * sender's verified identity hash and stamps K_NICK before fan-out,
     * so the [src]/[nick] we send are advisory.
     */
    fun message(
        src: ByteArray,
        timestampMs: Long,
        room: String,
        text: String,
        nick: String? = null,
        msgId: ByteArray = freshId(),
    ): RrcEnvelope =
        RrcEnvelope(Rrc.T_MSG, msgId, timestampMs, src, room = room, body = text, nick = nick)

    /**
     * ACTION — a `/me`-style message to a room (RRC type 22). The hub
     * routes and fans it out identically to [message], but does NOT scan
     * it for a leading `/` as a hub command — which is exactly why `/me`
     * text must go out as ACTION rather than MSG (a MSG body starting
     * with `/` is consumed as a command). The body is the full typed
     * text; receivers render type-22 like a MSG.
     */
    fun action(
        src: ByteArray,
        timestampMs: Long,
        room: String,
        text: String,
        nick: String? = null,
        msgId: ByteArray = freshId(),
    ): RrcEnvelope =
        RrcEnvelope(Rrc.T_ACTION, msgId, timestampMs, src, room = room, body = text, nick = nick)

    /**
     * A hub-local slash command (e.g. `/list`). Sent as a MSG with no
     * room — the hub command-dispatches a `/`-prefixed body before any
     * room routing — and replies with a NOTICE.
     */
    fun command(
        src: ByteArray,
        timestampMs: Long,
        text: String,
        nick: String? = null,
        msgId: ByteArray = freshId(),
    ): RrcEnvelope =
        RrcEnvelope(Rrc.T_MSG, msgId, timestampMs, src, body = text, nick = nick)

    /** PING — keepalive; the hub echoes [payload] back in a PONG. */
    fun ping(
        src: ByteArray,
        timestampMs: Long,
        payload: ByteArray? = null,
        msgId: ByteArray = freshId(),
    ): RrcEnvelope =
        RrcEnvelope(Rrc.T_PING, msgId, timestampMs, src, body = payload)

    /** PONG — reply to a hub-initiated PING, echoing its [payload]. */
    fun pong(
        src: ByteArray,
        timestampMs: Long,
        payload: ByteArray? = null,
        msgId: ByteArray = freshId(),
    ): RrcEnvelope =
        RrcEnvelope(Rrc.T_PONG, msgId, timestampMs, src, body = payload)

    private fun freshId(): ByteArray = Random.nextBytes(Rrc.MSG_ID_LENGTH)

    // ---- parser: hub → client -----------------------------------------

    /**
     * Decode + validate wire bytes and project them to a typed
     * [RrcInbound]. Envelope-structure violations throw (via
     * [RrcEnvelope.decode]); a type/body mismatch from the hub degrades
     * to a best-effort value or [RrcInbound.Unknown] rather than
     * throwing — a misbehaving hub must not crash the client.
     */
    fun parse(bytes: ByteArray): RrcInbound = project(RrcEnvelope.decode(bytes))

    /** Project an already-decoded envelope. */
    fun project(env: RrcEnvelope): RrcInbound = when (env.type) {
        Rrc.T_WELCOME -> {
            val body = env.body as? Map<*, *>
            RrcInbound.Welcome(
                envelope = env,
                hubName = body?.let { mapStr(it, Rrc.B_WELCOME_HUB) },
                hubVersion = body?.let { mapStr(it, Rrc.B_WELCOME_VER) },
                limits = parseLimits(body?.let { mapGet(it, Rrc.B_WELCOME_LIMITS) } as? Map<*, *>),
            )
        }
        Rrc.T_JOINED ->
            RrcInbound.Joined(env, env.room ?: "", memberList(env.body))
        Rrc.T_PARTED ->
            RrcInbound.Parted(env, env.room ?: "", memberList(env.body))
        // §10 parity — type-22 ACTION is rendered like a MSG (the `/me`
        // text is carried verbatim in the body), so project it the same.
        Rrc.T_MSG, Rrc.T_ACTION ->
            RrcInbound.Message(env, env.room ?: "", env.src, env.nick, env.body as? String ?: "")
        Rrc.T_NOTICE ->
            RrcInbound.Notice(env, env.room, env.body as? String ?: "")
        Rrc.T_ERROR ->
            RrcInbound.Error(env, env.room, env.body as? String ?: "")
        Rrc.T_PING -> RrcInbound.Ping(env)
        Rrc.T_PONG -> RrcInbound.Pong(env)
        Rrc.T_RESOURCE_ENVELOPE ->
            RrcInbound.ResourceEnvelope(env, parseResource(env.body as? Map<*, *>))
        else -> RrcInbound.Unknown(env)
    }

    private fun memberList(body: Any?): List<ByteArray> =
        (body as? List<*>)?.mapNotNull { it as? ByteArray } ?: emptyList()

    private fun parseLimits(m: Map<*, *>?): RrcLimits {
        if (m == null) return RrcLimits()
        return RrcLimits(
            maxNickBytes = mapInt(m, Rrc.B_LIMIT_MAX_NICK_BYTES) ?: RrcLimits().maxNickBytes,
            maxRoomNameBytes = mapInt(m, Rrc.B_LIMIT_MAX_ROOM_NAME_BYTES) ?: RrcLimits().maxRoomNameBytes,
            maxMsgBodyBytes = mapInt(m, Rrc.B_LIMIT_MAX_MSG_BODY_BYTES) ?: RrcLimits().maxMsgBodyBytes,
            maxRoomsPerSession = mapInt(m, Rrc.B_LIMIT_MAX_ROOMS_PER_SESSION) ?: RrcLimits().maxRoomsPerSession,
            rateLimitMsgsPerMinute = mapInt(m, Rrc.B_LIMIT_RATE_LIMIT_MSGS_PER_MINUTE)
                ?: RrcLimits().rateLimitMsgsPerMinute,
        )
    }

    private fun parseResource(m: Map<*, *>?): RrcResourceMeta {
        if (m == null) return RrcResourceMeta(ByteArray(0), "", 0, null, null)
        return RrcResourceMeta(
            id = mapGet(m, Rrc.B_RES_ID) as? ByteArray ?: ByteArray(0),
            kind = mapStr(m, Rrc.B_RES_KIND) ?: "",
            size = (mapGet(m, Rrc.B_RES_SIZE) as? Number)?.toLong() ?: 0L,
            sha256 = mapGet(m, Rrc.B_RES_SHA256) as? ByteArray,
            encoding = mapStr(m, Rrc.B_RES_ENCODING),
        )
    }

    // CBOR maps from the decoder carry Long keys; maps we build carry
    // Int keys. Look up tolerantly by numeric value.
    private fun mapGet(m: Map<*, *>, key: Int): Any? {
        for ((k, v) in m) if ((k as? Number)?.toLong() == key.toLong()) return v
        return null
    }

    private fun mapStr(m: Map<*, *>, key: Int): String? = mapGet(m, key) as? String

    private fun mapInt(m: Map<*, *>, key: Int): Int? = (mapGet(m, key) as? Number)?.toInt()
}

/**
 * Hub-advertised limits from WELCOME body key [Rrc.B_WELCOME_LIMITS].
 * Defaults are conservative fallbacks for when a hub omits a key.
 */
data class RrcLimits(
    val maxNickBytes: Int = 32,
    val maxRoomNameBytes: Int = 64,
    val maxMsgBodyBytes: Int = 4096,
    val maxRoomsPerSession: Int = 16,
    val rateLimitMsgsPerMinute: Int = 30,
)

/** Metadata from a RESOURCE_ENVELOPE — the actual payload follows as an RNS Resource. */
data class RrcResourceMeta(
    val id: ByteArray,
    val kind: String,
    val size: Long,
    val sha256: ByteArray?,
    val encoding: String?,
)

/** A parsed inbound RRC message. */
sealed interface RrcInbound {
    val envelope: RrcEnvelope

    data class Welcome(
        override val envelope: RrcEnvelope,
        val hubName: String?,
        val hubVersion: String?,
        val limits: RrcLimits,
    ) : RrcInbound

    data class Joined(
        override val envelope: RrcEnvelope,
        val room: String,
        val members: List<ByteArray>,
    ) : RrcInbound

    data class Parted(
        override val envelope: RrcEnvelope,
        val room: String,
        val members: List<ByteArray>,
    ) : RrcInbound

    data class Message(
        override val envelope: RrcEnvelope,
        val room: String,
        val src: ByteArray,
        val nick: String?,
        val text: String,
    ) : RrcInbound

    data class Notice(
        override val envelope: RrcEnvelope,
        val room: String?,
        val text: String,
    ) : RrcInbound

    data class Error(
        override val envelope: RrcEnvelope,
        val room: String?,
        val text: String,
    ) : RrcInbound

    /** Hub-initiated keepalive — the client must answer with PONG. */
    data class Ping(override val envelope: RrcEnvelope) : RrcInbound

    data class Pong(override val envelope: RrcEnvelope) : RrcInbound

    data class ResourceEnvelope(
        override val envelope: RrcEnvelope,
        val resource: RrcResourceMeta,
    ) : RrcInbound

    /** A message type this client does not handle — kept for logging. */
    data class Unknown(override val envelope: RrcEnvelope) : RrcInbound
}
