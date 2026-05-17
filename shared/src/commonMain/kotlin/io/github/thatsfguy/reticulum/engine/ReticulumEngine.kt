package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.announce.KnownDestinations
import io.github.thatsfguy.reticulum.announce.extractCoordinates
import io.github.thatsfguy.reticulum.announce.extractDisplayName
import io.github.thatsfguy.reticulum.announce.parseAnnounce
import io.github.thatsfguy.reticulum.announce.parseTelemetryBytes
import io.github.thatsfguy.reticulum.announce.validateAnnounce
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.computePacketFullHash
import io.github.thatsfguy.reticulum.lxmf.SignatureVariant
import io.github.thatsfguy.reticulum.lxmf.packMessage
import io.github.thatsfguy.reticulum.lxmf.unpackMessage
import io.github.thatsfguy.reticulum.lxmf.verifyMessageSignature
import io.github.thatsfguy.reticulum.protocol.CTX_NONE
import io.github.thatsfguy.reticulum.protocol.DEST_SINGLE
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.LINK_MAX_ATTEMPTS
import io.github.thatsfguy.reticulum.protocol.LINK_RETRY_INTERVAL_MS
import io.github.thatsfguy.reticulum.protocol.MSG_BACKOFF_MS
import io.github.thatsfguy.reticulum.protocol.MSG_MAX_ATTEMPTS
import io.github.thatsfguy.reticulum.protocol.PATH_STALE_MS
import io.github.thatsfguy.reticulum.protocol.PACKET_ANNOUNCE
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.HEADER_2
import io.github.thatsfguy.reticulum.protocol.TRANSPORT_BROADCAST
import io.github.thatsfguy.reticulum.protocol.TRANSPORT_TRANSPORT
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
import io.github.thatsfguy.reticulum.store.DestinationRepository
import io.github.thatsfguy.reticulum.store.IdentityRepository
import io.github.thatsfguy.reticulum.store.MessageRepository
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredIdentity
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Defensive ceiling on inbound LXMF `FIELD_IMAGE` (integer msgpack key
 * 6) payloads — 32 KB. The cooperating sender ladder caps at 20 KB
 * (see Phase 2 `ImageCompress`); anything larger came from a peer
 * that bypassed the picker or is actively hostile. We log + drop the
 * image (the rest of the message still saves) rather than persist a
 * blob that could starve a phone's heap on decode.
 */
internal const val INBOUND_IMAGE_MAX_BYTES = 32 * 1024

/**
 * Sentinel prefix written to [io.github.thatsfguy.reticulum.store.StoredMessage.lastError]
 * when an image-bearing send had to drop to the opportunistic
 * fallback (which can't carry images — single-packet MTU). The
 * subsequent PROOF flips the row's `state` to "delivered" but
 * leaves `lastError` untouched (partial-update semantics), so the
 * bubble renderer keys on this prefix to draw the ⚠ partial-
 * delivery indicator alongside the ✓.
 *
 * Stable prefix so the UI can match without binding to the exact
 * wording, and so future log telemetry can grep for it.
 */
internal const val IMAGE_DROPPED_MARKER: String = "image dropped — "

/**
 * Link-establishment attempt budget for image-bearing sends.
 * Opportunistic fallback can't carry images (single-packet MTU),
 * so cycling through the full [LINK_MAX_ATTEMPTS]=5 budget at
 * ~45 s/attempt just makes the user wait ~4 min before discovering
 * the image won't go. 2 attempts ≈ 100 s (45 s + 10 s gap + 45 s)
 * gives a single retry to absorb a transient LoRa collision while
 * still failing fast on a genuinely unreachable destination.
 */
internal const val IMAGE_LINK_MAX_ATTEMPTS: Int = 2

/**
 * Pull the optional LXMF `FIELD_IMAGE` payload out of a decoded
 * message's `fields` map. Returns `(bytes, size)` where `bytes` is the
 * extracted payload (or null when missing / wrong type / oversize) and
 * `size` is the raw bytecount of whatever was found (0 when the field
 * isn't present at all, > [INBOUND_IMAGE_MAX_BYTES] when the value was
 * a ByteArray but exceeded the cap). Callers use the size to emit a
 * dropped-oversize diagnostic and to disambiguate "no image" from
 * "image too large".
 *
 * Key matching is `(Number).toInt() == 6` because msgpack decoders
 * surface the integer key as `Int`, `Long`, or `Short` depending on
 * encoded width — equality against a literal `6: Int` would miss
 * messages packed by `Long`-emitting encoders (Sideband's
 * `lxmf-python` is one of them on some `msgpack` releases).
 *
 * `internal` rather than `private` so the test source set can pin the
 * key-matching + cap behavior without standing up a full engine
 * harness.
 */
internal fun extractImageField(
    fields: Map<Any?, Any?>,
): Pair<ByteArray?, Int> {
    val entry = fields.entries.firstOrNull { (k, _) ->
        (k as? Number)?.toInt() == 6
    } ?: return null to 0

    // Wire format is `[extension_string, bytes]` (Sideband + Columba
    // canonical — see send-side comment in tryDeliverOverLink). Accept
    // bare ByteArray too for back-compat with our own v1.1.15-16
    // devices that shipped the wrong format before this fix. Receiver
    // doesn't actually use the extension — JPEG / PNG / WebP all
    // decode automatically from the file-magic header in the bytes —
    // but we tolerate any non-empty string at index [0] for forward-
    // compat with future codec ladder changes.
    val bytes: ByteArray = when (val v = entry.value) {
        is List<*> -> v.getOrNull(1) as? ByteArray
        is ByteArray -> v
        else -> null
    } ?: return null to 0
    return if (bytes.size <= INBOUND_IMAGE_MAX_BYTES) {
        bytes to bytes.size
    } else {
        null to bytes.size
    }
}

/**
 * What the inbound LXMF aux fields say about this message.
 * Carries reaction-meta (Sideband/Columba field 16) OR reply-to
 * (Columba field 16 OR MeshChatX fields 0x30/0x31).
 *
 * Neither convention is in upstream LXMF — see SPEC.md §5.9 for
 * the canonical FIELD_* allocations 0x01..0x0F + 0xFB..0xFF.
 *
 * **Reaction shape** (Columba / MeshChatX) — empty-body LXMF:
 * ```
 * fields[16] = {
 *   "reaction_to": "<msg_id_hex>",
 *   "emoji":       "👍",
 *   "sender":      "<identity_hash_hex>",
 * }
 * ```
 * `sender` is the reactor's 16-byte RNS identity hash (hex) —
 * `SHA256(public_key)[:16]`, NOT the lxmf.delivery destination hash
 * (same dest-vs-identity gotcha as LXMF `source_hash`, CLAUDE.md
 * "Key bugs" §3). Columba and MeshChatX both emit the identity hash
 * here (verified from source). Receivers aggregate reactions keyed
 * by `(emoji, sender)`, so emitting the destination hash would
 * mis-bucket against those peers and double-count a reactor.
 *
 * **Reply shape 1** (Columba) — normal LXMF with reply pointer:
 * ```
 * fields[16] = {"reply_to": "<msg_id_hex>"}
 * ```
 *
 * **Reply shape 2** (MeshChatX) — normal LXMF, more compact wire:
 * ```
 * fields[0x30] = <raw 32-byte hash>         // ~30B vs Columba's ~80B
 * fields[0x31] = <utf-8 quoted text bytes>  // optional
 * ```
 *
 * Verified against:
 *   - `torlando-tech/columba` `MessageMapper.kt` lines 90-160 +
 *     `NativeMessageSender.kt:130` (reply_to send) +
 *     `NativeReticulumProtocol.kt` ~2230 (reaction send)
 *   - `Quad4-Software/MeshChatX` `lxmf_utils.py` lines 343-349
 *     (0x30 = bytes.hex(), 0x31 = bytes.decode('utf-8'))
 *
 * Dual-tolerance precedent: SPEC.md §5.6 — accept both msgpack
 * `str` and `bin` for signature fields. Same idiom here. Audit
 * reference: 2026-05-13 reactions + replies feature; 2026-05-14
 * MeshChatX dual-format added.
 *
 * `internal` so the test source set can verify both branches
 * without a full engine harness.
 */
internal sealed class Field16Payload {
    data class Reaction(
        val reactionTo: String,
        val emoji: String,
        val sender: String,
    ) : Field16Payload()
    data class Reply(
        val replyTo: String,
        /** Optional MeshChatX quoted content from `fields[0x31]`.
         *  Columba doesn't embed quoted content (receiver resolves
         *  the target locally by hash), so this is null on the
         *  field-16 path. When non-null, the receiver can fall
         *  back to displaying this if the target row isn't found
         *  locally — useful when a reply arrives but the
         *  referenced message was missed (out-of-order delivery,
         *  pre-1.1.33 row without messageId, etc.). */
        val quotedContent: String? = null,
    ) : Field16Payload()
}

internal fun extractField16(fields: Map<Any?, Any?>): Field16Payload? {
    // String values may arrive as msgpack `str` (Kotlin String) or
    // msgpack `bin` (Kotlin ByteArray, which we decode as UTF-8).
    // Different encoders pick differently. Accept both shapes per
    // SPEC.md §5.6's dual-variant precedent.
    fun stringValue(any: Any?): String? = when (any) {
        is String -> any
        is ByteArray -> runCatching { any.decodeToString() }.getOrNull()
        else -> null
    }

    // ---- Shape 1: Columba/Sideband on field 16 -----------------
    val entry16 = fields.entries.firstOrNull { (k, _) ->
        (k as? Number)?.toInt() == 16
    }
    @Suppress("UNCHECKED_CAST")
    val map16 = entry16?.value as? Map<Any?, Any?>
    if (map16 != null) {
        val reactionTo = stringValue(map16["reaction_to"])
        if (reactionTo != null) {
            val emoji = stringValue(map16["emoji"]) ?: return null
            val sender = stringValue(map16["sender"]) ?: return null
            return Field16Payload.Reaction(reactionTo, emoji, sender)
        }
        val replyTo16 = stringValue(map16["reply_to"])
        if (replyTo16 != null) return Field16Payload.Reply(replyTo16)
    }

    // ---- Shape 2: MeshChatX on fields[0x30] + optional [0x31] --
    // Per Quad4-Software/MeshChatX lxmf_utils.py:343-349, 0x30
    // holds the target's message hash as raw `bytes` (NOT hex
    // string); 0x31 optionally holds the quoted content as UTF-8
    // bytes. We tolerate the same-shape value arriving as a
    // String (rare; some bridges hex-encode pre-msgpack) by
    // falling back to that path.
    val entry30 = fields.entries.firstOrNull { (k, _) ->
        (k as? Number)?.toInt() == 0x30
    }
    val replyToHex: String? = when (val v = entry30?.value) {
        is ByteArray -> if (v.isNotEmpty()) v.toHex() else null
        is String -> v.takeIf { it.isNotEmpty() }
        else -> null
    }
    if (replyToHex != null) {
        val entry31 = fields.entries.firstOrNull { (k, _) ->
            (k as? Number)?.toInt() == 0x31
        }
        val quoted = stringValue(entry31?.value)
        return Field16Payload.Reply(replyToHex, quoted)
    }
    return null
}

/**
 * Glue between the protocol stack, the active [Transport], and the
 * persistent repositories. One instance per app lifetime; held by the
 * Android foreground service.
 */
class ReticulumEngine(
    private val crypto: CryptoProvider,
    private val identityRepo: IdentityRepository,
    private val destinationRepo: DestinationRepository,
    private val messageRepo: MessageRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { 0L },
    private val displayNameProvider: () -> String = { "Reticulum Mobile" },
    /** When true, inbound LXMF whose signature cannot be verified
     *  against a known announce is dropped instead of being saved
     *  as `state="unverified"`. Default is `false` (preserve the
     *  legacy "show as unverified, retroactively flip to verified
     *  when the sender's announce arrives" UX). Wired to a
     *  per-user setting via a provider callback so the user can
     *  toggle without restarting the service. Audit reference:
     *  2026-05-13 MED-6. */
    private val dropUnverifiedProvider: () -> Boolean = { false },
    /** Optional NomadNet page cache. When provided, [fetchNomadPage]
     *  writes successful fetches here so the UI can render the previous
     *  version on next visit while a fresh fetch runs in the background.
     *  Null in tests or when the caller doesn't want caching. */
    private val nomadPageCache: io.github.thatsfguy.reticulum.store.NomadPageCacheRepository? = null,
    /** Optional Reticulum Relay Chat storage. When provided, the
     *  experimental [openRrcSession] path is available and RRC room
     *  history / hub state are persisted via [RrcPersistence]. Null in
     *  tests and on builds where the experimental flag is off — every
     *  RRC engine method then fails fast with "RRC storage not
     *  configured". */
    private val rrcRepo: io.github.thatsfguy.reticulum.store.RrcRepository? = null,
) {
    private val tokenCrypto = TokenCrypto(crypto)

    private var identity: Identity? = null

    private val _connections = MutableStateFlow<List<ConnectionState>>(emptyList())
    /** All currently-attached transports, one entry per [TransportKind]. UI
     *  iterates this for "Connected: BLE (up 5m), TCP (up 12m)" status lines. */
    val connections: StateFlow<List<ConnectionState>> = _connections

    /** "Primary" view of the connection set used by widget code that just
     *  wants one number — picks the first Connected entry, else the first
     *  Connecting, else Disconnected. Kept for back-compat with the
     *  pre-multi-transport UI surfaces; new code should consume [connections]. */
    private val _connection = MutableStateFlow(ConnectionState(TransportState.Disconnected, kind = null, changedAtMs = nowMs()))
    val connection: StateFlow<ConnectionState> = _connection

    private fun emitConnection(state: TransportState, kind: TransportKind) {
        val now = nowMs()
        val current = _connections.value
        val existing = current.firstOrNull { it.kind == kind }
        val updated = if (existing == null) {
            current + ConnectionState(state, kind, now)
        } else if (existing.transport == state) {
            current  // no-op: same state, don't reset the timer
        } else {
            current.map { if (it.kind == kind) ConnectionState(state, kind, now) else it }
        }
        if (updated !== current) _connections.value = updated
        recomputePrimary()
    }

    private fun removeConnection(kind: TransportKind) {
        val pruned = _connections.value.filterNot { it.kind == kind }
        if (pruned.size != _connections.value.size) {
            _connections.value = pruned
            recomputePrimary()
        }
    }

    private fun recomputePrimary() {
        val list = _connections.value
        val pick = list.firstOrNull { it.transport == TransportState.Connected }
            ?: list.firstOrNull { it.transport == TransportState.Connecting }
            ?: list.firstOrNull()
            ?: ConnectionState(TransportState.Disconnected, kind = null, changedAtMs = nowMs())
        if (_connection.value.transport != pick.transport ||
            _connection.value.kind != pick.kind ||
            _connection.value.changedAtMs != pick.changedAtMs) {
            _connection.value = pick
        }
    }

    private val _events = MutableSharedFlow<EngineEvent>(replay = 0, extraBufferCapacity = 64)
    val events: Flow<EngineEvent> = _events.asSharedFlow()

    /** Per-kind attached transport plus its pump/state-mirror jobs. */
    private data class Attached(
        val transport: Transport,
        val pumpJob: Job,
        val stateMirrorJob: Job,
    )
    private val transports: MutableMap<TransportKind, Attached> = mutableMapOf()
    private var reannounceJob: Job? = null

    /** Per-Link transport pinning. After an LRPROOF arrives (or a
     *  LINKREQUEST is accepted as responder), every subsequent packet on
     *  that link follows the same kind it was first heard on. Outbound
     *  packets via [sendForLink] read from this map. */
    private val linkKinds: MutableMap<String, TransportKind> = mutableMapOf()

    /** Active outbound Link sessions, keyed by link_id hex. The pump routes
     *  inbound packets to a session when destHash matches a key here. */
    private val activeSessions: MutableMap<String, LinkPump> = mutableMapOf()
    private val sessionsLock = kotlinx.coroutines.sync.Mutex()

    /**
     * v0.1.66 NomadNet link reuse cache. Keyed by destHash hex, holds
     * the [LinkSession] established for the most recent fetchNomadPage
     * to that destination plus whether we already sent LINKIDENTIFY on
     * it. A subsequent fetch reuses the session if it's still ACTIVE
     * AND the identify state matches — saves the LRPROOF round-trip
     * (~1.5-6s per hop) and is a prerequisite for partials.
     *
     * Mismatched identify state (cached anonymous → request identified
     * or vice versa) drops the cached session and re-establishes;
     * upstream Browser.py:1245-1250 makes the identify decision once
     * per Link, and we keep the same invariant.
     *
     * Locked under [sessionsLock] alongside activeSessions because
     * removals are coordinated when a link closes.
     */
    private data class NomadLink(val session: LinkSession, val identified: Boolean, val linkIdHex: String)
    private val nomadLinks: MutableMap<String, NomadLink> = mutableMapOf()

    /**
     * v0.1.89 LXMF link cache. Same shape as [nomadLinks] but for the
     * Sideband-style "send a message over a Reticulum Link" path
     * primary-delivery flow uses. Reused for follow-up sends to the
     * same destination so each message after the first only pays the
     * encrypt + send + per-packet PROOF round-trip, not a fresh
     * LRPROOF handshake.
     *
     * Locked under [sessionsLock] alongside [activeSessions] and
     * [nomadLinks] because removals are coordinated when a link closes
     * or the engine detaches a transport.
     */
    private data class LxmfLink(val session: LinkSession, val linkIdHex: String)
    private val lxmfLinks: MutableMap<String, LxmfLink> = mutableMapOf()

    /** Hashes we've already issued a path request for in this session,
     *  to keep the "unverified message → request identity" loop from
     *  spamming the mesh if the same unknown sender keeps writing. */
    private val pathRequestsSent = mutableSetOf<String>()

    /** Truncated packet hashes (16 bytes, hex) of opportunistic DATA
     *  packets we've already accepted in this session. Senders like
     *  Sideband retransmit until a proof arrives, so until our proof
     *  propagates back we may see the same packet multiple times.
     *  We always reply with a fresh proof but only store the message
     *  once. Bounded LRU; resets on engine restart. */
    private val seenIncomingDataHashes = LinkedHashSet<String>()
    private val seenIncomingDataCap = 256

    /** Truncated full packet hashes (16 bytes hex) of announces we've
     *  already ingested in this session. The same announce typically
     *  arrives via several redundant relay paths through a single rnsd
     *  attachment — without dedup, every announce shows up 5-6× in the
     *  log and the destination row gets pointlessly upserted each time. */
    private val seenAnnounceHashes = LinkedHashSet<String>()
    private val seenAnnounceCap = 512

    /**
     * MED-2 announce-flood eviction. We let `destinationRepo` grow
     * up to [MAX_DESTINATIONS] non-favorited rows; past that
     * threshold each subsequent announce evicts the oldest excess
     * by `lastSeen` ASC. Favorited contacts and user-renamed entries
     * are exempt — those are deliberate state the user shouldn't
     * lose to flood pressure. Eviction is throttled to every
     * [EVICTION_INTERVAL_ANNOUNCES] new announces so a busy mesh
     * doesn't run the DELETE on every packet (Room/SQLDelight handle
     * the DELETE cheaply, but skipping it most of the time is
     * cheaper still). Audit reference: 2026-05-13 MED-2.
     *
     * **Cap lowered 2026-05-13 v1.1.26 from 5000 → 1000.** A
     * tester running on a busy mesh accumulated 1100+ destination
     * rows (each carrying a 64-byte publicKey BLOB + 16-byte
     * nextHop + appDataHex). The Flow-based `observeDestinations`
     * query in Room loaded the whole result into one CursorWindow,
     * which has a 2 MB Android default. 1100 × ~500 B overflowed:
     *
     *   FATAL EXCEPTION: java.lang.IllegalStateException: Couldn't
     *   read row 1123, col 0 from CursorWindow ...
     *
     * 1000 × ~500 B = ~500 KB, comfortably under the limit with
     * headroom for future column additions. Eviction also fires
     * EAGERLY at engine startup (see [evictDestinationsOnStartup])
     * so users whose tables grew past the cap on pre-1.1.26 builds
     * get cleaned up on next launch instead of waiting for 10
     * announces.
     */
    private var announcesSinceEviction = 0
    private val MAX_DESTINATIONS = 1_000
    private val EVICTION_INTERVAL_ANNOUNCES = 10

    private suspend fun maybeEvictDestinations() {
        announcesSinceEviction++
        if (announcesSinceEviction < EVICTION_INTERVAL_ANNOUNCES) return
        announcesSinceEviction = 0
        runCatching {
            val deleted = destinationRepo.evictUnfavoritedOldest(MAX_DESTINATIONS)
            if (deleted > 0) {
                _events.tryEmit(EngineEvent.Log(
                    "evicted $deleted unfavorited destination rows past $MAX_DESTINATIONS cap"
                ))
            }
        }.onFailure {
            _events.tryEmit(EngineEvent.Log("destination eviction failed: ${it.message}"))
        }
    }

    /**
     * Run the eviction immediately on engine init so any
     * pre-1.1.26 install whose table grew past 1000 rows gets
     * trimmed before the UI subscribes to the destinations Flow.
     * Without this, the first `observeDestinations()` query
     * after launch loads all rows into a CursorWindow and crashes
     * with IllegalStateException at the 2 MB Android default. We
     * also reset `announcesSinceEviction` so the normal cadence
     * counter doesn't run an immediate second eviction. Audit
     * reference: 2026-05-13 MED-2 follow-up.
     */
    suspend fun evictDestinationsOnStartup() {
        runCatching {
            val deleted = destinationRepo.evictUnfavoritedOldest(MAX_DESTINATIONS)
            if (deleted > 0) {
                _events.tryEmit(EngineEvent.Log(
                    "startup eviction: $deleted unfavorited destination rows past $MAX_DESTINATIONS cap"
                ))
            }
            announcesSinceEviction = 0
        }.onFailure {
            _events.tryEmit(EngineEvent.Log("startup destination eviction failed: ${it.message}"))
        }
    }

    /** Wall-clock millis of our most recent announce. Used by
     *  [sendAnnounceIfDue] to coalesce burst announces — without this,
     *  reconnect storms + per-link announces could trip rnsd's
     *  default-on ingress-control throttle and leave our destination
     *  silently held on the server. */
    private var lastAnnounceMs: Long = 0L

    /** Wall-clock ms of the last ratchet rotation. 0 = never rotated.
     *  Time-gated by [DEFAULT_RATCHET_INTERVAL_MS] so peers don't race
     *  in-flight DATA against rotation. */
    private var lastRatchetRotationMs: Long = 0L

    /** Minimum gap between throttled announces. Explicit user actions
     *  (Send-announce button, identity reset) still bypass this. */
    private val announceMinIntervalMs: Long = 15 * 60_000L

    /** Minimum age of our last announce before [sendMessage] forces a
     *  fresh re-announce up front. Keeps the recipient's delivery-side
     *  cache (`known` map / reverse_path) warm so its `ActiveTo` /
     *  `Recall(ourHash)` returns current values when it tries to send
     *  the reply. 60s is short enough that a single rapid-fire user
     *  burst (three /users taps in 30s) doesn't re-announce per tap,
     *  long enough that fwdsvc's cache prune (typical ~4 min) doesn't
     *  catch us off-guard between commands. */
    private val REANNOUNCE_BEFORE_SEND_MIN_MS: Long = 60_000L

    /** Load existing identity from storage, or generate a fresh one. */
    suspend fun ensureIdentity(): Identity {
        identity?.let { return it }
        val id = Identity(crypto)
        val stored = identityRepo.load()
        if (stored != null) {
            id.loadFromPrivateKeys(stored.encPrivKey, stored.sigPrivKey, stored.ratchetPrivKey)
            // HIGH-1 follow-up: if this load came from the legacy
            // plaintext columns (the repo returns them as-is when the
            // *Enc columns are null/empty), re-save through the repo
            // so the keys land in the vault-sealed columns and the
            // plaintext columns get cleared. Cheap (one row upsert)
            // and idempotent — once the row is migrated, subsequent
            // loads come from the encrypted columns and this re-save
            // becomes a no-op pass through the vault. Audit reference:
            // 2026-05-13 HIGH-1 follow-up.
            runCatching {
                identityRepo.save(stored)
            }.onFailure {
                _events.tryEmit(EngineEvent.Log(
                    "identity vault-seal migration failed: ${it::class.simpleName}: ${it.message}"
                ))
            }
        } else {
            id.generate()
            identityRepo.save(StoredIdentity(
                encPrivKey = id.encPrivKey!!,
                sigPrivKey = id.sigPrivKey!!,
                ratchetPrivKey = id.ratchetPrivKey,
            ))
        }
        identity = id
        // One-time cleanup: hide any self-row left behind by a prior
        // session that ingested our own looped-back announce. The
        // ingest filter prevents this happening again going forward.
        runCatching {
            val selfHex = id.hash!!.toHex()
            val existing = destinationRepo.get(selfHex)
            if (existing != null && !existing.hidden) {
                destinationRepo.delete(selfHex)
                _events.tryEmit(EngineEvent.Log("hid stale self-row $selfHex"))
            }
        }
        return id
    }

    /** Compute our `lxmf.delivery` destination hash. */
    suspend fun ourDestHash(): ByteArray {
        val id = ensureIdentity()
        return computeDestinationHash(crypto, "lxmf.delivery", id.hash!!)
    }

    /** Build the JSON payload that another app's QR scanner consumes. */
    suspend fun myIdentityCard(): IdentityCard.Payload {
        val id = ensureIdentity()
        val dest = ourDestHash()
        return IdentityCard.Payload(
            destHash = dest.toHex(),
            publicKey = id.publicKey.toHex(),
            ratchetPub = id.ratchetPubKey?.toHex(),
            displayName = displayNameProvider().ifBlank { "Reticulum Mobile" },
        )
    }

    /**
     * Apply an [IdentityCard] payload (manually pasted hex hash, scanned QR,
     * etc.) as a destination in the local store, marking it favorite by
     * default so it surfaces in the Messages tab. Re-uses any existing row
     * (preserving lastSeen / rssi / telemetry from prior announces).
     */
    suspend fun applyIdentityCard(card: IdentityCard.Payload): StoredDestination {
        val publicKey = card.publicKey.hexBytesOrThrow("publicKey", expectedLen = 64)
        val destBytes = card.destHash.hexBytesOrThrow("destHash", expectedLen = 16)
        val ratchet = card.ratchetPub?.hexBytesOrThrow("ratchetPub", expectedLen = 32)
        val identityHash = crypto.truncatedHash(publicKey, 16)

        val existing = destinationRepo.get(card.destHash)
        val merged = existing?.copy(
            identityHash = identityHash.toHex(),
            publicKey = publicKey,
            destHash = destBytes,
            ratchetPub = ratchet,
            displayName = card.displayName.ifBlank { existing.displayName },
            appName = "lxmf.delivery",
            appLabel = "LXMF delivery",
            favorite = true,
            source = if (existing.source == "announce") existing.source else "qr",
        ) ?: StoredDestination(
            hash = card.destHash,
            identityHash = identityHash.toHex(),
            publicKey = publicKey,
            destHash = destBytes,
            nameHash = ByteArray(0),  // filled in when an announce arrives
            ratchetPub = ratchet,
            displayName = card.displayName.ifBlank { "(QR import)" },
            appName = "lxmf.delivery",
            appLabel = "LXMF delivery",
            telemetry = null,
            lat = null,
            lon = null,
            appDataHex = "",
            lastSeen = nowMs(),
            rssi = null,
            favorite = true,
            source = "qr",
        )
        destinationRepo.upsertFromAnnounce(merged)
        _events.tryEmit(EngineEvent.Log("destination from QR: ${card.destHash}"))
        return merged
    }

    /**
     * Add a destination from a manually-typed hash. We don't have the public
     * key yet, so the row is a stub: visible in Nodes but not messagable
     * until an announce arrives and fills in the missing fields.
     */
    suspend fun addManualDestination(hashHex: String, label: String): StoredDestination {
        val normalized = hashHex.lowercase().filter { it != ':' && it != ' ' && it != '-' }
        require(normalized.length == 32 && normalized.all { it.isHexDigit() }) {
            "destination hash must be 32 hex chars (got ${normalized.length})"
        }
        val destBytes = normalized.hexBytesOrThrow("destHash", expectedLen = 16)
        val existing = destinationRepo.get(normalized)
        // The user-supplied label semantically belongs in userLabel, not
        // displayName: displayName is the announce-derived public name,
        // userLabel is the user's private nickname. Keeping these split
        // means a later announce can fill in displayName without
        // clobbering the user's intent. effectiveDisplayName prefers
        // userLabel anyway, so the UI renders the user's label as soon
        // as the row is created.
        val merged = existing?.copy(
            userLabel = label.takeIf { it.isNotBlank() } ?: existing.userLabel,
            favorite = true,
        ) ?: StoredDestination(
            hash = normalized,
            identityHash = "",
            publicKey = ByteArray(0),
            destHash = destBytes,
            nameHash = ByteArray(0),
            ratchetPub = null,
            displayName = "",  // empty until an announce arrives
            appName = null,
            appLabel = null,
            telemetry = null,
            lat = null,
            lon = null,
            appDataHex = "",
            lastSeen = nowMs(),
            rssi = null,
            favorite = true,
            source = "manual",
            userLabel = label.takeIf { it.isNotBlank() },
        )
        destinationRepo.upsertManualStub(merged)
        _events.tryEmit(EngineEvent.Log("manual destination: $normalized"))
        return merged
    }

    suspend fun setFavorite(hashHex: String, favorite: Boolean) {
        // The Messages-tab Inbox can synthesize a stub for a sender we
        // received an LXMF from before the announce arrived (no row in
        // `destinations` yet). Plain UPDATE on a missing row is a
        // silent no-op, so favoriting from such a row would do nothing.
        // Insert a manual stub first so the favorite flag has a row to
        // attach to. Idempotent: existing rows are left untouched by
        // upsertManualStub.
        val normalized = hashHex.lowercase()
        if (favorite && destinationRepo.get(normalized) == null) {
            val destBytes = runCatching { normalized.hexBytesOrThrow("destHash", expectedLen = 16) }.getOrNull()
            if (destBytes != null) {
                destinationRepo.upsertManualStub(StoredDestination(
                    hash = normalized,
                    identityHash = "",
                    publicKey = ByteArray(0),
                    destHash = destBytes,
                    nameHash = ByteArray(0),
                    ratchetPub = null,
                    displayName = "",
                    appName = null,
                    appLabel = null,
                    telemetry = null,
                    lat = null,
                    lon = null,
                    appDataHex = "",
                    lastSeen = nowMs(),
                    rssi = null,
                    favorite = false,
                    source = "inbox",
                ))
            }
        }
        destinationRepo.setFavorite(normalized, favorite)
    }

    /** Set or clear a local nickname for a contact. Pass null/blank to
     *  clear. Local-only — never transmitted on the wire. Wins over
     *  the announce-derived display name in [StoredDestination.effectiveDisplayName]. */
    suspend fun setUserLabel(hashHex: String, label: String?) {
        destinationRepo.setUserLabel(hashHex, label)
    }

    /**
     * Pack the current identity into a passphrase-encrypted archive
     * blob suitable for off-device backup. Format documented in
     * [io.github.thatsfguy.reticulum.crypto.IdentityArchive]. The user
     * supplies the passphrase; we never store it.
     *
     * Calls [ensureIdentity] first so a brand-new install can export
     * its freshly-generated identity without an explicit "create"
     * step.
     */
    suspend fun exportIdentity(passphrase: String): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must be non-empty" }
        ensureIdentity()  // make sure there's something to export
        val stored = identityRepo.load()
            ?: error("identity not loaded after ensureIdentity — internal error")
        // Snapshot the current display name through the provider so a
        // restoration on a clean install carries the user's chosen label
        // without forcing them to re-type it. Provider returns the empty
        // string when the user never set one — IdentityArchive encodes
        // that as name_len=0 in v0x02 so the round-trip stays clean.
        val name = runCatching { displayNameProvider() }.getOrNull()
        return io.github.thatsfguy.reticulum.crypto.IdentityArchive.pack(
            identity = stored,
            passphrase = passphrase,
            crypto = crypto,
            displayName = name,
        )
    }

    /**
     * Unpack [archive] using [passphrase] and replace the device's
     * current identity with it. Existing destinations / messages are
     * left in place (they're per-contact-hash, not per-our-identity)
     * but every active link session is torn down — they were keyed to
     * the OLD identity's signing key, and reusing them would emit
     * LRPROOFs/proofs signed with the new key, which the peer can't
     * verify.
     *
     * Caller MUST have already confirmed user intent (this overwrites
     * the current identity unrecoverably unless the previous one was
     * also exported).
     *
     * Returns the imported [StoredIdentity] on success. On failure
     * (wrong passphrase, malformed archive, tampering) the engine's
     * state is unchanged and the caller gets the underlying exception.
     */
    suspend fun importIdentity(
        archive: ByteArray,
        passphrase: String,
    ): io.github.thatsfguy.reticulum.crypto.IdentityArchivePayload {
        require(passphrase.isNotEmpty()) { "passphrase must be non-empty" }
        val payload = io.github.thatsfguy.reticulum.crypto.IdentityArchive
            .unpack(archive, passphrase, crypto)
            .getOrThrow()

        identityRepo.save(payload.identity)
        identity = null  // force reload from disk on next ensureIdentity()

        // Tear down any in-flight link sessions — they were established
        // under the OLD identity. Future packets on those links would
        // be signed with the new key and the peer would reject them.
        sessionsLock.withLock {
            // §6.7 — dispose each session so its KEEPALIVE loop
            // cancels deterministically; the parent scope's cancellation
            // is asynchronous and we don't want bogus pings going out on
            // a link whose keys we're about to discard.
            activeSessions.values.forEach { it.dispose() }
            activeSessions.clear()
            nomadLinks.clear()
            lxmfLinks.clear()
            rrcSessions.clear()
            linkKinds.clear()
        }

        // Re-derive our destination hash and emit so the new identity
        // gets announced on next opportunity. lastAnnounceMs reset
        // forces sendAnnounceIfDue to fire on the next prod.
        lastAnnounceMs = 0L
        val nameSuffix = payload.displayName?.takeIf { it.isNotEmpty() }
            ?.let { " (display name: $it)" } ?: ""
        _events.tryEmit(EngineEvent.Log("identity imported — re-announce will fire$nameSuffix"))
        return payload
    }

    /**
     * Delete a destination and every message associated with it. Used
     * by the Messages tab's "Delete conversation" action. Idempotent —
     * fine to call when one or the other is already gone. Does NOT
     * affect the local identity, transports, or other destinations.
     *
     * If a future announce from the same destHash arrives, it will be
     * re-added (just without prior message history).
     */
    suspend fun deleteDestinationAndMessages(hashHex: String) {
        runCatching { messageRepo.deleteForContact(hashHex) }
            .onFailure { _events.tryEmit(EngineEvent.Log("delete messages failed: ${it.message}")) }
        runCatching { destinationRepo.delete(hashHex) }
            .onFailure { _events.tryEmit(EngineEvent.Log("delete destination failed: ${it.message}")) }
        _events.tryEmit(EngineEvent.Log("deleted destination + messages: $hashHex"))
    }

    /** Clear conversation history for a destination but keep the
     *  destination row (favorite, public key, last-seen). The user can
     *  trigger this from inside a conversation when they want to wipe
     *  history without losing the contact. */
    suspend fun deleteMessagesForDestination(hashHex: String) {
        runCatching { messageRepo.deleteForContact(hashHex) }
            .onFailure { _events.tryEmit(EngineEvent.Log("clear messages failed: ${it.message}")) }
        _events.tryEmit(EngineEvent.Log("cleared messages for: $hashHex"))
    }

    /** Public hook for transport-layer code to emit lines into the
     *  diagnostics log alongside engine-originated entries. */
    fun logExternal(line: String) {
        _events.tryEmit(EngineEvent.Log(line))
    }

    /**
     * Issue a Reticulum path request for [targetDestHash]. Mirrors
     * `RNS.Transport.request_path()` from Python RNS. Other peers on the
     * mesh that know the path respond by re-announcing the destination,
     * which refreshes our local transport node's routing table so a
     * subsequent LINKREQUEST has a definite forward path AND a return
     * path for the LRPROOF.
     *
     * Wire format (matches Python RNS Transport.py:2541-2588):
     *   - Packet: HEADER_1, DATA, PLAIN destination, BROADCAST transport, ctx=NONE
     *   - destHash: SHA-256(SHA-256("rnstransport.path.request")[:10])[:16]
     *   - payload:  target_dest_hash(16) + random_tag(16) = 32 bytes
     *     (only non-transport-enabled clients; transport instances also
     *      include their own identity hash, which we are not.)
     */
    suspend fun requestPath(targetDestHash: ByteArray) {
        require(targetDestHash.size == 16) { "targetDestHash must be 16 bytes" }
        if (!hasAnyTransport()) return
        val pathReqDest = computeDestinationHash(crypto, "rnstransport.path.request", ByteArray(0))
        val tag = crypto.randomBytes(16)
        val payload = targetDestHash + tag
        val packet = buildPacket(
            headerType = HEADER_1,
            transportType = TRANSPORT_BROADCAST,
            destType = io.github.thatsfguy.reticulum.protocol.DEST_PLAIN,
            packetType = PACKET_DATA,
            destHash = pathReqDest,
            context = CTX_NONE,
            payload = payload,
        )
        // Path requests fan out on every attached transport — each fabric
        // has its own transit table to refresh.
        broadcast(packet)
        _events.tryEmit(EngineEvent.Log("path? ${targetDestHash.toHex()}"))
    }

    /**
     * Open a Reticulum Link to [destinationHash] and fetch a NomadNet page
     * via REQUEST/RESPONSE.
     *
     * Limitation: only single-packet pages work right now. Pages whose
     * encrypted plaintext exceeds the link MTU (~383 bytes after Token
     * overhead) need Reticulum Resource fragmentation, which is on the
     * follow-up list. Errors surface in the returned [Result].
     */
    suspend fun fetchNomadPage(
        destinationHash: String,
        path: String = "/page/index.mu",
        proofTimeoutMs: Long? = null,
        responseTimeoutMs: Long? = null,
        /** Optional request data sent as the third element of the
         *  envelope `[time, path_hash, data]`. For NomadNet form posts
         *  this is the dict `{ "field_<name>": "<value>", ... }`
         *  upstream Node.py:109-111 expects. `null` = simple GET (msgpack
         *  nil). The engine msgpack-encodes the whole envelope once;
         *  callers must NOT pre-encode `data` themselves. */
        data: Any? = null,
        /** v0.1.64: when true, send a CTX_LINKIDENTIFY packet
         *  (`LINKIDENTIFY = 0xFB`) right after LRPROOF lands, BEFORE
         *  the REQUEST. Required for ALLOW_LIST pages — server-side
         *  Node.py:152-154 keys auth on `remote_identity.hash`, which
         *  is None unless the client identifies. Default off because
         *  identifying on every link reveals the user's long-term
         *  identity hash to the page operator (see SPEC.md §11.6.6
         *  privacy note). UI surfaces this as an opt-in toggle. */
        identify: Boolean = false,
    ): Result<String> = runCatching {
        val dest = destinationRepo.get(destinationHash) ?: error("Unknown destination $destinationHash")
        require(dest.publicKey.size == 64) {
            "No public key for $destinationHash yet — wait for an announce"
        }
        if (!hasAnyTransport()) error("No transport attached — connect on the Settings tab first")
        // Adaptive timeout: scale with hop count. The flat 45 s used
        // pre-v0.1.47 timed out cleanly for 4-hop ALAYA and 6-hop
        // Cryptid_Node 2026-05-03 even when the path was known. Caller
        // can still pass an explicit override for tests / power users.
        val proofTimeout = proofTimeoutMs ?: proofTimeoutForHops(dest.hopCount)
        val responseTimeout = responseTimeoutMs ?: proofTimeoutForHops(dest.hopCount)

        // v0.1.66: try to reuse an existing ACTIVE link for this dest.
        // Saves LRPROOF round-trip on intra-node nav (index → about →
        // help). Mirrors Browser.py:1167-1213 — reuse if dest matches
        // and link is still ACTIVE; identify state must also match
        // because Browser fires identify once at link-up.
        val reused = sessionsLock.withLock {
            nomadLinks[destinationHash]?.takeIf {
                it.session.link.state == io.github.thatsfguy.reticulum.link.LinkState.ACTIVE &&
                    it.identified == identify
            }
        }
        if (reused != null) {
            _events.tryEmit(EngineEvent.Log("[${reused.linkIdHex}] reusing active link for $destinationHash$path"))
            val pathHash = crypto.sha256(path.encodeToByteArray()).copyOfRange(0, 16)
            val responseBytes = reused.session.request(pathHash, data, responseTimeout)
            if (responseBytes != null) {
                _events.tryEmit(EngineEvent.Log("page received (reused link): ${responseBytes.size} bytes"))
                return@runCatching cachePageAndReturn(
                    decoded = responseBytes.decodeToString(),
                    destinationHash = destinationHash,
                    path = path,
                    sizeBytes = responseBytes.size,
                    isPost = data != null,
                )
            }
            // Reused link's request timed out — fall through to fresh
            // establishment. Drop the cached session first.
            sessionsLock.withLock { nomadLinks.remove(destinationHash) }
            _events.tryEmit(EngineEvent.Log("reused link timed out — reconnecting"))
        }

        val targetSigPub = dest.publicKey.copyOfRange(32, 64)
        val (link, requestData) = Link.createInitiator(
            peerLongTermSigPub = targetSigPub,
            peerDestHash = dest.destHash,
            crypto = crypto,
            nowMs = nowMs(),
        )
        // §2.3 LINKREQ conversion: same rule as DATA. Upstream RNS Transport
        // silently drops a HEADER_1 LINKREQ addressed to a destination that
        // isn't locally attached — without a transport_id the transport node
        // has nothing to forward against and the LINKREQ never reaches the
        // responder. Reproduced 2026-05-03 against tools/test_nomadnet_node.py
        // via local transport node — LRPROOF never came back.
        val useHeader2Lr = dest.hopCount > 1 && dest.nextHop != null
        val linkReqPacket = buildPacket(
            headerType = if (useHeader2Lr) HEADER_2 else HEADER_1,
            transportType = if (useHeader2Lr) TRANSPORT_TRANSPORT else TRANSPORT_BROADCAST,
            destType = DEST_SINGLE,
            packetType = PACKET_LINKREQ,
            destHash = dest.destHash,
            transportId = if (useHeader2Lr) dest.nextHop else null,
            payload = requestData,
        )
        // The link_id is computed from the LINKREQUEST as it was packed,
        // so we have to parse our own outbound packet to derive it.
        val parsed = parsePacket(linkReqPacket) ?: error("self-parse failed")
        link.setLinkIdFromPacket(parsed)

        val linkIdHex = link.linkId!!.toHex()
        val session = LinkSession(
            link = link,
            crypto = crypto,
            sender = { pkt -> sendForLink(linkIdHex, pkt) },
            nowMs = nowMs,
            logger = { line -> _events.tryEmit(EngineEvent.Log("[$linkIdHex] $line")) },
        )
        if (useHeader2Lr) {
            _events.tryEmit(EngineEvent.Log(
                "[$linkIdHex] LINKREQ → using HEADER_2 (hops=${dest.hopCount}, transport_id=${dest.nextHop?.toHex()})"
            ))
        }
        sessionsLock.withLock { activeSessions[linkIdHex] = session }
        try {
            // Issue a path request for the target so each relay along
            // the way refreshes its forward path before we send the
            // LINKREQUEST. (We deliberately do NOT announce here — see
            // the throttle on sendAnnounceIfDue. Per-link announce
            // bursts are what trip rnsd's default-on ingress control
            // and silently park our destination on remote transports.)
            primePath(
                destHash = dest.destHash,
                requestPath = { hash -> requestPath(hash) },
                delayMs = { ms -> delay(ms) },
                onPathFailure = { _events.tryEmit(EngineEvent.Log("path? failed: ${it.message}")) },
            )

            // Route LINKREQUEST via affinity (transport that last heard
            // this destination's announce) — falls back to broadcast
            // for first-contact / unknown affinity. Pre-affinity
            // version always broadcast on every attached transport,
            // which amplified into the wider mesh on TCP+BLE setups
            // and slowed direct LoRa peers down.
            sendToDestination(destinationHash, linkReqPacket)
            _events.tryEmit(EngineEvent.Log("link → $destinationHash (link_id=$linkIdHex)"))

            // Snapshot transport-set size at fetch start so we can detect
            // a mid-fetch disconnect on timeout — diagnostic for "no
            // LRPROOF" failures.
            val transportsAtStart = transports.size

            when (val proof = session.awaitProof(proofTimeout)) {
                is LinkSession.ProofResult.Validated -> {
                    _events.tryEmit(EngineEvent.Log("link active, requesting $path"))
                    session.startKeepalive(scope)
                }
                is LinkSession.ProofResult.Invalid -> {
                    error("LRPROOF rejected: ${proof.reason}. ${session.diagnosticSummary()}")
                }
                LinkSession.ProofResult.Timeout -> {
                    val diag = classifyLinkFailure(dest.hopCount, dest.lastSeen, nowMs())
                    val rxDetail = session.diagnosticSummary().ifEmpty { "no inbound packets on link_id during wait" }
                    val transportNote = if (transports.isEmpty() && transportsAtStart > 0) {
                        " All transports disconnected during the wait."
                    } else if (transports.size != transportsAtStart) {
                        " Transport set changed mid-fetch (was $transportsAtStart, now ${transports.size})."
                    } else ""
                    error("No LRPROOF received within ${proofTimeout / 1000}s. $diag$transportNote\n  $rxDetail")
                }
            }

            // v0.1.64: optional LINKIDENTIFY before the REQUEST. Required
            // for ALLOW_LIST pages (server-side Node.py:152-154 reads
            // `remote_identity.hash` for the auth check). UI surfaces this
            // as an opt-in toggle so the user's long-term identity hash
            // isn't pinned to every public node they browse.
            if (identify) {
                val ourIdentity = ensureIdentity()
                val identifyCipher = link.buildIdentifyPayload(ourIdentity)
                val identifyPacket = buildPacket(
                    destType = io.github.thatsfguy.reticulum.protocol.DEST_LINK,
                    packetType = PACKET_DATA,
                    destHash = link.linkId!!,
                    context = io.github.thatsfguy.reticulum.protocol.CTX_LINKIDENTIFY,
                    payload = identifyCipher,
                )
                sendForLink(linkIdHex, identifyPacket)
                _events.tryEmit(EngineEvent.Log("[$linkIdHex] → LINKIDENTIFY (auth)"))
            }

            // Spec §11.1: request_path_hash is the 16-byte truncation of
            // SHA-256(path). Upstream Destination.register_request_handler
            // keys its handler dict on this 16-byte form, so a 32-byte
            // hash never matches a registered handler.
            val pathHash = crypto.sha256(path.encodeToByteArray()).copyOfRange(0, 16)
            val responseBytes = session.request(pathHash, data, responseTimeout) ?: run {
                // Link came up (we got the LRPROOF) but no body arrived.
                // The session diagnostic now distinguishes the cases:
                //  - silence after LRPROOF → server didn't run the handler
                //  - RESOURCE_ADV but partial RESOURCE → mid-stream drop
                //  - RESOURCE_ADV + complete but no PRF emit → handshake glitch
                val rxDetail = session.diagnosticSummary().ifEmpty { "no further packets after LRPROOF" }
                val pinnedKind = linkKinds[linkIdHex]
                val transportNote = if (pinnedKind != null && transports[pinnedKind] == null)
                    " Pinned transport ($pinnedKind) disconnected during fetch." else ""
                error("No RESPONSE within ${responseTimeout / 1000}s — link came up but no body.${transportNote}\n  $rxDetail")
            }

            _events.tryEmit(EngineEvent.Log("page received: ${responseBytes.size} bytes"))
            // v0.1.66: cache the just-established session in nomadLinks
            // so the next fetch to this destHash can reuse it. Keep it
            // in activeSessions too (the engine pump dispatches inbound
            // packets through that map).
            sessionsLock.withLock {
                nomadLinks[destinationHash] = NomadLink(session, identified = identify, linkIdHex = linkIdHex)
            }
            return@runCatching cachePageAndReturn(
                decoded = responseBytes.decodeToString(),
                destinationHash = destinationHash,
                path = path,
                sizeBytes = responseBytes.size,
                isPost = data != null,
            )
        } finally {
            // v0.1.66: do NOT remove from activeSessions here — the
            // session is now cached in nomadLinks for reuse and still
            // needs to receive inbound packets via the engine pump.
            // Removal happens on KEEPALIVE timeout, link close, or
            // engine detach (clearNomadLinks).
        }
    }

    /**
     * Result of a successful `/file/` download from a NomadNet node.
     * `filename` comes from the server-supplied `metadata["name"]`
     * (Node.py:128-141 returns `os.path.basename(fdest)` UTF-8 encoded
     * as bytes); falls back to the trailing component of the request
     * `path` when the server didn't supply one. `bytes` is the raw
     * file content with the §10.2 step 1 metadata prefix already
     * stripped by [Resource.assemble].
     */
    data class DownloadedFile(
        val filename: String,
        val bytes: ByteArray,
    )

    /**
     * Fetch a `/file/<path>` resource from a NomadNet node. Mirrors
     * [fetchNomadPage]'s link establish + REQUEST + RESPONSE plumbing,
     * but the response is the raw file body (binary) with the filename
     * lifted from the §10.2 step 1 metadata prefix.
     *
     * Spec references:
     *   - SPEC §10.2 step 1 — metadata prefix wire format
     *   - SPEC §10.4 — ADV `has_metadata` flag (bit 5 of `f`)
     *   - Upstream `markqvist/NomadNet/nomadnet/Node.py:128-141`:
     *     ```
     *     return [open(fdest, "rb"), {"name": os.path.basename(fdest).encode("utf-8")}]
     *     ```
     *   - Upstream `markqvist/Reticulum/RNS/Link.py:895` wraps that
     *     into a Resource with `metadata={"name": ...}` and the file
     *     bytes as the body.
     *
     * Server-side path resolution (NomadNet `Node.py:115-127`): the
     * `/file/` path prefix is mapped to the node's `filespath`
     * directory. Symlinks are honored. ALLOW_LIST gating is the same
     * as for pages — pass `identify = true` to send a LINKIDENTIFY
     * before the REQUEST if the file is auth-gated.
     */
    suspend fun fetchNomadFile(
        destinationHash: String,
        path: String,
        proofTimeoutMs: Long? = null,
        responseTimeoutMs: Long? = null,
        identify: Boolean = false,
    ): Result<DownloadedFile> = runCatching {
        require(path.startsWith("/file/")) {
            "fetchNomadFile only handles `/file/` paths; got `$path` — use fetchNomadPage for `/page/`"
        }
        val dest = destinationRepo.get(destinationHash) ?: error("Unknown destination $destinationHash")
        require(dest.publicKey.size == 64) {
            "No public key for $destinationHash yet — wait for an announce"
        }
        if (!hasAnyTransport()) error("No transport attached — connect on the Settings tab first")
        val proofTimeout = proofTimeoutMs ?: proofTimeoutForHops(dest.hopCount)
        val responseTimeout = responseTimeoutMs ?: proofTimeoutForHops(dest.hopCount)

        val pathHash = crypto.sha256(path.encodeToByteArray()).copyOfRange(0, 16)

        // Reuse an active link to the same node if one's parked in
        // nomadLinks — same logic as fetchNomadPage. Saves the LRPROOF
        // round-trip when the user just opened the page that linked
        // to this file.
        val reused = sessionsLock.withLock {
            nomadLinks[destinationHash]?.takeIf {
                it.session.link.state == io.github.thatsfguy.reticulum.link.LinkState.ACTIVE &&
                    it.identified == identify
            }
        }
        if (reused != null) {
            _events.tryEmit(EngineEvent.Log("[${reused.linkIdHex}] reusing active link for $destinationHash$path"))
            val responseBytes = reused.session.request(pathHash, null, responseTimeout)
            if (responseBytes != null) {
                return@runCatching wrapDownloadedFile(responseBytes, reused.session.lastResponseMetadata, path)
            }
            sessionsLock.withLock { nomadLinks.remove(destinationHash) }
            _events.tryEmit(EngineEvent.Log("reused link timed out — reconnecting"))
        }

        // Fresh link establish — copy of the fetchNomadPage path with
        // the /file/-specific result handling at the end. Refactoring
        // the shared plumbing is on the follow-up list once a third
        // fetcher emerges (e.g. /stream/, /api/).
        val targetSigPub = dest.publicKey.copyOfRange(32, 64)
        val (link, requestData) = Link.createInitiator(
            peerLongTermSigPub = targetSigPub,
            peerDestHash = dest.destHash,
            crypto = crypto,
            nowMs = nowMs(),
        )
        val useHeader2Lr = dest.hopCount > 1 && dest.nextHop != null
        val linkReqPacket = buildPacket(
            headerType = if (useHeader2Lr) HEADER_2 else HEADER_1,
            transportType = if (useHeader2Lr) TRANSPORT_TRANSPORT else TRANSPORT_BROADCAST,
            destType = DEST_SINGLE,
            packetType = PACKET_LINKREQ,
            destHash = dest.destHash,
            transportId = if (useHeader2Lr) dest.nextHop else null,
            payload = requestData,
        )
        val parsed = parsePacket(linkReqPacket) ?: error("self-parse failed")
        link.setLinkIdFromPacket(parsed)
        val linkIdHex = link.linkId!!.toHex()
        val session = LinkSession(
            link = link,
            crypto = crypto,
            sender = { pkt -> sendForLink(linkIdHex, pkt) },
            nowMs = nowMs,
            logger = { line -> _events.tryEmit(EngineEvent.Log("[$linkIdHex] $line")) },
        )
        sessionsLock.withLock { activeSessions[linkIdHex] = session }
        primePath(
            destHash = dest.destHash,
            requestPath = { hash -> requestPath(hash) },
            delayMs = { ms -> delay(ms) },
            onPathFailure = { _events.tryEmit(EngineEvent.Log("path? failed: ${it.message}")) },
        )
        sendToDestination(destinationHash, linkReqPacket)
        _events.tryEmit(EngineEvent.Log("link → $destinationHash (link_id=$linkIdHex) for $path"))

        when (val proof = session.awaitProof(proofTimeout)) {
            is LinkSession.ProofResult.Validated -> {
                _events.tryEmit(EngineEvent.Log("link active, requesting $path"))
                session.startKeepalive(scope)
            }
            is LinkSession.ProofResult.Invalid ->
                error("LRPROOF rejected: ${proof.reason}")
            LinkSession.ProofResult.Timeout ->
                error("No LRPROOF received within ${proofTimeout / 1000}s")
        }

        if (identify) {
            val ourIdentity = ensureIdentity()
            val identifyCipher = link.buildIdentifyPayload(ourIdentity)
            val identifyPacket = buildPacket(
                destType = io.github.thatsfguy.reticulum.protocol.DEST_LINK,
                packetType = PACKET_DATA,
                destHash = link.linkId!!,
                context = io.github.thatsfguy.reticulum.protocol.CTX_LINKIDENTIFY,
                payload = identifyCipher,
            )
            sendForLink(linkIdHex, identifyPacket)
        }

        val responseBytes = session.request(pathHash, null, responseTimeout)
            ?: error("No RESPONSE within ${responseTimeout / 1000}s — file fetch")
        sessionsLock.withLock {
            nomadLinks[destinationHash] = NomadLink(session, identified = identify, linkIdHex = linkIdHex)
        }
        wrapDownloadedFile(responseBytes, session.lastResponseMetadata, path)
    }

    /**
     * Convert a raw [responseBytes] + optional metadata map into a
     * [DownloadedFile]. The filename comes from `metadata["name"]`
     * (msgpack bin UTF-8 per Node.py); falls back to the trailing
     * path component if the server didn't supply metadata (legacy
     * servers, sanitization, etc.).
     */
    private fun wrapDownloadedFile(
        responseBytes: ByteArray,
        metadata: Map<Any?, Any?>?,
        path: String,
    ): DownloadedFile {
        val fromMetadata = (metadata?.get("name") as? ByteArray)?.decodeToString()
        val filename = fromMetadata?.takeIf { it.isNotBlank() }
            ?: path.substringAfterLast('/').ifBlank { "download" }
        _events.tryEmit(EngineEvent.Log("file received: $filename (${responseBytes.size} B)"))
        return DownloadedFile(filename = filename, bytes = responseBytes)
    }

    /**
     * Apply per-page cache rules and return the decoded page source.
     * Used by both the fresh-link and reused-link branches of
     * fetchNomadPage so caching behavior stays identical.
     */
    private suspend fun cachePageAndReturn(
        decoded: String,
        destinationHash: String,
        path: String,
        sizeBytes: Int,
        isPost: Boolean,
    ): String {
        // Cache only for plain GETs — form-post responses are body-
        // dependent and pollute the cache for subsequent GETs.
        if (!isPost) {
            // v0.1.62: respect server's `#!c=N` cache-TTL hint per
            // Browser.py:1315-1335. 0 = "do not cache". Defensive
            // runCatching: a malformed response that breaks parseDocument
            // shouldn't take down the whole fetch — fall back to default
            // caching (treat ttl as null).
            val ttl = runCatching {
                io.github.thatsfguy.reticulum.nomad.Micron.parseDocument(decoded).cacheTtlSeconds
            }.getOrNull()
            if (ttl == 0) {
                _events.tryEmit(EngineEvent.Log("page cache: skipped — server set #!c=0"))
            } else {
                nomadPageCache?.let { cache ->
                    runCatching {
                        cache.put(io.github.thatsfguy.reticulum.store.StoredNomadPage(
                            destHash  = destinationHash,
                            path      = path,
                            source    = decoded,
                            fetchedAt = nowMs(),
                            byteSize  = sizeBytes,
                        ))
                    }.onFailure { _events.tryEmit(EngineEvent.Log("page cache write failed: ${it.message}")) }
                }
            }
        }
        return decoded
    }

    /**
     * Result of a propagation /get poll. [tidsAdvertised] = how many
     * messages the propagation node says it has queued for us.
     * [messagesStored] = how many we actually decoded and saved.
     * [resourceDeferred] = true when round 2 timed out, which usually
     * means the response went out as a multi-packet Resource that we
     * can't yet receive.
     */
    data class PropagationSyncResult(
        val tidsAdvertised: Int,
        val messagesStored: Int,
        val resourceDeferred: Boolean,
        val errorMessage: String? = null,
    )

    /**
     * Try [syncPropagation] against the best-ranked candidates from our
     * destinations table until one returns successfully or the candidate
     * list is exhausted. Ranking is `(hopCount asc, lastSeen desc)`:
     * closest first, freshest tie-break. We bail after [maxAttempts]
     * candidates so a network of 200+ propagation nodes doesn't pin the
     * UI for an hour.
     */
    suspend fun syncPropagationAuto(maxAttempts: Int = 5): PropagationSyncResult {
        val candidates = destinationRepo.getAll()
            .filter { it.appName == "lxmf.propagation" && it.publicKey.size == 64 && !it.hidden }
            .sortedWith(compareBy({ it.hopCount }, { -it.lastSeen }))
            .take(maxAttempts)

        if (candidates.isEmpty()) {
            return PropagationSyncResult(0, 0, false, "no propagation nodes seen yet")
        }
        _events.tryEmit(EngineEvent.Log(
            "propagation: ${candidates.size} candidate(s) ranked by hops; best=${candidates.first().hash} (${candidates.first().hopCount} hops)"
        ))
        for ((i, node) in candidates.withIndex()) {
            _events.tryEmit(EngineEvent.Log(
                "propagation: trying ${node.hash} (${node.hopCount} hops, ${(nowMs() - node.lastSeen) / 60_000}m ago) [${i+1}/${candidates.size}]"
            ))
            // Short timeouts so a dead node doesn't block the cascade.
            // First-hit wins; the loop exits as soon as one node returns
            // a non-error sync result with at least the round-1 list.
            val result = syncPropagation(
                propagationNodeHash = node.hash,
                proofTimeoutMs = 20_000L,
                roundTimeoutMs = 15_000L,
            )
            if (result.errorMessage == null) {
                return result
            }
            _events.tryEmit(EngineEvent.Log("propagation: ${node.hash} → ${result.errorMessage}"))
        }
        return PropagationSyncResult(0, 0, false, "all ${candidates.size} candidate(s) failed")
    }

    /**
     * Open a Reticulum Link to a propagation node and run the 3-phase
     * /get fetch. Each delivered LXMF blob is unpacked + saved via the
     * same code path as opportunistic delivery, so notifications fire
     * exactly the same way. Caller (UI / service) is responsible for
     * the polling cadence.
     */
    suspend fun syncPropagation(
        propagationNodeHash: String,
        proofTimeoutMs: Long = 45_000L,
        roundTimeoutMs: Long = 30_000L,
    ): PropagationSyncResult = try {
        val dest = destinationRepo.get(propagationNodeHash)
            ?: return PropagationSyncResult(0, 0, false, "Unknown propagation node $propagationNodeHash")
        require(dest.publicKey.size == 64) { "No public key for $propagationNodeHash yet — wait for its announce" }
        if (!hasAnyTransport()) error("No transport attached — connect on the Settings tab first")
        val id = ensureIdentity()

        val targetSigPub = dest.publicKey.copyOfRange(32, 64)
        val (link, requestData) = Link.createInitiator(
            peerLongTermSigPub = targetSigPub,
            peerDestHash = dest.destHash,
            crypto = crypto,
            nowMs = nowMs(),
        )
        // §2.3 LINKREQ conversion — see fetchNomadPage for the full why.
        val useHeader2Lr = dest.hopCount > 1 && dest.nextHop != null
        val linkReqPacket = buildPacket(
            headerType = if (useHeader2Lr) HEADER_2 else HEADER_1,
            transportType = if (useHeader2Lr) TRANSPORT_TRANSPORT else TRANSPORT_BROADCAST,
            destType = DEST_SINGLE,
            packetType = PACKET_LINKREQ,
            destHash = dest.destHash,
            transportId = if (useHeader2Lr) dest.nextHop else null,
            payload = requestData,
        )
        val parsed = parsePacket(linkReqPacket) ?: error("self-parse failed")
        link.setLinkIdFromPacket(parsed)

        val linkIdHex = link.linkId!!.toHex()
        val session = LinkSession(
            link = link,
            crypto = crypto,
            sender = { pkt -> sendForLink(linkIdHex, pkt) },
            nowMs = nowMs,
            logger = { line -> _events.tryEmit(EngineEvent.Log("[prop $linkIdHex] $line")) },
        )
        if (useHeader2Lr) {
            _events.tryEmit(EngineEvent.Log(
                "[prop $linkIdHex] LINKREQ → using HEADER_2 (hops=${dest.hopCount}, transport_id=${dest.nextHop?.toHex()})"
            ))
        }
        sessionsLock.withLock { activeSessions[linkIdHex] = session }
        try {
            primePath(
                destHash = dest.destHash,
                requestPath = { hash -> requestPath(hash) },
                delayMs = { ms -> delay(ms) },
                onPathFailure = { _events.tryEmit(EngineEvent.Log("[prop $linkIdHex] path? failed: ${it.message}")) },
            )
            // LRPROOF arrival pins the link's kind via linkKinds. Same
            // pattern as fetchNomadPage; affinity-routed so a propagation
            // node we last heard via TCP doesn't get a duplicate
            // LINKREQ on every attached transport.
            sendToDestination(propagationNodeHash, linkReqPacket)
            _events.tryEmit(EngineEvent.Log("propagation link → $propagationNodeHash"))

            when (val proof = session.awaitProof(proofTimeoutMs)) {
                is LinkSession.ProofResult.Validated -> session.startKeepalive(scope)
                is LinkSession.ProofResult.Invalid -> error("LRPROOF rejected: ${proof.reason}")
                LinkSession.ProofResult.Timeout ->
                    error("no LRPROOF within ${proofTimeoutMs / 1000}s — propagation node may be down")
            }

            val client = PropagationClient(
                session = session,
                identity = id,
                crypto = crypto,
                sender = { pkt -> sendForLink(linkIdHex, pkt) },
                logger = { line -> _events.tryEmit(EngineEvent.Log("[prop $linkIdHex] $line")) },
            )
            client.identify()
            // Tiny settle so the node processes our identify before /get.
            delay(250L)

            val result = client.pollAll(roundTimeoutMs = roundTimeoutMs)

            // Save each delivered LXMF blob via the opportunistic path.
            // Format on the wire is: source_hash(16) + sig(64) + msgpack
            // — same as a token-decrypted opportunistic packet plaintext.
            var stored = 0
            val ourDest = ourDestHash()
            for (blob in result.messagesReceived) {
                runCatching {
                    val msg = io.github.thatsfguy.reticulum.lxmf.unpackMessage(blob, ourDest, crypto)
                    val sourceHashHex = msg.sourceHash.toHex()
                    val senderRow = destinationRepo.get(sourceHashHex)
                    val senderKey = senderRow?.publicKey?.takeIf { it.size == 64 }
                    val variant = senderKey?.let {
                        val senderId = Identity(crypto)
                        senderId.loadFromPublicKey(it)
                        io.github.thatsfguy.reticulum.lxmf.verifyMessageSignature(msg, senderId, crypto)
                    }
                    // SECURITY (audit H2): a known sender key that fails to
                    // verify means the message is forged or the propagation
                    // node tampered with it — drop rather than surface it.
                    if (senderKey != null && variant == null) {
                        _events.tryEmit(EngineEvent.Log(
                            "dropped propagation LXMF with invalid signature from $sourceHashHex — forged",
                        ))
                        return@runCatching
                    }
                    val isUnverified = variant == null
                    // MED-6 opt-in (see opportunistic-path twin). Drop
                    // propagated-and-unverified messages on the floor
                    // when the user has opted out.
                    if (isUnverified && dropUnverifiedProvider()) {
                        _events.tryEmit(EngineEvent.Log(
                            "dropped unverified propagation msg from $sourceHashHex (user opted out)"
                        ))
                        return@runCatching
                    }
                    // Field 16 dispatch (see opportunistic-path twin).
                    val payload16 = extractField16(msg.fields)
                    if (payload16 is Field16Payload.Reaction) {
                        val applied = messageRepo.applyReaction(
                            payload16.reactionTo, payload16.emoji, payload16.sender,
                        )
                        _events.tryEmit(EngineEvent.Log(
                            if (applied) "reaction ${payload16.emoji} from $sourceHashHex applied to ${payload16.reactionTo.take(16)}…"
                            else "reaction ${payload16.emoji} from $sourceHashHex dropped — target ${payload16.reactionTo.take(16)}… not found locally"
                        ))
                        stored++
                        return@runCatching
                    }
                    val replyToMessageId = (payload16 as? Field16Payload.Reply)?.replyTo
                    val messageIdHex = io.github.thatsfguy.reticulum.lxmf.LxmfStamp
                        .computeMessageId(ourDest, msg.sourceHash, msg.msgpackForId, crypto).toHex()
                    // SECURITY (audit M4): durable replay dedup. The
                    // canonical message_id is deterministic per message,
                    // so an already-stored id means this is a replay —
                    // survives a restart, unlike the in-memory data set.
                    if (messageRepo.getByMessageId(messageIdHex) != null) {
                        _events.tryEmit(EngineEvent.Log(
                            "duplicate propagation LXMF $messageIdHex — already stored, dropping",
                        ))
                        return@runCatching
                    }
                    val (imageBytes, imageRawSize) = extractImageField(msg.fields)
                    if (imageBytes == null && imageRawSize > 0) {
                        _events.tryEmit(EngineEvent.Log(
                            "propagation msg from $sourceHashHex: image field ${imageRawSize} B > ${INBOUND_IMAGE_MAX_BYTES} B — dropped"
                        ))
                    }
                    val savedId = messageRepo.save(StoredMessage(
                        contactHash = sourceHashHex,
                        direction = "incoming",
                        content = msg.content,
                        title = msg.title,
                        timestamp = correctClocklessTimestamp(msg.timestamp, nowMs()),
                        state = if (!isUnverified) "verified" else "unverified",
                        rawPacket = if (isUnverified) blob else null,
                        imageBytes = imageBytes,
                        messageId = messageIdHex,
                        replyToMessageId = replyToMessageId,
                        // v1.1.39 — uniform routing rule. Propagation
                        // /get pulls already-rebroadcast LXMFs out of
                        // the propagation node's spool; source_hash IS
                        // the originator at this point (fwdsvc-prefix
                        // text + fwdsvc-signed if the rebroadcast came
                        // via fwdsvc). Same fallback the link path
                        // uses when LINKIDENTIFY is absent.
                        arrivedViaDest = sourceHashHex,
                    ))
                    _events.tryEmit(EngineEvent.MessageReceived(
                        messageId = savedId,
                        contactHash = sourceHashHex,
                        content = msg.content,
                        verified = !isUnverified,
                    ))
                    if (isUnverified && pathRequestsSent.add(sourceHashHex)) {
                        runCatching { requestPath(msg.sourceHash) }
                    }
                    stored++
                }.onFailure {
                    _events.tryEmit(EngineEvent.Log("propagation msg unpack failed: ${it.message}"))
                }
            }

            PropagationSyncResult(
                tidsAdvertised = result.tidsAdvertised.size,
                messagesStored = stored,
                resourceDeferred = result.multiPacketDeferred,
            )
        } finally {
            sessionsLock.withLock { activeSessions.remove(linkIdHex)?.dispose() }
        }
    } catch (t: Throwable) {
        PropagationSyncResult(0, 0, false, t.message ?: t::class.simpleName)
    }

    /** Discard current identity, generate fresh keys, immediately re-announce. */
    suspend fun resetIdentity(): Identity {
        identity = null
        val id = Identity(crypto)
        id.generate()
        identityRepo.save(StoredIdentity(
            encPrivKey = id.encPrivKey!!,
            sigPrivKey = id.sigPrivKey!!,
            ratchetPrivKey = id.ratchetPrivKey,
        ))
        identity = id
        _events.tryEmit(EngineEvent.Log("identity reset (dest=${id.hash!!.toHex()})"))
        runCatching { sendAnnounce() }.onFailure {
            _events.tryEmit(EngineEvent.Log("re-announce after reset failed: ${it.message}"))
        }
        return id
    }

    /** Send [packet] on every attached transport. Used for non-link
     *  outbound traffic — announces, path requests, opportunistic LXMF,
     *  initiator LINKREQUESTs. Receiver-side dedup handles double receipt. */
    private suspend fun broadcast(packet: ByteArray) {
        val snap = transports.values.toList()
        if (snap.isEmpty()) return
        snap.forEach { att ->
            runCatching { att.transport.send(packet) }.onFailure {
                _events.tryEmit(EngineEvent.Log("tx failed on ${att.transport::class.simpleName}: ${it.message}"))
            }
        }
    }

    /** Send [packet] on the transport pinned to [kind], if attached.
     *  Returns true if sent, false if no such transport. Used for link-
     *  context outbound traffic (proof receipts, link DATA, LRPROOF
     *  responder, etc.). */
    private suspend fun sendOn(kind: TransportKind, packet: ByteArray): Boolean {
        val t = transports[kind]?.transport ?: return false
        runCatching { t.send(packet) }.onFailure {
            _events.tryEmit(EngineEvent.Log("tx failed on $kind: ${it.message}"))
            return false
        }
        return true
    }

    /** Send [packet] over the transport this link is pinned to. Pre-pin
     *  (initiator before LRPROOF arrives), falls back to broadcast — the
     *  responder is reachable via at least one of our transports and
     *  whichever one delivers the LRPROOF will pin the link. */
    private suspend fun sendForLink(linkIdHex: String, packet: ByteArray) {
        val kind = linkKinds[linkIdHex]
        if (kind != null) {
            sendOn(kind, packet)
        } else {
            broadcast(packet)
        }
    }

    /**
     * Per-destination affinity: which transport last delivered an
     * announce from this peer. Drives the [sendToDestination]
     * routing decision for outbound LXMF + LINKREQ traffic so we
     * don't waste airtime broadcasting the same packet on every
     * attached transport (the post-Columba mesh-amplification issue:
     * with TCP+BLE both up, every outbound was hitting LoRa twice —
     * once direct via the user's RNode, once via the wider TCP-attached
     * mesh re-emitting it on its own LoRa interfaces).
     *
     * Key: destination_hash hex (16 bytes / 32 chars). Value: the
     * [TransportKind] that delivered the most recent announce from
     * that destination. In-memory only — re-derived from inbound
     * announces every session. Persisting would mean stale affinities
     * surviving across restarts when peer mobility has changed.
     *
     * Update sites: [handleAnnounce] only. We deliberately do NOT
     * update on inbound DATA — that creates flap when a peer's
     * announce arrives via TCP but a transport-relayed reply comes
     * back via LoRa (or vice versa). Announces are the canonical
     * "I'm reachable via this path" signal.
     *
     * Read sites: [sendToDestination], called by per-peer outbound
     * paths (opportunistic LXMF send + retry, LXMF LINKREQ, Nomad
     * LINKREQ, propagation LINKREQ).
     */
    internal data class AffinityEntry(
        val kind: TransportKind,
        val hops: Int,
        val lastSeenMs: Long,
    )

    /** A pinned affinity older than this is treated as "peer probably
     *  moved" and any inbound announce on a different kind can adopt
     *  it regardless of hop count. 2× the ~5 min announce cadence. */
    private val AFFINITY_STALE_MS: Long = 10 * 60_000L

    private val destAffinity: MutableMap<String, AffinityEntry> = mutableMapOf()

    /**
     * When did we last see an announce for this destination on the SAME
     * (or shorter) hop count as the one we currently store? Drives the
     * [mergePathFromAnnounce] sticky-shortest-path rule. In-memory only
     * — re-derived after restart.
     *
     * Distinct from `StoredDestination.lastSeen`, which updates on
     * EVERY announce regardless of path. We need a separate clock so
     * "the good path went silent for >5 min" is detectable even while
     * worse-path re-emits keep arriving.
     */
    private val bestPathSeenMs: MutableMap<String, Long> = mutableMapOf()

    /**
     * Per-destination announce becomes a candidate to update affinity.
     * Hop-count aware: the wider TCP-attached rnsd mesh re-emits a
     * peer's own LoRa announce back to us at 5+ hops, which used to
     * flip the affinity to TCP every time (most-recent wins). LINKREQ
     * then routed via the long TCP path, exceeded the LRPROOF timeout,
     * and fell back to opportunistic delivery — observed by the user
     * 2026-05-08 with two phones on local LoRa + one on TCP.
     *
     * Update semantics (apply in order, first match wins):
     *   1. No prior pin / pinned-kind detached / pin stale (>10 min) → adopt
     *   2. Same kind → refresh lastSeen + hops
     *   3. Strictly fewer hops on a different kind → adopt (better path)
     *   4. Otherwise (equal/more hops on a different kind) → keep current
     *
     * Sticky on ties so transient hop-count fluctuation doesn't flap.
     * The 10-minute stale window is 2× the announce re-broadcast cadence
     * (CLAUDE.md "Periodic re-announce is mandatory ~5 min"), so a peer
     * that's actually moved becomes adoptable on its first announce on
     * the new path while still-reachable peers stay sticky.
     */
    private fun updateAffinityFromAnnounce(destHashHex: String, kind: TransportKind, hops: Int) {
        val now = nowMs()
        val current = destAffinity[destHashHex]
        val newEntry = AffinityEntry(kind, hops, now)
        when {
            current == null -> destAffinity[destHashHex] = newEntry
            current.kind == kind -> destAffinity[destHashHex] = newEntry
            transports[current.kind] == null -> destAffinity[destHashHex] = newEntry
            now - current.lastSeenMs > AFFINITY_STALE_MS -> destAffinity[destHashHex] = newEntry
            hops < current.hops -> destAffinity[destHashHex] = newEntry
            // Equal/more hops on a different kind → keep current (sticky)
        }
    }

    /**
     * Decide what hopCount + nextHop to write into [StoredDestination]
     * for an inbound announce. Sticky on shortest-path: the same trap
     * the affinity map had, but on the path-table side of the engine.
     *
     * Without this, every via-T re-emit of B's announce overwrites A's
     * direct-LoRa path entry with hopCount=2 + nextHop=T, and A's next
     * outbound to B undergoes §2.3 conversion to HEADER_2-via-T even
     * though the direct path is still live.
     *
     * Rules (apply in order, first match wins):
     *   1. No prior record → adopt
     *   2. New hops <= existing hops → adopt + refresh bestPathSeenMs
     *   3. bestPathSeenMs older than [PATH_STALE_MS] → adopt (peer
     *      moved or path went down — accept whatever new path we see)
     *   4. Otherwise (worse hops, recent good-path refresh) → keep
     *      existing path. lastSeen still updates on the StoredDestination
     *      because the announce IS valid evidence of liveness, just
     *      not better-route evidence.
     *
     * Returns (hopCount, nextHop) to write. The caller is expected to
     * preserve `existing.nextHop` when the new announce arrives via a
     * direct path (HEADER_1) but we keep the via-relay route — that's
     * encoded in rule 4 by returning existing.nextHop.
     */
    internal data class MergedPath(val hopCount: Int, val nextHop: ByteArray?)

    internal fun mergePathFromAnnounce(
        destHashHex: String,
        existingHopCount: Int?,
        existingNextHop: ByteArray?,
        newHopCount: Int,
        newNextHop: ByteArray?,
    ): MergedPath {
        val now = nowMs()
        if (existingHopCount == null) {
            bestPathSeenMs[destHashHex] = now
            return MergedPath(newHopCount, newNextHop)
        }
        val pathLastSeen = bestPathSeenMs[destHashHex] ?: 0L
        val isStale = (now - pathLastSeen) > PATH_STALE_MS
        return when {
            isStale -> {
                bestPathSeenMs[destHashHex] = now
                MergedPath(newHopCount, newNextHop)
            }
            newHopCount < existingHopCount -> {
                // Strictly better — fully replace, INCLUDING nextHop. A
                // shorter direct path makes any cached relay record
                // obsolete; preserving it would mean we keep routing
                // through a relay we no longer need.
                bestPathSeenMs[destHashHex] = now
                MergedPath(newHopCount, newNextHop)
            }
            newHopCount == existingHopCount -> {
                // Tied. Refresh the staleness clock. Preserve nextHop
                // when the new announce arrived as HEADER_1 (null
                // transportId) — we don't have evidence the relay went
                // away, just evidence we heard the dest again on the
                // same path.
                bestPathSeenMs[destHashHex] = now
                MergedPath(newHopCount, newNextHop ?: existingNextHop)
            }
            else -> {
                // Worse hops, recent good path → keep existing path.
                MergedPath(existingHopCount, existingNextHop)
            }
        }
    }

    /**
     * Send [packet] addressed to [destHashHex], routed to the transport
     * that last heard the destination's announce. Falls back to
     * [broadcast] when affinity is unknown or the affinity-kind is no
     * longer attached. Used by all per-peer outbound paths; for
     * advertise-everywhere traffic ([sendAnnounce]) and discovery
     * ([requestPath]) keep using [broadcast] directly.
     */
    private suspend fun sendToDestination(destHashHex: String, packet: ByteArray) {
        val pinned = destAffinity[destHashHex]?.kind
        if (pinned != null && transports[pinned] != null) {
            sendOn(pinned, packet)
            return
        }
        // Affinity-kind detached or never recorded — broadcast and let
        // dedup catch duplicates. Recording the affinity on the first
        // inbound announce after this send will narrow future sends.
        broadcast(packet)
    }

    /** True if at least one transport is attached. Used by the message
     *  send path in place of the old `transport != null` null-check. */
    fun hasAnyTransport(): Boolean = transports.isNotEmpty()

    /** Guards [drainQueuedOutgoing] against concurrent invocations — two
     *  attaches in quick succession (BLE coming up while TCP also recovers)
     *  would otherwise both pick up the same `"queued"` rows and double-
     *  send them. */
    private val drainMutex = Mutex()

    /**
     * Re-send every outgoing message that was parked in `"queued"` state
     * by [sendMessage] when no transport was attached. Called from [attach]
     * the moment a transport comes back online.
     *
     * Drain order is by timestamp ASC so the user's first queued tap goes
     * out first. Each row is flipped to `"pending"` before the actual send
     * so a parallel drain (or a follow-on attach for a different kind)
     * doesn't pick it up a second time. If a transport detaches mid-drain,
     * the loop breaks — the remaining rows stay `"queued"` for the next
     * reattach.
     */
    private fun drainQueuedOutgoing() {
        scope.launch {
            drainMutex.withLock {
                val queued = messageRepo.getAll()
                    .filter { it.direction == "outgoing" && it.state == "queued" }
                    .sortedBy { it.timestamp }
                if (queued.isEmpty()) return@withLock
                _events.tryEmit(EngineEvent.Log("drain: ${queued.size} queued message(s) — re-sending"))
                val identity = runCatching { ensureIdentity() }.getOrNull() ?: return@withLock
                val ourDest = ourDestHash()
                for (msg in queued) {
                    if (!hasAnyTransport()) {
                        _events.tryEmit(EngineEvent.Log(
                            "drain: transport detached again mid-drain — ${queued.size - queued.indexOf(msg)} message(s) still queued"
                        ))
                        break
                    }
                    val dest = destinationRepo.get(msg.contactHash)
                    if (dest == null || dest.publicKey.size != 64) {
                        _events.tryEmit(EngineEvent.Log(
                            "drain: msg #${msg.id} dropped — destination ${msg.contactHash} no longer messagable"
                        ))
                        messageRepo.updateState(msg.id, state = "failed", lastError = "destination not messagable on drain")
                        continue
                    }
                    messageRepo.updateState(msg.id, state = "pending")
                    runCatching {
                        sendExistingMessage(msg.id, dest, msg.content, msg.title, identity, ourDest)
                    }.onFailure {
                        _events.tryEmit(EngineEvent.Log(
                            "drain msg #${msg.id} failed: ${it::class.simpleName}: ${it.message}"
                        ))
                        messageRepo.updateState(msg.id, state = "failed", lastError = it.message ?: "drain error")
                    }
                }
            }
        }
    }

    /**
     * Test seam: directly set / read the affinity map without driving
     * a real announce through [handleAnnounce]. Internal so production
     * code can't accidentally use it as a substitute for the announce
     * path; tests use it to keep the routing-decision check small.
     */
    internal fun forTest_setDestAffinity(
        destHashHex: String,
        kind: TransportKind?,
        hops: Int = 1,
        lastSeenMs: Long? = null,
    ) {
        if (kind == null) destAffinity.remove(destHashHex)
        else destAffinity[destHashHex] = AffinityEntry(kind, hops, lastSeenMs ?: nowMs())
    }
    internal fun forTest_getDestAffinity(destHashHex: String): TransportKind? =
        destAffinity[destHashHex]?.kind
    internal fun forTest_getDestAffinityEntry(destHashHex: String): AffinityEntry? =
        destAffinity[destHashHex]
    internal fun forTest_updateAffinityFromAnnounce(destHashHex: String, kind: TransportKind, hops: Int) =
        updateAffinityFromAnnounce(destHashHex, kind, hops)
    internal fun forTest_mergePathFromAnnounce(
        destHashHex: String,
        existingHopCount: Int?,
        existingNextHop: ByteArray?,
        newHopCount: Int,
        newNextHop: ByteArray?,
    ): MergedPath = mergePathFromAnnounce(
        destHashHex, existingHopCount, existingNextHop, newHopCount, newNextHop,
    )
    internal fun forTest_setBestPathSeenMs(destHashHex: String, ms: Long) {
        bestPathSeenMs[destHashHex] = ms
    }

    /** Test seam: drive the affinity-routing decision in isolation
     *  without standing up sendMessage's full retry-loop machinery
     *  (which generates uncompleted-coroutine noise under
     *  StandardTestDispatcher). Exercises [sendToDestination]
     *  directly. */
    internal suspend fun forTest_sendToDestination(destHashHex: String, packet: ByteArray) {
        sendToDestination(destHashHex, packet)
    }

    fun attach(transport: Transport, kind: TransportKind) {
        // Replace just THIS kind's slot if already present; leave other
        // kinds running. This is what makes simultaneous BLE+TCP work.
        val previous = transports[kind]
        if (previous != null) detachOne(kind, alreadyHeld = previous)

        // Reset announce throttle when this kind is *new* (first attach
        // for it), so a fresh rnsd has a return path immediately. Don't
        // reset when the user toggles a different kind — the existing
        // attached transports already have current paths.
        if (previous == null && transports.isEmpty()) {
            lastAnnounceMs = 0L
        } else if (previous == null) {
            // First time we're seeing this kind, but we already have
            // others up. Same reasoning — re-announce so the new fabric
            // learns the path back to us.
            lastAnnounceMs = 0L
        }

        emitConnection(transport.state.value, kind)

        // Lazy-start the pump and state-mirror so we can assign
        // `transports[kind]` BEFORE the pump can dispatch its first
        // packet. Without this there's a race: handleIncoming gets
        // called for a freshly arrived packet, hits sendOn(kind, ...)
        // for an LRPROOF responder reply, and finds no transport
        // registered for `kind` yet because the launch{} returned a Job
        // before the assignment ran.
        val stateMirror = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            transport.state.collect { st -> emitConnection(st, kind) }
        }
        val pump = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            transport.incoming.collect { incoming ->
                runCatching { handleIncoming(incoming.packet, incoming.rssi, kind) }
                    .onFailure { _events.tryEmit(EngineEvent.Log("rx error: ${it.message}")) }
            }
        }
        transports[kind] = Attached(transport, pump, stateMirror)
        stateMirror.start()
        pump.start()

        // Singleton reannounce timer. One global cadence regardless of
        // how many transports are attached — broadcasts to all of them
        // when it fires (see sendAnnounce → broadcast).
        if (reannounceJob == null) {
            reannounceJob = scope.launch {
                while (true) {
                    runCatching { sendAnnounceIfDue() }.onFailure {
                        _events.tryEmit(EngineEvent.Log("announce failed: ${it.message}"))
                    }
                    delay(announceMinIntervalMs)
                }
            }
        }

        // Drain any messages parked in "queued" state while no transport
        // was attached — see sendMessage's hasAnyTransport() branch.
        drainQueuedOutgoing()
    }

    /** Detach a specific transport kind. When [kind] is null, detach
     *  every kind (the wholesale teardown the disconnect button used
     *  to do). */
    fun detach(kind: TransportKind? = null) {
        if (kind == null) {
            val keys = transports.keys.toList()
            for (k in keys) detachOne(k, alreadyHeld = null)
            return
        }
        detachOne(kind, alreadyHeld = null)
    }

    private fun detachOne(kind: TransportKind, alreadyHeld: Attached?) {
        val attached = alreadyHeld ?: transports.remove(kind) ?: run {
            // Wasn't present — emit Disconnected for tidiness so the UI
            // clears any "Connecting" entry that lingered.
            removeConnection(kind)
            return
        }
        if (alreadyHeld != null) transports.remove(kind)
        attached.pumpJob.cancel()
        attached.stateMirrorJob.cancel()

        // Drop links pinned to this kind. Their underlying Reticulum
        // session state can't survive a transport loss — the responder
        // won't have us in its routing tables anymore once the path
        // ages out, and we can't keep keepalives flowing. Force a fresh
        // handshake the next time the user opens that destination.
        val droppedLinkIds = linkKinds.filterValues { it == kind }.keys.toList()
        for (id in droppedLinkIds) linkKinds.remove(id)
        if (droppedLinkIds.isNotEmpty()) {
            scope.launch {
                sessionsLock.withLock {
                    droppedLinkIds.forEach { activeSessions.remove(it)?.dispose() }
                    val nomadKeys = nomadLinks.entries
                        .filter { it.value.linkIdHex in droppedLinkIds }
                        .map { it.key }
                    nomadKeys.forEach { nomadLinks.remove(it) }
                }
            }
        }

        removeConnection(kind)

        // Stop the singleton reannounce timer if no transports remain.
        if (transports.isEmpty()) {
            reannounceJob?.cancel()
            reannounceJob = null
            // No transports left — emit a Disconnected primary so widget
            // code that reads `connection` (not `connections`) sees it.
            _connection.value = ConnectionState(TransportState.Disconnected, kind = null, nowMs())
        }
    }

    suspend fun sendAnnounce(asPathResponse: Boolean = false) {
        val id = ensureIdentity()
        // Rotate the ratchet on a slow schedule (default 30 min, per
        // upstream RNS RATCHET_INTERVAL). Two competing requirements:
        //  - rotate often enough that transit nodes that dedupe on
        //    (destHash, ratchet) keep propagating our re-announces
        //    (the v0.1.33 fix that broke pre-fix invisible)
        //  - DON'T rotate so often that peers' in-flight DATA arrives
        //    encrypted to a ratchet pub we've already discarded
        //    (mobile-to-mobile silent failure observed 2026-05-03)
        //
        // The path-request handler in v0.1.35 calls sendAnnounce on
        // every inbound path?, which can fire many times per minute.
        // Time-gating rotation here decouples announce frequency from
        // ratchet rotation cadence — the same ratchet pub may appear
        // on many announces within a 30-min window.
        if (shouldRotateRatchet(nowMs(), lastRatchetRotationMs)) {
            id.rotateRatchet()
            identityRepo.save(StoredIdentity(
                encPrivKey = id.encPrivKey!!,
                sigPrivKey = id.sigPrivKey!!,
                ratchetPrivKey = id.ratchetPrivKey,
            ))
            lastRatchetRotationMs = nowMs()
            _events.tryEmit(EngineEvent.Log("ratchet rotated"))
        }
        val name = displayNameProvider().ifBlank { "Reticulum Mobile" }
        val (destHash, payload, hasRatchet) = io.github.thatsfguy.reticulum.announce.buildAnnounce(
            identity = id,
            crypto = crypto,
            appName = "lxmf.delivery",
            appData = io.github.thatsfguy.reticulum.codec.MessagePack.encode(
                listOf(name.encodeToByteArray(), 0)
            ),
            ratchetPub = id.ratchetPubKey,
            nowSeconds = nowMs() / 1000L,
        )
        // PATH_RESPONSE context (0x0B) when this announce is a reply
        // to a path? request, NONE (0x00) otherwise. Receivers
        // (RNS/Transport.py:1632-1639) bypass ingress rate-limiting
        // for path-response context — without it, our re-announce in
        // response to a flood of path? requests gets rate-limited at
        // transit nodes and the requester never gets the path it
        // needs to advance from path? to actual DATA delivery.
        // See reticulum-specifications/flows/path-discovery.md §6.
        val ctx = if (asPathResponse) {
            io.github.thatsfguy.reticulum.protocol.CTX_PATH_RESPONSE
        } else CTX_NONE
        val packet = buildPacket(
            headerType = HEADER_1,
            contextFlag = if (hasRatchet) 1 else 0,
            transportType = TRANSPORT_BROADCAST,
            destType = DEST_SINGLE,
            packetType = PACKET_ANNOUNCE,
            destHash = destHash,
            context = ctx,
            payload = payload,
        )
        broadcast(packet)
        lastAnnounceMs = nowMs()
        val tag = if (asPathResponse) " [path-response]" else ""
        val kindsTag = transports.keys.joinToString(",") { it.name }.ifEmpty { "no-transport" }
        _events.tryEmit(EngineEvent.Log("announce sent (${destHash.toHex()})$tag → [$kindsTag]"))
    }

    /** Throttled wrapper around [sendAnnounce]. Skips the send if our
     *  last announce went out less than [announceMinIntervalMs] ago.
     *  Used by the on-connect path and the periodic re-announce loop;
     *  explicit user actions (Settings → Send announce, identity
     *  reset, display-name change) call [sendAnnounce] directly. */
    suspend fun sendAnnounceIfDue() {
        val now = nowMs()
        val sinceLast = now - lastAnnounceMs
        if (lastAnnounceMs != 0L && sinceLast < announceMinIntervalMs) {
            val ageS = sinceLast / 1000
            val gateS = announceMinIntervalMs / 1000
            _events.tryEmit(EngineEvent.Log("announce throttled (last sent ${ageS}s ago, gate ${gateS}s)"))
            return
        }
        sendAnnounce()
    }

    /**
     * Send an LXMF message to a known messagable destination.
     *
     * v0.1.89: link-delivery primary, opportunistic fallback. We open
     * (or reuse) a Reticulum Link to the recipient, send the LXMF as
     * link-delivered DATA, and treat the responder's per-packet PROOF
     * (§6.5) as the delivery confirmation. If the link doesn't
     * establish (LRPROOF timeout / rejected) or the per-packet PROOF
     * doesn't arrive, we fall back to the opportunistic path the
     * pre-v0.1.89 code took: Token-encrypt to the recipient's pub key,
     * broadcast as CTX_NONE DATA, run the existing retry loop.
     *
     * Sideband + most modern clients deliver via Link. Strict-mode
     * receivers (and Sideband configurations that gate inbound on
     * "must arrive over a link") REQUIRE this path; the opportunistic
     * fallback is for older / minimal peers that don't run a link
     * responder.
     */
    // @Throws makes IllegalStateException (from the dest-not-in-repo
    // error()) cross the Swift bridge as a catchable NSError. Without
    // this, Kotlin/Native treats it as an unhandled exception and
    // terminates the process — the iOS-only "send-to-manual-contact"
    // crash the v1.0.22 tester reported was this exact bridge gap.
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    /**
     * Send an LXMF message to [destinationHash].
     *
     * When [imageBytes] is non-null, the bytes ride alongside [content] as
     * LXMF `FIELD_IMAGE` (integer key 6) — the wire key Sideband and Columba
     * both use, confirmed against torlando-tech/columba's MessagingScreen.kt.
     * Delivery uses a Reticulum Resource (§10.2) over an established Link
     * since 10–20 KB JPEG bytes don't fit in a single packet's MDU.
     *
     * On Resource failure (link timeout / PRF timeout / link establish fail),
     * the existing opportunistic-fallback path kicks in but drops the image —
     * text content still reaches the receiver. Per [todo.md] image-attachment
     * plan: "image silently dropped, content preserved" is the intended UX
     * for partial failure.
     *
     * Phase 1 of the image-attachment work: this lays the wire-side plumbing
     * but does NOT yet persist [imageBytes] on the sender's `StoredMessage`
     * row (Phase 3) — the sender's own conversation bubble will show only
     * the text until storage migration lands. Receiver-side `fields[6]`
     * extraction is also Phase 3.
     */
    suspend fun sendMessage(
        destinationHash: String,
        content: String,
        title: String = "",
        imageBytes: ByteArray? = null,
        /** When non-null, the message is a reply to the message
         *  whose canonical LXMF message_id is [replyToMessageId].
         *  The recipient's UI renders a small quote-preview at the
         *  top of this bubble by looking up that message in its
         *  local DB. Wire shape: LXMF field 16 with sub-key
         *  `"reply_to"` (Columba / Sideband convention). Audit
         *  reference: 2026-05-13 reactions + replies feature. */
        replyToMessageId: String? = null,
    ): Long {
        val dest = destinationRepo.get(destinationHash) ?: error("Unknown destination $destinationHash")

        val id = ensureIdentity()
        val ourDest = ourDestHash()

        // Save the message up front so it appears in the conversation
        // immediately — the user shouldn't wait for link establishment
        // (~1-30s depending on hops) to see what they typed. State is
        // updated in place by whichever delivery path completes. The
        // imageBytes are persisted here too so the sender's own bubble
        // can render the attached image without a round-trip — the
        // opportunistic fallback at the bottom of [sendExistingMessage]
        // is still text-only, but the local row keeps the bytes
        // regardless (the recipient just won't see them if Resource
        // delivery failed and the message degraded to opportunistic).
        val msgId = messageRepo.save(StoredMessage(
            contactHash = destinationHash,
            direction = "outgoing",
            content = content,
            title = title,
            timestamp = nowMs(),
            state = "pending",
            attempts = 0,
            lastAttempt = nowMs(),
            imageBytes = imageBytes,
            replyToMessageId = replyToMessageId,
        ))

        // Manual-stub contacts (added via addManualDestination, before any
        // announce has filled in their keys) have publicKey.size == 0 and
        // identityHash == "". Two evolutions of this branch:
        //
        //   v1.0.23 (b40d99e era): converted from require()-throws — which
        //     crashed iOS via the @Throws-less suspend bridge — to a
        //     state="failed" soft-fail. No crash, but tester report
        //     2026-05-11 showed the message just got a red X and died.
        //   v1.0.25 (this): convert further from "failed" → "queued" AND
        //     fire an active path? request so the destination's announce
        //     gets pulled from the mesh rather than waited-for. When the
        //     announce arrives, handleAnnounce → drainQueuedOutgoing
        //     re-runs sendExistingMessage with the now-populated public
        //     key. The drain function already re-checks publicKey.size,
        //     so a still-unknown destination at drain time gets failed
        //     there — this branch is just the "hold + actively probe"
        //     half of the round-trip.
        //
        // UX consequence: the chat bubble now shows 🕒 (the existing
        // "queued" glyph from yesterday's no-transport drain work)
        // instead of ✗, and the message sends automatically once the
        // contact's announce echoes back through the mesh.
        if (dest.publicKey.size != 64 || dest.identityHash.isEmpty()) {
            val why = if (dest.publicKey.size != 64)
                "no public key yet — waiting for an announce"
            else
                "no identity hash yet — waiting for an announce"
            _events.tryEmit(EngineEvent.Log("msg #$msgId: 🕒 $why — requesting path"))
            messageRepo.updateState(
                msgId,
                state = "queued",
                lastAttempt = nowMs(),
                lastError = why,
            )
            // Active pull: ask the mesh to echo the destination's
            // announce back through us. No-op when no transport is
            // attached (requestPath bails internally).
            runCatching { requestPath(dest.destHash) }
            return msgId
        }

        if (!hasAnyTransport()) {
            // Don't drop the message — the BLE / BT-Classic / TCP supervisor
            // (ReticulumService.startBle / startBtClassic / startTcp) is in
            // an exponential-backoff window (1s → 60s). Park the message
            // as "queued"; [drainQueuedOutgoing] picks it up the moment a
            // transport successfully re-attaches via [attach]. Previously
            // we marked these "failed" and the user had to retype the
            // content to retry, which felt broken when a brief RNode drop
            // ate their /users tap.
            _events.tryEmit(EngineEvent.Log("msg #$msgId: queued — no transport, will retry on reattach"))
            messageRepo.updateState(msgId, state = "queued", lastAttempt = nowMs(), lastError = "no transport at send time")
            return msgId
        }

        return sendExistingMessage(msgId, dest, content, title, id, ourDest, imageBytes)
    }

    /**
     * Send a tap-back reaction targeting [targetMessageId] (the
     * canonical LXMF message_id hex of the message being reacted to).
     *
     * Wire shape (Columba / MeshChatX app-extension on LXMF field 16):
     * a separate empty-body LXMF message with
     * `fields[16] = {"reaction_to": <hex>, "emoji": "👍", "sender":
     * <our_identity_hash_hex>}`. `sender` is our 16-byte RNS identity
     * hash (`id.hash`), NOT the lxmf.delivery destination hash — Columba
     * and MeshChatX both emit the identity hash, so the value must match
     * to aggregate correctly across clients. Receivers aggregate this into
     * their copy of the target row's `reactionsJson` and do NOT render
     * the reaction itself as a bubble. The sender does the same merge
     * locally for immediate visual feedback so the user sees their
     * own reaction without waiting for a round-trip.
     *
     * Returns the row id of the shadow StoredMessage row that
     * carries the reaction's delivery state. The conversation view
     * filters `direction = "outgoing-reaction"` rows out of the
     * bubble list — they exist only for the delivery state machine.
     * Audit reference: 2026-05-13 reactions + replies feature.
     */
    suspend fun sendReaction(
        destinationHash: String,
        targetMessageId: String,
        emoji: String,
    ): Long {
        val id = ensureIdentity()
        val ourDest = ourDestHash()

        // Apply locally first so the user's own reaction appears
        // immediately on their UI. Same dedup as the inbound path
        // (idempotent on repeated taps of the same emoji).
        messageRepo.applyReaction(targetMessageId, emoji, id.hash!!.toHex())

        // v1.1.38 — relay-aware routing. If the target message arrived
        // via a relay (fwdsvc fanout case, tagged with arrivedViaDest at
        // receive time by [handleLinkLxmf]), route the reaction through
        // the relay's destination so it reaches the whole group instead
        // of egressing direct to the original sender (who is one of
        // many group members, not the message router). Falls back to
        // the caller-supplied destinationHash when the target row has
        // no relay tag — that's the 1:1 chat case where the message's
        // source IS the conversation peer. Audit reference: 2026-05-14
        // routing fix (fwdsvc agent verified reactions never reached
        // the relay because the LXMF egressed direct to BlueP).
        val targetRow = messageRepo.getByMessageId(targetMessageId)
        val effectiveDestHash = targetRow?.arrivedViaDest ?: destinationHash
        val dest = destinationRepo.get(effectiveDestHash)
            ?: error("Unknown destination $effectiveDestHash")
        if (effectiveDestHash != destinationHash) {
            _events.tryEmit(EngineEvent.Log(
                "reaction → relay $effectiveDestHash (target row was tagged arrivedViaDest; original peer was $destinationHash)"
            ))
        }

        // Shadow row tracks delivery state for the reaction-send.
        // direction="outgoing-reaction" so the conversation list
        // filters it out of the bubble feed. content is empty.
        val msgId = messageRepo.save(StoredMessage(
            contactHash = destinationHash,
            direction = "outgoing-reaction",
            content = "",
            title = "",
            timestamp = nowMs(),
            state = "pending",
            attempts = 0,
            lastAttempt = nowMs(),
        ))

        if (dest.publicKey.size != 64 || dest.identityHash.isEmpty()) {
            messageRepo.updateState(msgId, state = "failed",
                lastError = "no public key yet — can't send reaction")
            return msgId
        }
        if (!hasAnyTransport()) {
            messageRepo.updateState(msgId, state = "queued",
                lastError = "no transport at reaction time")
            return msgId
        }

        // Wire the reaction body via the same delivery machinery as
        // a normal message. Empty content + reactions-shaped fields.
        // Reuses sendExistingMessage's pack + retry path; the field
        // is added by extending the fields map (no other code change
        // needed since fields are already a parameter at the pack
        // sites).
        val reactionFields: Map<Any?, Any?> = mapOf(
            16 to mapOf(
                "reaction_to" to targetMessageId,
                "emoji" to emoji,
                "sender" to id.hash!!.toHex(),
            ),
        )
        // Re-using sendExistingMessage requires we pass fields
        // through, which we don't today — content / title are
        // the only payload knobs. For reactions we ride the
        // link-delivery path directly via tryDeliverOverLink
        // since reactions are tiny (a few hundred bytes) and
        // never benefit from Resource framing.
        val ok = tryDeliverOverLink(
            msgId = msgId,
            dest = dest,
            content = "",
            title = "",
            id = id,
            ourDest = ourDest,
            imageBytes = null,
            extraFields = reactionFields,
        )
        if (!ok) {
            // Opportunistic fallback isn't ideal for reactions (no
            // Resource needed anyway, but no link means no delivery
            // proof either). Mark as sent locally; user's own
            // reaction is already applied to the target row.
            messageRepo.updateState(msgId, state = "failed",
                lastError = "link delivery failed — reaction not delivered")
        }
        return msgId
    }

    /**
     * Send body for [sendMessage] and [drainQueuedOutgoing] — operates on
     * an already-persisted [StoredMessage] row (no new save). Runs the
     * announce-refresh → link-delivery → opportunistic-fallback → retry
     * loop. Returns the same [msgId] for the caller's convenience.
     *
     * [imageBytes] is the optional 10–20 KB image payload to deliver as
     * LXMF FIELD_IMAGE via a Resource on the link path. The opportunistic
     * fallback ignores it (MTU doesn't fit) — see [sendMessage] kdoc.
     */
    private suspend fun sendExistingMessage(
        msgId: Long,
        dest: io.github.thatsfguy.reticulum.store.StoredDestination,
        content: String,
        title: String,
        id: Identity,
        ourDest: ByteArray,
        imageBytes: ByteArray? = null,
    ): Long {
        // v1.1.38 — relay-aware routing for replies. If this row is a
        // reply (replyToMessageId set) and the target arrived via a
        // relay (arrivedViaDest set at receive time), egress through
        // the relay's destination so the reply reaches the whole
        // group via fanout instead of going direct to the original
        // sender. Same logic as sendReaction. Falls back to the
        // passed-in `dest` for normal (non-reply) sends and for
        // replies whose target wasn't relayed (1:1 chats). Audit
        // reference: 2026-05-14 routing fix.
        val incomingRow = messageRepo.getById(msgId)
        val replyTargetRow = incomingRow?.replyToMessageId
            ?.let { messageRepo.getByMessageId(it) }
        val relayDestHash = replyTargetRow?.arrivedViaDest
        val effectiveDest = if (relayDestHash != null && relayDestHash != dest.hash) {
            val relayRow = destinationRepo.get(relayDestHash)
            if (relayRow != null) {
                _events.tryEmit(EngineEvent.Log(
                    "msg #$msgId: reply → relay $relayDestHash (target tagged arrivedViaDest; original peer was ${dest.hash})"
                ))
                relayRow
            } else {
                _events.tryEmit(EngineEvent.Log(
                    "msg #$msgId: reply target tagged arrivedViaDest=$relayDestHash but relay destination not in our table — falling back to direct ${dest.hash}"
                ))
                dest
            }
        } else dest
        @Suppress("NAME_SHADOWING")
        val dest = effectiveDest
        val destinationHash = dest.hash

        // Tap-Send timestamp from the row we saved up front in
        // sendMessage. Use this for the LXMF wire timestamp instead
        // of nowMs() at pack time so the recipient's conversation
        // view sorts messages by when the user actually TAPPED SEND,
        // not by how long link establishment + retry took. Without
        // this, two messages composed seconds apart can land out of
        // order on the recipient if the second had a faster link
        // (e.g. cached link reuse) than the first (link establishment
        // from scratch + retries). Falls back to nowMs() if the row
        // was deleted between save() and here — defensive only;
        // shouldn't happen in practice.
        val savedRow = messageRepo.getById(msgId)
        val tapSendMs = savedRow?.timestamp ?: nowMs()
        val tapSendSeconds = tapSendMs / 1000.0
        // Reply-to target, populated by sendMessage when the user
        // composed in "replying to" mode. Both the opportunistic
        // and link send paths emit `fields[16] = {"reply_to":...}`
        // so a Sideband or Columba recipient renders the quote
        // preview at the top of the bubble. Audit reference:
        // 2026-05-13 reactions + replies feature.
        val replyToMessageId = savedRow?.replyToMessageId
        // MeshChatX wire shape on outbound — `fields[0x30] = <raw
        // 32-byte hash>`. Saves ~50 wire bytes per reply vs
        // Columba's `fields[16] = {"reply_to": "<64-char-hex>"}`
        // msgpack-encoded map (the hex string alone is 64 bytes;
        // map+key overhead pushes it past 80B). Inbound parser
        // (extractField16) accepts both Columba and MeshChatX
        // shapes so this remains interop-friendly with Columba
        // peers — they just see one fewer reply preview on their
        // end if they don't recognise 0x30, which matches how
        // unknown LXMF fields degrade across implementations.
        // Audit reference: 2026-05-14 MeshChatX dual-format added.
        val replyFields: Map<Any?, Any?> = if (replyToMessageId != null) {
            runCatching {
                val hashBytes = replyToMessageId.hexBytesOrThrow(
                    "replyToMessageId", expectedLen = 32
                )
                mapOf<Any?, Any?>(0x30 to hashBytes)
            }.getOrElse {
                // Defensive: should never happen because the column
                // is populated by our own engine code with a known-
                // 32-byte hex, but a malformed manual row in the DB
                // shouldn't kill the send.
                _events.tryEmit(EngineEvent.Log(
                    "reply_to hex malformed, sending without reply field: ${it.message}"
                ))
                emptyMap()
            }
        } else emptyMap()

        // Refresh fwdsvc-shaped peers' view of our destination BEFORE we
        // try to deliver. The recipient may need to send its reply via a
        // fresh initiator-side link (its `ActiveTo(our_hash)` won't match
        // the responder-side link we established for the outbound), which
        // requires its `Recall(our_hash)` to return our current public key
        // and TransportID. Without a recent re-announce, an aging
        // delivery-side cache silently drops the outbound and our
        // initiator-side link delivery proof comes back without a reply
        // ever following.
        //
        // Guarded so we don't spam the network on rapid bursts — limited
        // to one re-announce per 60s. The default reannounce loop runs
        // every 15 min which is too coarse for this race.
        val sinceLastAnnounce = nowMs() - lastAnnounceMs
        if (lastAnnounceMs == 0L || sinceLastAnnounce > REANNOUNCE_BEFORE_SEND_MIN_MS) {
            runCatching { sendAnnounce() }.onFailure {
                _events.tryEmit(EngineEvent.Log(
                    "msg #$msgId: pre-send re-announce failed: ${it.message} (continuing)"
                ))
            }
        }

        // Path 1 — Link delivery. Returns true if we got a per-packet
        // proof (or a CTX_RESOURCE_PRF when imageBytes != null), false if
        // establishment or the proof timed out.
        val deliveredViaLink = runCatching {
            // Pass replyFields through so the LXMF body carries
            // `field 16 = {"reply_to": ...}` when this row is a
            // reply. Merged with the image field inside
            // tryDeliverOverLink's existing fields-merge logic.
            tryDeliverOverLink(
                msgId = msgId,
                dest = dest,
                content = content,
                title = title,
                id = id,
                ourDest = ourDest,
                imageBytes = imageBytes,
                extraFields = replyFields,
            )
        }.onFailure {
            _events.tryEmit(EngineEvent.Log(
                "msg #$msgId: link delivery threw (${it::class.simpleName}: ${it.message}) — falling back to opportunistic"
            ))
        }.getOrDefault(false)

        if (deliveredViaLink) return msgId

        // Image attached but the link path failed → mark the row with
        // the IMAGE_DROPPED_MARKER prefix in lastError BEFORE the
        // opportunistic send fires. Opportunistic strips imageBytes
        // (no room in a single 360 B packet), so the recipient gets
        // text only. When the PROOF eventually lands and the state
        // flips to "delivered", lastError is preserved (partial-update
        // semantics — passing null leaves the column unchanged), so
        // the bubble renderer can spot the marker and surface a "⚠
        // image not delivered" indicator next to the ✓.
        //
        // Without this, the user sees ~90 s of spinner (post the
        // shorter image-mode retry budget in tryDeliverOverLink) and
        // then a clean ✓ — falsely implying the image arrived.
        if (imageBytes != null) {
            _events.tryEmit(EngineEvent.Log(
                "msg #$msgId: ⚠ image (${imageBytes.size} B) dropped — link failed, opportunistic fallback is text-only"
            ))
            messageRepo.updateState(
                msgId,
                lastError = IMAGE_DROPPED_MARKER + "link establishment failed; only the text content was delivered",
            )
        }

        // Path 2 — Opportunistic fallback. Original pre-v0.1.89 flow:
        // pack + Token-encrypt to recipient's pub key, broadcast as
        // CTX_NONE DATA, run the existing MSG_MAX_ATTEMPTS retry loop.
        // SPEC §5.7 stamp — compute PoW if the recipient's announce
        // advertised a stamp_cost. Sideband 1.x defaults to dropping
        // unstamped inbound at the application layer. extractStampCost
        // returns null when no stamp is required, or 1..254 when one
        // is. The PoW happens on the engine's own dispatcher (which is
        // already a coroutine context — sendMessage is a suspend fun);
        // for typical cost ≤ 12 this completes in well under a second,
        // higher costs degrade gracefully and refuse via
        // LxmfStamp.MAX_TARGET_COST.
        val stampCost = runCatching {
            destAppDataBytes(dest)?.let { io.github.thatsfguy.reticulum.announce.extractStampCost(it) }
        }.getOrNull()
        val plaintext = packLxmfWithOptionalStamp(
            sourceIdentity   = id,
            destHash         = dest.destHash,
            sourceHash       = ourDest,
            title            = title,
            content          = content,
            // Tap-Send timestamp from the local StoredMessage row so
            // the recipient's ORDER BY timestamp sort matches the
            // order the user actually pressed Send in. See the
            // top of sendExistingMessage for the full rationale.
            timestampSeconds = tapSendSeconds,
            // replyFields carries the LXMF field 16 reply_to wrapper
            // when this is a reply. Empty for non-reply opportunistic
            // sends — keeps the wire form identical to pre-1.1.34.
            fields           = replyFields,
            stampCost        = stampCost,
            msgId            = msgId,
        )
        // Persist the canonical LXMF message_id on this row so
        // future inbound reactions / replies that target it can
        // find it via getByMessageId. Same SHA-256 the receiver
        // will compute on its end (deterministic from the 4-element
        // packed payload), so reactions land on the right row.
        val packed4Opp = io.github.thatsfguy.reticulum.codec.MessagePack.encode(
            listOf(
                tapSendSeconds,
                title.encodeToByteArray(),
                content.encodeToByteArray(),
                replyFields,
            )
        )
        val messageIdHexOpp = io.github.thatsfguy.reticulum.lxmf.LxmfStamp
            .computeMessageId(dest.destHash, ourDest, packed4Opp, crypto).toHex()
        messageRepo.setMessageId(msgId, messageIdHexOpp)
        val recipientEncPub = dest.ratchetPub ?: dest.publicKey.copyOfRange(0, 32)
        val recipientIdHash = dest.identityHash.hexBytesOrThrow("identityHash", expectedLen = 16)
        val keyKind = if (dest.ratchetPub != null) "ratchet" else "long-term"
        val seenAgeMin = (nowMs() - dest.lastSeen) / 60_000
        _events.tryEmit(EngineEvent.Log(
            "→ encrypting to ${destinationHash.take(16)}… via $keyKind key (peer last seen ${seenAgeMin}m ago, ${dest.hopCount} hops)"
        ))
        val token = tokenCrypto.encrypt(plaintext, recipientEncPub, recipientIdHash)
        // §2.3 originator HEADER_1→HEADER_2 conversion. When the path
        // to the destination is via a transit relay (hopCount > 1) and
        // we know that relay's transport_id, we MUST emit HEADER_2 with
        // the relay as transport_id. Upstream RNS/Transport.py:1497
        // forwards inbound DATA only when transport_id != None; HEADER_1
        // DATA addressed to a non-locally-attached destination is added
        // to the dedup hashlist and silently dropped. Verified 2026-05-03
        // by reading RNS 1.2.0 source after offline replay-decrypt
        // confirmed our outbound crypto was correct end-to-end.
        val useHeader2 = dest.hopCount > 1 && dest.nextHop != null
        val packet = buildPacket(
            headerType = if (useHeader2) HEADER_2 else HEADER_1,
            transportType = if (useHeader2) TRANSPORT_TRANSPORT else TRANSPORT_BROADCAST,
            destType = DEST_SINGLE,
            packetType = PACKET_DATA,
            destHash = dest.destHash,
            transportId = if (useHeader2) dest.nextHop else null,
            context = CTX_NONE,
            payload = token,
        )
        if (useHeader2) {
            _events.tryEmit(EngineEvent.Log(
                "  → using HEADER_2 (hops=${dest.hopCount}, transport_id=${dest.nextHop?.toHex()})"
            ))
        }
        // Pre-compute the FULL packet hash and store it as 64 hex
        // chars so the PROOF handler can both (a) match the inbound
        // proof's truncated 16-byte dest_hash via prefix lookup, AND
        // (b) verify the Ed25519 signature in the proof body against
        // the full 32-byte hash (§6.5.5). Pre-v1.1.22 we stored only
        // the 32-char truncated form, which let the matching path
        // work but left the security verification impossible — the
        // full hash needed for Ed25519_verify wasn't reconstructible
        // from the stored truncation. Forged proofs from any on-path
        // observer could flip our state to "delivered" silently.
        //
        // The database queries `WHERE packetHash LIKE :hash || '%'`
        // so both 32-char (legacy pre-v1.1.22 rows) and 64-char (new)
        // forms continue to match. Legacy rows skip sig verification;
        // the row updates to a verified state organically as new
        // sends replace the old.
        val outgoingFullHashHex = runCatching {
            val self = parsePacket(packet) ?: error("self-parse failed")
            computePacketFullHash(self, crypto).toHex()
        }.getOrNull()

        outgoingFullHashHex?.let {
            messageRepo.updateState(msgId, packetHash = it)
        }
        val outgoingTruncHashHex = outgoingFullHashHex?.take(32)

        // Sideband-style progressive states so the user can see WHERE a
        // send is in flight. The path-request step matters: without it,
        // opportunistic DATA sent to a destination whose path has aged
        // out on the local rnsd silently fails — the rnsd has nowhere
        // to forward it.
        _events.tryEmit(EngineEvent.Log("msg #$msgId: requesting path to $destinationHash"))
        primePath(
            destHash = dest.destHash,
            requestPath = { hash -> requestPath(hash) },
            delayMs = { ms -> delay(ms) },
            onPathFailure = { _events.tryEmit(EngineEvent.Log("msg #$msgId: path? failed: ${it.message}")) },
        )

        _events.tryEmit(EngineEvent.Log("msg #$msgId: sending opportunistic (${packet.size}B)"))
        runCatching { sendToDestination(dest.hash, packet) }.onFailure {
            _events.tryEmit(EngineEvent.Log("msg #$msgId: ✗ send threw: ${it::class.simpleName}: ${it.message}"))
            messageRepo.updateState(msgId, state = "failed", lastAttempt = nowMs(), lastError = it.message ?: "send error")
            return msgId
        }
        messageRepo.updateState(msgId, state = "sent", attempts = 1, lastAttempt = nowMs())
        outgoingTruncHashHex?.let { _events.tryEmit(EngineEvent.Log("→ data $it (msg #$msgId)")) }
        _events.tryEmit(EngineEvent.Log("msg #$msgId: awaiting proof (up to ${MSG_MAX_ATTEMPTS} attempts)"))

        scope.launch {
            try {
                for (attempt in 2..MSG_MAX_ATTEMPTS) {
                    delay(MSG_BACKOFF_MS[attempt - 2])
                    val current = messageRepo.getById(msgId) ?: return@launch
                    if (current.state == "delivered") {
                        _events.tryEmit(EngineEvent.Log("msg #$msgId: ✓ delivered"))
                        return@launch
                    }
                    if (current.state == "failed") return@launch
                    _events.tryEmit(EngineEvent.Log("msg #$msgId: retry $attempt/$MSG_MAX_ATTEMPTS (no proof yet)"))
                    if (!hasAnyTransport()) {
                        // ALL transports detached between the initial send
                        // and this retry. Surface explicitly — without
                        // this the broadcast call is a silent no-op and
                        // the message would stay on "sent" forever
                        // instead of flipping to failed.
                        _events.tryEmit(EngineEvent.Log("msg #$msgId: ✗ no transport at retry $attempt (all transports detached)"))
                        messageRepo.updateState(
                            msgId,
                            state = "failed",
                            lastAttempt = nowMs(),
                            lastError = "no transport at retry $attempt",
                        )
                        return@launch
                    }
                    runCatching { sendToDestination(dest.hash, packet) }
                        .onSuccess { messageRepo.updateState(msgId, attempts = attempt, lastAttempt = nowMs()) }
                        .onFailure {
                            _events.tryEmit(EngineEvent.Log("msg #$msgId: ✗ retry $attempt threw: ${it::class.simpleName}: ${it.message}"))
                            messageRepo.updateState(msgId, lastError = it.message ?: "send error")
                        }
                }
                // Final attempt complete — give the proof one more grace
                // window before declaring failed. If a proof arrives in
                // that window the PROOF handler flips state to delivered;
                // we re-check before marking failed.
                delay(MSG_BACKOFF_MS.last())
                val finalState = messageRepo.getById(msgId)?.state
                if (finalState != "delivered" && finalState != "failed") {
                    messageRepo.updateState(msgId, state = "failed", lastError = "no proof after $MSG_MAX_ATTEMPTS attempts")
                    _events.tryEmit(EngineEvent.Log("msg #$msgId: ✗ failed — no proof after $MSG_MAX_ATTEMPTS attempts"))
                }
            } catch (_: kotlinx.coroutines.CancellationException) {}
        }
        return msgId
    }

    /**
     * Attempt link-delivered LXMF send (Sideband parity). Returns true
     * if we got a per-packet PROOF back from the responder (= confirmed
     * delivered), false on any failure (timeout, link rejected, no
     * transport at the right moment, etc.) so [sendMessage] can fall
     * back to the opportunistic path.
     *
     * Implementation:
     *   1. Try to reuse a cached ACTIVE link from [lxmfLinks].
     *   2. Otherwise establish a fresh link: createInitiator →
     *      LINKREQUEST broadcast → awaitProof.
     *   3. Build link-LXMF body (dest_hash || source_hash || sig ||
     *      msgpack), send via [LinkSession.sendDataAndAwaitProof].
     *   4. On per-packet PROOF: mark message delivered, cache the
     *      link for the next message to this dest.
     *
     * Wire shape mirrors what we already accept on the responder side
     * in [ResponderLinkSession.handleData] / [unpackLinkMessage], so
     * any RNS receiver that handles inbound link-delivered LXMF will
     * decrypt + parse + verify-sig correctly.
     */
    private suspend fun tryDeliverOverLink(
        msgId: Long,
        dest: io.github.thatsfguy.reticulum.store.StoredDestination,
        content: String,
        title: String,
        id: Identity,
        ourDest: ByteArray,
        imageBytes: ByteArray? = null,
        /** Caller-supplied LXMF fields to merge on top of the
         *  image-field map. Used by `sendReaction` and (future)
         *  `sendReply` to inject field 16 alongside the normal
         *  envelope. Empty by default — no behavior change for
         *  text + image sends. */
        extraFields: Map<Any?, Any?> = emptyMap(),
    ): Boolean {
        // Tap-Send timestamp from the saved row — see the matching
        // comment at the top of sendExistingMessage. Without this,
        // link establishment latency (up to 50s in the 5× retry @
        // 10s loop) shifts the LXMF wire timestamp far enough later
        // than tap-Send time that two messages composed in order can
        // arrive out of order on the recipient if a later message
        // had a faster link than an earlier one.
        val tapSendMs = messageRepo.getById(msgId)?.timestamp ?: nowMs()
        val tapSendSeconds = tapSendMs / 1000.0

        val proofTimeout = proofTimeoutForHops(dest.hopCount)
        val dataProofTimeout = proofTimeoutForHops(dest.hopCount)
        // Resource send takes ADV + N chunks + receiver assembly + PRF.
        // Allow 4× the per-packet timeout per todo.md image-attachment plan.
        val resourceTimeout = dataProofTimeout * 4

        val imageField: Map<Any?, Any?> = if (imageBytes != null) {
            // LXMF FIELD_IMAGE = integer key 6. The VALUE is a 2-element
            // msgpack list: `[extension_string, bytes]`, NOT bare bytes.
            //
            // Verified 2026-05-13 against Sideband sbapp/main.py:2192
            // (`image = ["webp", buf.getvalue()]` — sender) and
            // sbapp/ui/messages.py:814
            // (`CoreImage(io.BytesIO(image_field[1]), ext=image_field[0])`
            //  — receiver). Sideband indexes [0] for the file extension
            // and [1] for the bytes; sending bare bytes was the v1.1.15-16
            // bug that worked mobile-to-mobile (both ends agreed on the
            // wrong format) but failed mobile-to-Sideband.
            //
            // Phase 2 compresses to JPEG, so the extension is `"jpg"`. If
            // we ever ladder down to WebP for size, change the literal
            // here to match — the extension string is purely metadata
            // for the receiver's image-decoder lookup, never compared
            // byte-for-byte by the protocol layer.
            mapOf<Any?, Any?>(6 to listOf("jpg", imageBytes))
        } else emptyMap()
        // Merge caller-supplied extra fields (reactions, replies)
        // on top of the image field. Caller wins on key collision —
        // by design, since a reaction message has empty content and
        // never carries an image so there's no real collision to
        // worry about.
        val fields: Map<Any?, Any?> = if (extraFields.isEmpty()) imageField
        else imageField + extraFields

        // SPEC §5.7 stamp — compute on link-delivered path too so
        // Sideband 1.x recipients don't drop the message for missing
        // PoW. Same workblock + search as the opportunistic path but
        // computed over the link-LXMF message_id (dest_hash || src ||
        // packed_4_element_payload). When dest has no stamp_cost
        // advertised (most non-Sideband peers), stampCost is null and
        // packMessage emits the 4-element form unchanged.
        val stampCost = runCatching {
            destAppDataBytes(dest)?.let { io.github.thatsfguy.reticulum.announce.extractStampCost(it) }
        }.getOrNull()
        val stamp = if (stampCost != null) {
            computeOutboundStamp(
                destHash    = dest.destHash,
                sourceHash  = ourDest,
                // Same tap-Send timestamp as packLinkMessage below
                // — the stamp's workblock is derived from the
                // message_id which is in turn derived from
                // timestamp + payload, so the stamp MUST be
                // computed over the same timestamp the wire
                // body carries or the recipient's stamp
                // verification fails.
                timestampS  = tapSendSeconds,
                title       = title,
                content     = content,
                fields      = fields,
                stampCost   = stampCost,
                msgId       = msgId,
            )
        } else null

        val linkBody = io.github.thatsfguy.reticulum.lxmf.packLinkMessage(
            sourceIdentity   = id,
            destHash         = dest.destHash,
            sourceHash       = ourDest,
            title            = title,
            content          = content,
            timestampSeconds = tapSendSeconds,
            fields           = fields,
            crypto           = crypto,
            stamp            = stamp,
        )
        // Same message_id persistence as the opportunistic-path
        // twin — needed so inbound reactions / replies from the
        // recipient can target this row by its canonical message_id.
        val packed4Link = io.github.thatsfguy.reticulum.codec.MessagePack.encode(
            listOf(
                tapSendSeconds,
                title.encodeToByteArray(),
                content.encodeToByteArray(),
                fields,
            )
        )
        val messageIdHexLink = io.github.thatsfguy.reticulum.lxmf.LxmfStamp
            .computeMessageId(dest.destHash, ourDest, packed4Link, crypto).toHex()
        messageRepo.setMessageId(msgId, messageIdHexLink)

        // Sideband-style retry loop. LXMF.LXMRouter retries the whole
        // link-establishment-plus-data-send cycle up to
        // MAX_DELIVERY_ATTEMPTS=5 with DELIVERY_RETRY_WAIT=10s between
        // attempts. The collision resilience comes from sheer repetition:
        // if a single LINKREQUEST or LRPROOF is lost to LoRa half-duplex
        // contention, the next attempt 10s later almost always gets
        // through. Pre-fix we attempted ONCE and dropped to opportunistic
        // on any failure, losing the link reuse + integrity-check + UX
        // signal that link delivery provides.
        //
        // Image-attached sends use a tighter [IMAGE_LINK_MAX_ATTEMPTS]
        // budget (=2). Opportunistic fallback can't carry images — the
        // single-packet MTU is ~360 B and a step-3 JPEG is ~17 KB — so
        // burning the full 5 × 45s = 4m30s before discovering the image
        // won't go was the dominant cause of the v1.1.15 "image sending
        // is slow" tester report. 2 attempts ≈ 100 s gives one retry
        // window for a transient LoRa collision and bails fast on a
        // genuinely unreachable destination. Text-only sends still get
        // the full 5-attempt budget because their opportunistic fallback
        // DOES deliver.
        val maxAttempts = if (imageBytes != null) IMAGE_LINK_MAX_ATTEMPTS else LINK_MAX_ATTEMPTS
        for (attempt in 1..maxAttempts) {
            val reused = sessionsLock.withLock {
                lxmfLinks[dest.hash]?.takeIf {
                    it.session.link.state == io.github.thatsfguy.reticulum.link.LinkState.ACTIVE
                }
            }
            val session: LinkSession? = if (reused != null) {
                _events.tryEmit(EngineEvent.Log("msg #$msgId: reusing active LXMF link ${reused.linkIdHex}"))
                reused.session
            } else {
                // Drop a stale (closed) cache entry so we don't keep
                // pointing at it after a fresh establish.
                sessionsLock.withLock { lxmfLinks.remove(dest.hash) }
                if (attempt > 1) {
                    _events.tryEmit(EngineEvent.Log("msg #$msgId: link establish retry $attempt/$maxAttempts"))
                }
                establishLxmfLink(msgId, dest, proofTimeout)
            }

            if (session == null) {
                // Establishment failed (LRPROOF timeout / rejected / no
                // path). Wait DELIVERY_RETRY_WAIT and try again unless
                // we've exhausted the budget.
                if (attempt < maxAttempts) {
                    delay(LINK_RETRY_INTERVAL_MS)
                    continue
                }
                _events.tryEmit(EngineEvent.Log(
                    "msg #$msgId: ✗ link establish failed after $maxAttempts attempts — falling back to opportunistic"
                ))
                return false
            }

            val sendDesc = if (imageBytes != null) "Resource (image ${imageBytes.size}B)" else "DATA"
            _events.tryEmit(EngineEvent.Log(
                "msg #$msgId: sending over link ${session.link.linkId!!.toHex()} as $sendDesc (${linkBody.size}B body, attempt $attempt/$maxAttempts)"
            ))

            val delivered = runCatching {
                if (imageBytes != null) {
                    session.sendResource(linkBody, resourceTimeout)
                } else {
                    session.sendDataAndAwaitProof(linkBody, dataProofTimeout)
                }
            }.onFailure {
                _events.tryEmit(EngineEvent.Log("msg #$msgId: link $sendDesc send threw: ${it.message}"))
            }.getOrDefault(false)

            if (delivered) {
                messageRepo.updateState(msgId, state = "delivered", attempts = attempt, lastAttempt = nowMs())
                _events.tryEmit(EngineEvent.Log("msg #$msgId: ✓ delivered via link (attempt $attempt)"))
                // Keep the link warm for follow-up messages to this destination.
                sessionsLock.withLock {
                    lxmfLinks[dest.hash] = LxmfLink(session, session.link.linkId!!.toHex())
                }
                return true
            }

            // Data-proof timed out. The far side may have gone quiet, or
            // the proof packet was lost to collision. Drop the cached
            // link so the next iteration establishes fresh; wait the
            // retry interval before looping.
            sessionsLock.withLock { lxmfLinks.remove(dest.hash) }
            if (attempt < maxAttempts) {
                _events.tryEmit(EngineEvent.Log(
                    "msg #$msgId: link DATA proof timeout (attempt $attempt/$maxAttempts) — re-establishing in ${LINK_RETRY_INTERVAL_MS / 1000}s"
                ))
                delay(LINK_RETRY_INTERVAL_MS)
            } else {
                _events.tryEmit(EngineEvent.Log(
                    "msg #$msgId: ✗ link DATA proof timeout after $maxAttempts attempts — falling back to opportunistic"
                ))
            }
        }
        return false
    }

    /**
     * Establish a fresh initiator-side Link to [dest]'s lxmf.delivery
     * destination. Mirrors the establishment block of [fetchNomadPage]
     * but stripped down — no LINKIDENTIFY, no path reuse logic.
     * Returns the active session on success, null on any failure
     * (so [tryDeliverOverLink] can return false and the caller can
     * fall back to opportunistic delivery).
     */
    private suspend fun establishLxmfLink(
        msgId: Long,
        dest: io.github.thatsfguy.reticulum.store.StoredDestination,
        proofTimeout: Long,
    ): LinkSession? = runCatching {
        val targetSigPub = dest.publicKey.copyOfRange(32, 64)
        val (link, requestData) = io.github.thatsfguy.reticulum.link.Link.createInitiator(
            peerLongTermSigPub = targetSigPub,
            peerDestHash       = dest.destHash,
            crypto             = crypto,
            nowMs              = nowMs(),
        )
        val useHeader2Lr = dest.hopCount > 1 && dest.nextHop != null
        val linkReqPacket = buildPacket(
            headerType    = if (useHeader2Lr) HEADER_2 else HEADER_1,
            transportType = if (useHeader2Lr) TRANSPORT_TRANSPORT else TRANSPORT_BROADCAST,
            destType      = DEST_SINGLE,
            packetType    = io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ,
            destHash      = dest.destHash,
            transportId   = if (useHeader2Lr) dest.nextHop else null,
            payload       = requestData,
        )
        val parsed = parsePacket(linkReqPacket) ?: error("self-parse of LINKREQUEST failed")
        link.setLinkIdFromPacket(parsed)

        val linkIdHex = link.linkId!!.toHex()
        val session = LinkSession(
            link    = link,
            crypto  = crypto,
            sender  = { pkt -> sendForLink(linkIdHex, pkt) },
            nowMs   = nowMs,
            logger  = { line -> _events.tryEmit(EngineEvent.Log("[$linkIdHex] $line")) },
        )
        sessionsLock.withLock { activeSessions[linkIdHex] = session }

        primePath(
            destHash      = dest.destHash,
            requestPath   = { hash -> requestPath(hash) },
            delayMs       = { ms -> delay(ms) },
            onPathFailure = { _events.tryEmit(EngineEvent.Log("msg #$msgId: path? failed: ${it.message}")) },
        )
        sendToDestination(dest.hash, linkReqPacket)
        _events.tryEmit(EngineEvent.Log(
            "msg #$msgId: link → ${dest.hash} (link_id=$linkIdHex, hops=${dest.hopCount})"
        ))

        when (val proof = session.awaitProof(proofTimeout)) {
            is LinkSession.ProofResult.Validated -> {
                _events.tryEmit(EngineEvent.Log(
                    "msg #$msgId: link active rtt=${(proof.rttSeconds * 1000).toLong()}ms"
                ))
                // §6.7.1 — start the initiator-side KEEPALIVE loop now
                // that the link is ACTIVE and RTT has been measured. The
                // loop is parented to the engine's scope so engine
                // detach + child-cancel propagates cancellation; it also
                // self-terminates when link.state != ACTIVE. The earlier
                // session.handleResourceReq + LRRTT path doesn't trigger
                // keepalive — it only fires here on confirmed
                // establishment.
                session.startKeepalive(scope)
                session
            }
            is LinkSession.ProofResult.Invalid -> {
                sessionsLock.withLock { activeSessions.remove(linkIdHex)?.dispose() }
                _events.tryEmit(EngineEvent.Log("msg #$msgId: ✗ LRPROOF rejected: ${proof.reason}"))
                null
            }
            LinkSession.ProofResult.Timeout -> {
                sessionsLock.withLock { activeSessions.remove(linkIdHex)?.dispose() }
                _events.tryEmit(EngineEvent.Log(
                    "msg #$msgId: ✗ no LRPROOF within ${proofTimeout / 1000}s"
                ))
                null
            }
        }
    }.onFailure {
        _events.tryEmit(EngineEvent.Log(
            "msg #$msgId: link establishment threw (${it::class.simpleName}: ${it.message})"
        ))
    }.getOrNull()

    private suspend fun handleIncoming(rawPacket: ByteArray, rssi: Int?, kind: TransportKind) {
        val pkt = parsePacket(rawPacket) ?: run {
            // Symmetric to TcpInterface's tx-log: surface unparseable
            // bytes so we can wire-trace BLE/TCP issues. Truncate to
            // 32B so a single corrupt frame doesn't flood logcat.
            val n = minOf(rawPacket.size, 32)
            val hex = (0 until n).joinToString("") { (rawPacket[it].toInt() and 0xFF).toString(16).padStart(2, '0') }
            _events.tryEmit(EngineEvent.Log("rx ${rawPacket.size}B unparseable: $hex${if (rawPacket.size > n) "..." else ""}"))
            return
        }

        // One-line wire trace of every inbound packet, BEFORE any
        // filtering. Without this, packets dropped by a destHash mismatch
        // or a context-byte we don't handle disappear silently — the
        // BLE inbound-from-Ratdeck investigation got stuck for hours
        // because we couldn't tell whether replies were arriving at the
        // engine or being lost upstream.
        val ptName = when (pkt.packetType) {
            PACKET_DATA     -> "DATA"
            PACKET_ANNOUNCE -> "ANNC"
            PACKET_LINKREQ  -> "LREQ"
            io.github.thatsfguy.reticulum.protocol.PACKET_PROOF -> "PROOF"
            else            -> "PT${pkt.packetType}"
        }
        _events.tryEmit(EngineEvent.Log(
            "rx ${rawPacket.size}B H${pkt.headerType + 1} $ptName dest=${pkt.destHash.toHex()} ctx=0x${pkt.context.toString(16).padStart(2,'0')} hops=${pkt.hops}"
        ))

        // Active link routing: if destHash matches a session's link_id,
        // hand the packet to that session and stop. LRPROOF / RESPONSE
        // packets all flow through this path during a NomadNet fetch.
        val sessionKey = pkt.destHash.toHex()
        val session = sessionsLock.withLock { activeSessions[sessionKey] }
        if (session != null) {
            // Pin this link to the kind that delivered its first
            // packet — the LRPROOF for an initiator session, or the
            // first inbound DATA for a responder session whose kind
            // was already pre-pinned at construction. getOrPut is
            // idempotent: subsequent packets on the same link don't
            // override the pin even if the kind changes (e.g. a
            // duplicate arriving via a redundant path on the other
            // transport).
            linkKinds.getOrPut(sessionKey) { kind }
            session.handlePacket(pkt, rssi)
            return
        }

        // PROOF packets that don't match an active link session are
        // (almost always) opportunistic-DATA delivery proofs from the
        // recipient of one of our outgoing messages. The dest_hash
        // field of an opportunistic proof IS the 16-byte truncated
        // full hash of the original DATA packet (SPEC §6.5.3); we
        // look up by prefix match against the row's stored full hash.
        //
        // SPEC §6.5.5 — verify the Ed25519 sig in the proof body
        // against the recipient's long-term Ed25519 pub before
        // accepting. The recipient is the destination of the original
        // DATA, i.e. `msg.contactHash`. The two wire forms per §6.5.1
        // are:
        //   explicit (96B): payload[0..32] = full hash, payload[32..96] = sig
        //   implicit (64B): payload[0..64] = sig (hash known to us only)
        // Both are verified against the FULL hash we stored on the
        // outgoing row. Mismatched explicit-form hash (someone
        // forwarded an unrelated proof with our truncated hash) → log
        // + drop. Sig verify failure → log + drop. Forged proofs are
        // silently dropped instead of completing — a legitimate proof
        // that races in afterwards can still resolve.
        //
        // Without this check, ANY on-path observer could mint a fake
        // 64-byte PROOF (all-zero sig works if we don't verify) with
        // dest_hash matching our outgoing message's truncated hash,
        // and flip our state to "delivered" while the real recipient
        // never sees the message. Security review 2026-05-07 flagged
        // this vector; v1.1.22 fixes it for new sends, with a
        // back-compat fallback (no sig verify) for legacy rows whose
        // stored packetHash is still the truncated form.
        if (pkt.packetType == io.github.thatsfguy.reticulum.protocol.PACKET_PROOF) {
            val msg = messageRepo.getOutgoingByPacketHash(sessionKey)
            if (msg == null) {
                val activeKeys = sessionsLock.withLock { activeSessions.keys.toList() }
                _events.tryEmit(EngineEvent.Log(
                    "rx PROOF dest=$sessionKey ctx=0x${pkt.context.toString(16).padStart(2,'0')} (no match; active=$activeKeys)"
                ))
                return
            }
            if (msg.state == "delivered") {
                // Already delivered; the duplicate PROOF doesn't hurt
                // anything but skipping the verify+update keeps the
                // logs quiet.
                return
            }

            val verified = verifyOpportunisticProof(pkt, msg)
            if (!verified) {
                // Reasons (each logged inside verifyOpportunisticProof):
                //   - stored packetHash is the truncated form, full
                //     hash unavailable for sig verification (legacy)
                //   - recipient's long-term Ed25519 pub unknown
                //     (manual stub, no announce yet)
                //   - wire length neither 64 nor 96 (malformed)
                //   - explicit form's embedded hash != our stored hash
                //   - Ed25519_verify returned false (forged or replay)
                //
                // For legacy rows where we genuinely can't verify,
                // verifyOpportunisticProof returns FALSE but logs the
                // "back-compat unverified" case — we accept the proof
                // anyway since the alternative is leaving every
                // pre-v1.1.22 outgoing message stuck on "sent" forever
                // even when delivery genuinely happened.
                if (msg.packetHash != null && msg.packetHash.length == 32) {
                    // Legacy row — accept without verification.
                    messageRepo.updateState(msg.id, state = "delivered", lastAttempt = nowMs())
                    _events.tryEmit(EngineEvent.Log(
                        "✓ delivered msg #${msg.id} (proof for $sessionKey, legacy row — sig not verified)"
                    ))
                }
                return
            }
            messageRepo.updateState(msg.id, state = "delivered", lastAttempt = nowMs())
            _events.tryEmit(EngineEvent.Log("✓ delivered msg #${msg.id} (proof for $sessionKey, sig verified)"))
            return
        }

        when (pkt.packetType) {
            PACKET_ANNOUNCE -> handleAnnounce(pkt, rssi, kind)
            PACKET_DATA     -> handleData(pkt, rssi, kind)
            PACKET_LINKREQ  -> handleLinkRequest(pkt, kind)
            else            -> _events.tryEmit(EngineEvent.Log("rx pkt type ${pkt.packetType} ctx=${pkt.context}"))
        }
    }

    /**
     * Inbound peer-initiated link. Validates the request, sends an
     * LRPROOF, and registers a [ResponderLinkSession] so subsequent DATA
     * packets to link_id route into our LXMF receiver.
     *
     * Wire layout of the LRPROOF (from upstream Link.prove):
     *   flags=0x0F (HEADER_1 | DEST_LINK | PACKET_PROOF), hops=0,
     *   destHash=link_id, context=0xFF (CTX_LRPROOF),
     *   payload = signature(64) || ourEphemeralX25519Pub(32) || signalling(3)
     */
    private suspend fun handleLinkRequest(pkt: io.github.thatsfguy.reticulum.protocol.Packet, kind: TransportKind) {
        val ourDest = ourDestHash()
        if (!pkt.destHash.contentEquals(ourDest)) {
            // Not for us — relay layer would normally forward; we don't
            // play that role yet.
            return
        }
        val id = ensureIdentity()
        if (transports[kind] == null) {
            _events.tryEmit(EngineEvent.Log("LINKREQUEST received on $kind but transport already detached"))
            return
        }

        val (link, proofPayload) = runCatching {
            io.github.thatsfguy.reticulum.link.Link.validateRequest(pkt, id, crypto)
        }.onFailure { _events.tryEmit(EngineEvent.Log("LINKREQUEST rejected: ${it.message}")) }
            .getOrNull() ?: return

        val linkIdHex = link.linkId!!.toHex()
        // Pin link to the inbound kind BEFORE sending the LRPROOF —
        // that way every packet emitted on this link, including this
        // first proof, follows the same transport.
        linkKinds[linkIdHex] = kind
        val proofPacket = buildPacket(
            headerType = HEADER_1,
            destType = io.github.thatsfguy.reticulum.protocol.DEST_LINK,
            packetType = io.github.thatsfguy.reticulum.protocol.PACKET_PROOF,
            destHash = link.linkId!!,
            context = io.github.thatsfguy.reticulum.protocol.CTX_LRPROOF,
            payload = proofPayload,
        )
        sendOn(kind, proofPacket)
        _events.tryEmit(EngineEvent.Log("→ LRPROOF for $linkIdHex (responder, on $kind)"))

        val session = ResponderLinkSession(
            link = link,
            identity = id,
            crypto = crypto,
            sender = { pkt -> sendForLink(linkIdHex, pkt) },
            nowMs = nowMs,
            onLxmfReceived = { plaintext, senderHash, rssi, hopCount, linkPeerHex ->
                handleLinkLxmf(plaintext, senderHash, rssi, hopCount, linkPeerHex)
            },
            onClose = { closedHex, reason ->
                sessionsLock.withLock { activeSessions.remove(closedHex)?.dispose() }
                linkKinds.remove(closedHex)
                _events.tryEmit(EngineEvent.Log("link $closedHex closed: $reason"))
            },
            logger = { line -> _events.tryEmit(EngineEvent.Log("[$linkIdHex] $line")) },
        )
        sessionsLock.withLock { activeSessions[linkIdHex] = session }
    }

    /**
     * Persist a link-delivered LXMF message. Mirrors [handleData]'s
     * opportunistic path: try to verify against the sender's cached
     * identity, save with state="verified" or "unverified", trigger a
     * path request if the sender is unknown so a future announce can
     * retroactively re-verify.
     */
    private suspend fun handleLinkLxmf(
        linkPlaintext: ByteArray,
        senderDestHashHex: String,
        rssi: Int?,
        hopCount: Int?,
        linkPeerDestHashHex: String?,
    ) {
        // Guard the decode (audit M6): malformed msgpack must fail
        // cleanly here, not throw out of the link handler.
        val msg = runCatching {
            io.github.thatsfguy.reticulum.lxmf.unpackLinkMessage(linkPlaintext, crypto)
        }.getOrElse {
            _events.tryEmit(EngineEvent.Log("link LXMF unpack failed: ${it.message}"))
            return
        }
        val dest = destinationRepo.get(senderDestHashHex)
        val senderKey = dest?.publicKey?.takeIf { it.size == 64 }
        val variant = senderKey?.let {
            val senderId = Identity(crypto)
            senderId.loadFromPublicKey(it)
            io.github.thatsfguy.reticulum.lxmf.verifyMessageSignature(msg, senderId, crypto)
        }
        // SECURITY (audit H2): a message from a sender whose identity key
        // we HAVE, whose signature does not verify, is forged or tampered
        // — the link decrypt already proved the bytes intact. Drop it;
        // surfacing it would display attacker-chosen content under the
        // claimed sender's identity. (A sender we have no key for stays
        // "unverified" and is re-checked when their announce arrives.)
        if (senderKey != null && variant == null) {
            _events.tryEmit(EngineEvent.Log(
                "dropped link LXMF with invalid signature from $senderDestHashHex — forged",
            ))
            return
        }
        val effectiveTimestamp = correctClocklessTimestamp(msg.timestamp, nowMs())
        val isUnverified = variant == null
        // MED-6 opt-in (see opportunistic-path twin).
        if (isUnverified && dropUnverifiedProvider()) {
            _events.tryEmit(EngineEvent.Log(
                "dropped unverified link msg from $senderDestHashHex (user opted out)"
            ))
            return
        }
        // Field 16 dispatch (see opportunistic-path twin for full
        // commentary). Reactions are aggregated onto the target row;
        // replies set replyToMessageId on the saved row.
        val payload16 = extractField16(msg.fields)
        if (payload16 is Field16Payload.Reaction) {
            val applied = messageRepo.applyReaction(
                payload16.reactionTo, payload16.emoji, payload16.sender,
            )
            _events.tryEmit(EngineEvent.Log(
                if (applied) "reaction ${payload16.emoji} from $senderDestHashHex applied to ${payload16.reactionTo.take(16)}…"
                else "reaction ${payload16.emoji} from $senderDestHashHex dropped — target ${payload16.reactionTo.take(16)}… not found locally"
            ))
            return
        }
        val replyToMessageId = (payload16 as? Field16Payload.Reply)?.replyTo
        // Canonical LXMF message_id for this row (32-byte SHA-256 hex).
        val ourDestForMid = ourDestHash()
        val messageIdHex = io.github.thatsfguy.reticulum.lxmf.LxmfStamp
            .computeMessageId(ourDestForMid, msg.sourceHash, msg.msgpackForId, crypto).toHex()
        // SECURITY (audit M4): durable replay dedup — link-delivered LXMF
        // previously had no dedup at all, so a replayed message re-stored.
        if (messageRepo.getByMessageId(messageIdHex) != null) {
            _events.tryEmit(EngineEvent.Log(
                "duplicate link LXMF $messageIdHex — already stored, dropping",
            ))
            return
        }
        val (imageBytes, imageRawSize) = extractImageField(msg.fields)
        if (imageBytes == null && imageRawSize > 0) {
            _events.tryEmit(EngineEvent.Log(
                "link msg from $senderDestHashHex: image field present but ${imageRawSize} B > ${INBOUND_IMAGE_MAX_BYTES} B — dropped"
            ))
        }
        // v1.1.39 — uniform routing rule (fwdsvc maintainer's
        // simplification). arrivedViaDest = LINKIDENTIFY peer when
        // available, else the LXMF body's source_hash. Covers two
        // distinct relay models with one rule:
        //
        //   - fwdsvc rebroadcast (the case in the wild): fwdsvc unpacks
        //     each inbound LXMF, prepends "[OriginatorNick] " to the
        //     content, RE-SIGNS as fwdsvc, and re-emits with
        //     source_hash = fwdsvc. The LXMF body's source_hash IS the
        //     relay; LINKIDENTIFY (if it fires) just confirms it.
        //     Per-recipient delivery is usually opportunistic
        //     (Delivery.Send falls through to link only on
        //     ErrPayloadTooLarge; a typical "[BlueP] test" body is
        //     ~100-150 B, well under the 295 B cap), so the fallback
        //     branch is the live path.
        //
        //   - passthrough relay (hypothetical): the relay forwards
        //     bytes verbatim, source_hash = original sender; the link
        //     peer's destHash (from LINKIDENTIFY) is the only signal
        //     identifying the relay. Captured by the LINKIDENTIFY
        //     branch when present.
        //
        // For direct 1:1 chats the source_hash IS the conversation peer,
        // so the fallback equals contactHash and the send-time routing
        // override is a no-op. Audit reference: 2026-05-14 fwdsvc
        // maintainer's clarification thread — internal/lxmf/delivery.go
        // (opportunistic-first policy) + rebroadcast wire shape doc.
        val arrivedViaDest = linkPeerDestHashHex ?: senderDestHashHex
        val savedId = messageRepo.save(StoredMessage(
            contactHash = senderDestHashHex,
            direction = "incoming",
            content = msg.content,
            title = msg.title,
            timestamp = effectiveTimestamp,
            state = if (!isUnverified) "verified" else "unverified",
            attempts = 0,
            lastAttempt = 0,
            rawPacket = if (isUnverified) linkPlaintext else null,
            rssi = rssi,
            hopCount = hopCount,
            imageBytes = imageBytes,
            messageId = messageIdHex,
            replyToMessageId = replyToMessageId,
            arrivedViaDest = arrivedViaDest,
        ))
        // Diagnostic only when the routing destination actually differs
        // from the conversation peer (i.e. a passthrough relay case via
        // LINKIDENTIFY). For fwdsvc rebroadcast and direct 1:1 chats
        // arrivedViaDest == senderDestHashHex, so logging there is just
        // noise.
        if (arrivedViaDest != senderDestHashHex) {
            _events.tryEmit(EngineEvent.Log(
                "msg #$savedId from $senderDestHashHex arrived via relay $arrivedViaDest — react/reply will route through relay"
            ))
        }
        _events.tryEmit(EngineEvent.MessageReceived(
            messageId = savedId,
            contactHash = senderDestHashHex,
            content = msg.content,
            verified = !isUnverified,
        ))

        if (isUnverified && pathRequestsSent.add(senderDestHashHex)) {
            runCatching { requestPath(msg.sourceHash) }
                .onSuccess { _events.tryEmit(EngineEvent.Log("path? for unverified link sender $senderDestHashHex")) }
                .onFailure { _events.tryEmit(EngineEvent.Log("path? failed for $senderDestHashHex: ${it.message}")) }
        }
    }

    /**
     * Verify an inbound opportunistic-DATA PROOF (SPEC §6.5.1) against
     * the recipient's long-term Ed25519 pub. The expected full hash
     * comes from [msg]'s stored `packetHash` column (v1.1.22+ stores
     * the full 32-byte hex form there). Returns true on successful
     * verification, false on any failure path. Caller distinguishes
     * "legacy row, can't verify" from "real failure" by checking
     * `msg.packetHash.length == 32` after a false return.
     *
     * Both proof forms accepted per §6.5.5:
     *   explicit (96B): payload[0..32] = hash, payload[32..96] = sig
     *   implicit (64B): payload[0..64] = sig (hash must match ours)
     */
    private suspend fun verifyOpportunisticProof(
        pkt: io.github.thatsfguy.reticulum.protocol.Packet,
        msg: StoredMessage,
    ): Boolean {
        val storedHex = msg.packetHash
        if (storedHex == null || storedHex.length != 64) {
            // Legacy row (32-char truncated) — full hash unavailable
            // for sig verification.
            return false
        }
        val storedFullHash = runCatching { storedHex.hexBytesOrThrow("packetHash", expectedLen = 32) }
            .getOrElse {
                _events.tryEmit(EngineEvent.Log("PROOF: stored packetHash failed hex decode (${it.message})"))
                return false
            }

        // Find the recipient's long-term Ed25519 pub. Identity layout
        // is X25519_pub(32) || Ed25519_pub(32) per §1.2 — strip the
        // first 32 bytes.
        val dest = destinationRepo.get(msg.contactHash)
        if (dest == null || dest.publicKey.size != 64) {
            _events.tryEmit(EngineEvent.Log(
                "PROOF: recipient ${msg.contactHash.take(16)}… has no announced public key — can't verify"
            ))
            return false
        }
        val recipientSigPub = dest.publicKey.copyOfRange(32, 64)

        val payload = pkt.payload
        val sig: ByteArray = when (payload.size) {
            96 -> {
                // Explicit form. Cross-check the embedded hash against
                // our stored hash; mismatch means the PROOF is for a
                // different packet despite the truncated dest_hash
                // collision (1-in-2^128 random collision, more likely
                // a forwarded unrelated proof or a forge attempt).
                val embeddedHash = payload.copyOfRange(0, 32)
                if (!embeddedHash.contentEquals(storedFullHash)) {
                    _events.tryEmit(EngineEvent.Log(
                        "PROOF: explicit-form embedded hash != stored hash for msg #${msg.id} — dropping"
                    ))
                    return false
                }
                payload.copyOfRange(32, 96)
            }
            64 -> payload
            else -> {
                _events.tryEmit(EngineEvent.Log(
                    "PROOF: wrong size ${payload.size}B (need 64 or 96) — dropping"
                ))
                return false
            }
        }

        val ok = crypto.ed25519Verify(sig, storedFullHash, recipientSigPub)
        if (!ok) {
            _events.tryEmit(EngineEvent.Log(
                "PROOF: Ed25519 sig verify failed for msg #${msg.id} (forged or replay) — dropping"
            ))
        }
        return ok
    }

    /**
     * Decode a [StoredDestination]'s appData hex back to bytes for
     * stamp_cost / display_name extraction. Returns null when the hex
     * is empty or malformed — caller treats null as "no stamp
     * required".
     */
    private fun destAppDataBytes(
        dest: io.github.thatsfguy.reticulum.store.StoredDestination,
    ): ByteArray? {
        val hex = dest.appDataHex
        if (hex.isEmpty()) return null
        return runCatching { hex.hexBytesOrThrow("appDataHex", expectedLen = hex.length / 2) }.getOrNull()
    }

    /**
     * SPEC §5.7.2 stamp generation wrapper. Computes the workblock
     * from the deterministic message_id (`SHA256(destHash || sourceHash
     * || packed_4_element_payload)`) and brute-forces a 32-byte stamp
     * meeting the recipient's `stampCost`. Returns null + logs a
     * graceful-degradation message when the cost exceeds
     * [LxmfStamp.MAX_TARGET_COST] (the user's CPU isn't worth burning
     * minutes on PoW; receivers with extreme cost requirements are
     * effectively opting out of our send).
     */
    private suspend fun computeOutboundStamp(
        destHash: ByteArray,
        sourceHash: ByteArray,
        timestampS: Double,
        title: String,
        content: String,
        fields: Map<Any?, Any?>,
        stampCost: Int,
        msgId: Long,
    ): ByteArray? {
        if (stampCost > io.github.thatsfguy.reticulum.lxmf.LxmfStamp.MAX_TARGET_COST) {
            _events.tryEmit(EngineEvent.Log(
                "msg #$msgId: ⚠ recipient stamp_cost=$stampCost exceeds MAX_TARGET_COST=${io.github.thatsfguy.reticulum.lxmf.LxmfStamp.MAX_TARGET_COST} — sending unstamped (may be dropped)"
            ))
            return null
        }
        return runCatching {
            // The packed 4-element payload is the "stripped" variant
            // (no stamp); message_id is computed over this.
            val titleBytes = title.encodeToByteArray()
            val contentBytes = content.encodeToByteArray()
            val packed4 = io.github.thatsfguy.reticulum.codec.MessagePack.encode(
                listOf(timestampS, titleBytes, contentBytes, fields)
            )
            val messageId = io.github.thatsfguy.reticulum.lxmf.LxmfStamp.computeMessageId(
                destHash = destHash,
                sourceHash = sourceHash,
                packedPayload4 = packed4,
                crypto = crypto,
            )
            _events.tryEmit(EngineEvent.Log(
                "msg #$msgId: computing stamp (cost=$stampCost, expected ~${1 shl stampCost} tries)"
            ))
            val workblock = io.github.thatsfguy.reticulum.lxmf.LxmfStamp.buildWorkblock(messageId, crypto)
            val started = nowMs()
            val stamp = io.github.thatsfguy.reticulum.lxmf.LxmfStamp.findStamp(workblock, stampCost, crypto)
            val elapsed = nowMs() - started
            _events.tryEmit(EngineEvent.Log("msg #$msgId: ✓ stamp computed in ${elapsed}ms"))
            stamp
        }.onFailure {
            _events.tryEmit(EngineEvent.Log(
                "msg #$msgId: stamp generation threw (${it::class.simpleName}: ${it.message}) — sending unstamped"
            ))
        }.getOrNull()
    }

    /**
     * Pack an opportunistic LXMF message with an optional stamp. Wraps
     * [computeOutboundStamp] + [packMessage] so the engine's
     * opportunistic-fallback path stays a single call.
     */
    private suspend fun packLxmfWithOptionalStamp(
        sourceIdentity: Identity,
        destHash: ByteArray,
        sourceHash: ByteArray,
        title: String,
        content: String,
        timestampSeconds: Double,
        fields: Map<Any?, Any?>,
        stampCost: Int?,
        msgId: Long,
    ): ByteArray {
        val stamp = stampCost?.let {
            computeOutboundStamp(
                destHash    = destHash,
                sourceHash  = sourceHash,
                timestampS  = timestampSeconds,
                title       = title,
                content     = content,
                fields      = fields,
                stampCost   = it,
                msgId       = msgId,
            )
        }
        return packMessage(
            sourceIdentity   = sourceIdentity,
            destHash         = destHash,
            sourceHash       = sourceHash,
            title            = title,
            content          = content,
            timestampSeconds = timestampSeconds,
            fields           = fields,
            crypto           = crypto,
            stamp            = stamp,
        )
    }

    private suspend fun handleAnnounce(
        pkt: io.github.thatsfguy.reticulum.protocol.Packet,
        rssi: Int?,
        kind: TransportKind,
    ) {
        // Drop announces that came back to us — typically our own
        // packet looped via a relay (RNode in repeater mode, or an
        // upstream rnsd retransmitting). Ingesting these would
        // populate the contact list with a row pointing at ourselves.
        val ours = runCatching { ourDestHash() }.getOrNull()
        if (ours != null && pkt.destHash.contentEquals(ours)) {
            _events.tryEmit(EngineEvent.Log("self-announce echo dropped (${pkt.destHash.toHex()})"))
            return
        }
        // Dedup against the in-session set. Each announce has a unique
        // random_hash (10 bytes baked into the payload), so the full
        // packet hash uniquely identifies one emission across the 5-6
        // relay paths an rnsd typically forwards through.
        val truncHashHex = runCatching {
            io.github.thatsfguy.reticulum.protocol.TruncatedHash
                .of(computePacketFullHash(pkt, crypto)).hex
        }.getOrNull()
        val parsed = parseAnnounce(pkt.payload, pkt.contextFlag, pkt.destHash, crypto) ?: return
        if (!validateAnnounce(parsed, crypto)) {
            // validateAnnounce now checks BOTH signature and
            // dest_hash↔(name_hash,identity_hash) consistency. The two
            // failure modes are indistinguishable at the call site
            // without re-running each step; the diagnostic split is in
            // the validator if we ever need it.
            _events.tryEmit(EngineEvent.Log("announce rejected ${pkt.destHash.toHex()} (sig or hash mismatch)"))
            return
        }
        // Dedup AFTER validation (SECURITY audit M3): a malformed or
        // forged announce must never consume a dedup slot — remembering
        // it before validation lets an injected bad packet pre-empt the
        // slot a genuine announce with the same hash would use.
        if (truncHashHex != null && !rememberAnnounce(truncHashHex)) {
            // Silent — duplicate of an announce we already processed.
            return
        }
        val nameHashHex = parsed.nameHash.toHex()
        val knownService = KnownDestinations.byNameHashHex(nameHashHex)
        val extractedName = extractDisplayName(parsed.appData)

        // Telemetry parse — only meaningful for non-LXMF announces.
        // parseTelemetryBytes tries both legacy `key=value;` text AND
        // msgpack-encoded `[int_key: value]` (used by `rnstransport.broadcasts`
        // BackboneInterface / RNodeInterface / TCPInterface gossip). v0.1.51.
        val telemetry = if (knownService?.name != "lxmf.delivery") {
            runCatching { parseTelemetryBytes(parsed.appData) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        } else null
        val coords = telemetry?.let { extractCoordinates(it) }

        val hashHex = pkt.destHash.toHex()
        val existing = destinationRepo.get(hashHex)
        // SPEC §4.5 rule 4 — first-announcer-wins. If this destination
        // is already known with a 64-byte public key and the announce
        // carries a DIFFERENT one, reject it: a destination_hash that
        // resolves to two distinct public keys can only arise from a
        // forced SHA-256 collision, never a legitimate re-announce.
        if (existing != null && existing.publicKey.size == 64 &&
            !existing.publicKey.contentEquals(parsed.publicKey)
        ) {
            _events.tryEmit(EngineEvent.Log(
                "announce rejected: $hashHex re-announced with a different public key (§4.5#4)"))
            return
        }
        // Compute the path update ONCE — mergePathFromAnnounce mutates
        // bestPathSeenMs as a side-effect of "we adopted this path."
        // Calling it twice would still be correct (idempotent), but the
        // single-call form keeps the side-effect explicit.
        val mergedPath = mergePathFromAnnounce(
            destHashHex = hashHex,
            existingHopCount = existing?.hopCount,
            existingNextHop = existing?.nextHop,
            newHopCount = pkt.hops + 1,
            newNextHop = pkt.transportId,
        )
        val merged = StoredDestination(
            hash = hashHex,
            identityHash = parsed.identityHash.toHex(),
            publicKey = parsed.publicKey,
            destHash = pkt.destHash,
            nameHash = parsed.nameHash,
            ratchetPub = parsed.ratchet,
            displayName = io.github.thatsfguy.reticulum.announce.resolveDisplayName(
                extracted = extractedName,
                existing = existing?.displayName,
                knownLabel = knownService?.label,
            ),
            appName = knownService?.name,
            appLabel = knownService?.label,
            telemetry = telemetry ?: existing?.telemetry,
            lat = coords?.first ?: existing?.lat,
            lon = coords?.second ?: existing?.lon,
            appDataHex = parsed.appData.toHex(),
            lastSeen = nowMs(),
            rssi = rssi ?: existing?.rssi,
            favorite = existing?.favorite ?: false,
            source = existing?.source ?: "announce",
            // Sticky-shortest-path — see [mergePathFromAnnounce] for the
            // rule table. Within [PATH_STALE_MS] of the last good-path
            // refresh, worse-hop announces are ignored (the wider mesh
            // re-emits a peer's own announce at higher hop counts and
            // would otherwise flip our outbound through a needless relay).
            hopCount = mergedPath.hopCount,
            nextHop = mergedPath.nextHop,
            // Preserve the user's local nickname across announce
            // overwrites — without this an inbound re-announce would
            // null out the userLabel on every path-response.
            userLabel = existing?.userLabel,
        )
        destinationRepo.upsertFromAnnounce(merged)
        maybeEvictDestinations()

        // Per-destination transport affinity: outbound traffic to this
        // peer prefers the [kind] that delivered the BEST announce
        // (fewest hops). See [updateAffinityFromAnnounce] kdoc for the
        // sticky / tie-break rules — they're the whole reason link
        // delivery survives BLE+TCP coexistence.
        updateAffinityFromAnnounce(hashHex, kind, pkt.hops + 1)

        // If this announce just FILLED IN a public key that was
        // previously empty (manual-stub contact pre-announce, or a
        // QR-stub without keys), drain any messages parked in
        // "queued" state waiting for that key. drainQueuedOutgoing
        // is cheap (a single getAll + filter) but we still gate on
        // this transition rather than firing on every announce so
        // a busy mesh doesn't burn cycles in the drain path. See
        // sendMessage's no-publicKey branch — that's the producer
        // side of this rendezvous.
        val wasPreviouslyUnkeyed = existing == null || existing.publicKey.size != 64
        if (wasPreviouslyUnkeyed && merged.publicKey.size == 64) {
            drainQueuedOutgoing()
        }

        if (knownService?.name == "lxmf.delivery") {
            _events.tryEmit(EngineEvent.MessagableSeen(hashHex, merged.displayName, rssi, knownService.name))
            // Retroactive re-verify: if this announce gives us the identity
            // of a sender whose previous messages we couldn't verify, walk
            // those rows and try again with the now-known sig pub. Each
            // row that succeeds flips "unverified" → "verified" and the
            // stored LXMF plaintext is no longer needed.
            scope.launch { reverifyMessagesFrom(hashHex, parsed.publicKey) }
        } else {
            _events.tryEmit(EngineEvent.NodeSeen(hashHex, merged.displayName, rssi, knownService?.name))
        }
    }

    private suspend fun reverifyMessagesFrom(senderHashHex: String, publicKey: ByteArray) {
        val candidates = messageRepo.getForContact(senderHashHex)
            .filter { it.state == "unverified" && it.rawPacket != null }
        if (candidates.isEmpty()) return

        val senderId = Identity(crypto)
        senderId.loadFromPublicKey(publicKey)
        val ourDest = ourDestHash()
        var verifiedCount = 0
        for (row in candidates) {
            val plaintext = row.rawPacket ?: continue
            val msg = runCatching { unpackMessage(plaintext, ourDest, crypto) }.getOrNull() ?: continue
            val variant = verifyMessageSignature(msg, senderId, crypto) ?: continue
            messageRepo.updateState(row.id, state = "verified")
            verifiedCount++
        }
        if (verifiedCount > 0) {
            _events.tryEmit(EngineEvent.Log(
                "re-verified $verifiedCount prior message${if (verifiedCount == 1) "" else "s"} from $senderHashHex"
            ))
        }
    }

    private suspend fun handleData(pkt: io.github.thatsfguy.reticulum.protocol.Packet, rssi: Int?, kind: TransportKind) {
        val ourDest = ourDestHash()

        // Path-request handling. Peers send a path? before sending us
        // LXMF DATA — without us responding (by re-announcing) the
        // sender's path? times out and the message never goes out.
        // Symptom that surfaced this: 13 inbound 51B DATA packets to
        // 6b9f66... (the rnstransport.path.request destination) on
        // BLE, all silently dropped by the destHash mismatch below.
        val pathReqDest = computeDestinationHash(crypto, "rnstransport.path.request", ByteArray(0))
        if (pkt.destHash.contentEquals(pathReqDest)) {
            val target = parsePathRequestTarget(pkt.payload)
            if (target == null) {
                _events.tryEmit(EngineEvent.Log("path? rx malformed (${pkt.payload.size}B)"))
                return
            }
            if (target.contentEquals(ourDest)) {
                _events.tryEmit(EngineEvent.Log("path? rx for us — re-announcing as PATH_RESPONSE"))
                runCatching { sendAnnounce(asPathResponse = true) }.onFailure {
                    _events.tryEmit(EngineEvent.Log("re-announce on path? failed: ${it.message}"))
                }
            } else {
                // We're not a transport node, so we can't fulfill
                // path? for other destinations. Just trace it.
                _events.tryEmit(EngineEvent.Log("path? rx for ${target.toHex()} (not us; not transport)"))
            }
            return
        }

        if (!pkt.destHash.contentEquals(ourDest)) return

        val id = ensureIdentity()
        // Candidate decrypt keys, tried in order of likelihood:
        //   1. current ratchet  — most recent peer announces will use this
        //   2. previous ratchet — for in-flight messages encrypted just before
        //                         our last rotation (peers may not have seen
        //                         the new announce yet); see Identity.rotateRatchet
        //   3. long-term enc    — fallback for peers that don't track our
        //                         ratchet at all (Sideband sometimes does this)
        val candidates = listOfNotNull(
            id.ratchetPrivKey,
            id.previousRatchetPrivKey,
            id.encPrivKey,
        )
        val plaintext = runCatching { tokenCrypto.decrypt(pkt.payload, candidates, id.hash!!) }
            .onFailure { _events.tryEmit(EngineEvent.Log("decrypt fail: ${it.message}")) }
            .getOrNull() ?: return

        // Send a delivery proof on every successful decrypt — Sideband and
        // MeshChatX keep retransmitting opportunistic LXMF until they get
        // back a proof, even if we're already storing the message. We
        // always re-emit the proof on duplicates so a re-tx that crossed
        // our first proof in flight still gets acked.
        val fullHash = runCatching { computePacketFullHash(pkt, crypto) }.getOrNull()
        if (fullHash != null) {
            runCatching { sendDeliveryProof(fullHash, kind) }
                .onFailure { _events.tryEmit(EngineEvent.Log("proof send failed: ${it.message}")) }
        }

        // Dedup against the in-session set. Duplicates get re-acked above
        // but skip storage so the inbox doesn't fill with copies.
        val truncHashHex = fullHash?.let { io.github.thatsfguy.reticulum.protocol.TruncatedHash.of(it).hex }
        if (truncHashHex != null && !rememberIncomingData(truncHashHex)) {
            _events.tryEmit(EngineEvent.Log("dup data $truncHashHex (re-acked)"))
            return
        }

        // Guard the decode (audit M6): malformed msgpack inside an
        // otherwise-decryptable body must not throw out of here — the
        // codec now fails with a clean IllegalArgumentException.
        val msg = runCatching { unpackMessage(plaintext, ourDest, crypto) }
            .getOrElse {
                _events.tryEmit(EngineEvent.Log("opportunistic LXMF unpack failed: ${it.message}"))
                return
            }
        val sourceHashHex = msg.sourceHash.toHex()
        val dest = destinationRepo.get(sourceHashHex)

        val senderKey = dest?.publicKey?.takeIf { it.size == 64 }
        val variant = senderKey?.let {
            val senderId = Identity(crypto)
            senderId.loadFromPublicKey(it)
            verifyMessageSignature(msg, senderId, crypto)
        }
        // SECURITY (audit H2): the Token decrypt proved the bytes intact,
        // so a key we HAVE failing to verify the signature means the
        // message is forged/tampered — drop it rather than display
        // attacker content under the claimed sender's identity.
        if (senderKey != null && variant == null) {
            _events.tryEmit(EngineEvent.Log(
                "dropped opportunistic LXMF with invalid signature from $sourceHashHex — forged",
            ))
            return
        }

        val effectiveTimestamp = correctClocklessTimestamp(msg.timestamp, nowMs())
        val isUnverified = variant == null
        // MED-6 opt-in: when the user has chosen to reject unverified
        // messages entirely, drop on the floor instead of persisting +
        // notifying. This closes the first-contact phishing surface
        // where an attacker can craft an opportunistic LXMF from a
        // not-yet-known source_hash with attacker-chosen display name.
        // Default is false (preserve the legacy "show as unverified,
        // retroactively flip to verified once their announce arrives"
        // behaviour). Audit reference: 2026-05-13 MED-6.
        if (isUnverified && dropUnverifiedProvider()) {
            _events.tryEmit(EngineEvent.Log(
                "dropped unverified opportunistic msg from $sourceHashHex (user opted out)"
            ))
            return
        }
        // Field 16 dispatch — Columba/Sideband convention. A reaction
        // is an empty-body LXMF that applies to a previously-received
        // message; we merge it onto the target row's reactionsJson and
        // do NOT save a separate bubble. A reply rides on top of a
        // normal LXMF and just adds replyToMessageId to the saved row.
        // See extractField16's kdoc + plans/serialized-prancing-quill.md.
        val payload16 = extractField16(msg.fields)
        if (payload16 is Field16Payload.Reaction) {
            val applied = messageRepo.applyReaction(
                payload16.reactionTo, payload16.emoji, payload16.sender,
            )
            _events.tryEmit(EngineEvent.Log(
                if (applied) "reaction ${payload16.emoji} from $sourceHashHex applied to ${payload16.reactionTo.take(16)}…"
                else "reaction ${payload16.emoji} from $sourceHashHex dropped — target ${payload16.reactionTo.take(16)}… not found locally"
            ))
            return
        }
        val replyToMessageId = (payload16 as? Field16Payload.Reply)?.replyTo
        // Compute the canonical LXMF message_id so future reactions /
        // replies that target this row can find it. Hex string, 64
        // chars (32-byte SHA-256).
        val messageIdHex = io.github.thatsfguy.reticulum.lxmf.LxmfStamp
            .computeMessageId(ourDest, msg.sourceHash, msg.msgpackForId, crypto).toHex()
        // SECURITY (audit M4): durable replay dedup. rememberIncomingData
        // above is an in-memory 256-entry set lost on restart and rolled
        // by volume; the canonical message_id check is restart-durable.
        if (messageRepo.getByMessageId(messageIdHex) != null) {
            _events.tryEmit(EngineEvent.Log(
                "duplicate opportunistic LXMF $messageIdHex — already stored, dropping",
            ))
            return
        }
        // Opportunistic single-packet LXMF in practice never carries an
        // image (the 360-byte MTU can't fit even a step-3 JPEG), but
        // extract anyway for symmetry with the link-delivered path —
        // a future tighter SDU or a non-standard peer could surprise
        // us. The 32 KB ceiling makes this safe regardless.
        val (imageBytes, imageRawSize) = extractImageField(msg.fields)
        if (imageBytes == null && imageRawSize > 0) {
            _events.tryEmit(EngineEvent.Log(
                "opportunistic msg from $sourceHashHex: image field ${imageRawSize} B > ${INBOUND_IMAGE_MAX_BYTES} B — dropped"
            ))
        }
        val savedId = messageRepo.save(StoredMessage(
            contactHash = sourceHashHex,
            direction = "incoming",
            content = msg.content,
            title = msg.title,
            timestamp = effectiveTimestamp,
            state = if (!isUnverified) "verified" else "unverified",
            attempts = 0,
            lastAttempt = 0,
            // Stash the LXMF plaintext on unverified rows so we can
            // re-run verifyMessageSignature once the sender's announce
            // (and identity) shows up, and flip "unverified" → "verified"
            // retroactively. The plaintext is already decrypted local
            // bytes — no extra secret storage.
            rawPacket = if (isUnverified) plaintext else null,
            rssi = rssi,
            hopCount = pkt.hops,
            imageBytes = imageBytes,
            messageId = messageIdHex,
            replyToMessageId = replyToMessageId,
            // v1.1.39 — opportunistic-path twin of the link path's
            // arrivedViaDest rule. fwdsvc's Delivery.Send tries
            // opportunistic first (internal/lxmf/delivery.go) and
            // falls through to a link only on ErrPayloadTooLarge —
            // typical "[BlueP] body" fanouts pack ~100-150 B which
            // is well under the 295 B opportunistic cap, so this is
            // the live path for fwdsvc-relayed reactions and replies.
            // Per the maintainer's rebroadcast model documentation,
            // source_hash IS the relay's destHash on the rebroadcast
            // (fwdsvc re-signs as itself); so storing source_hash
            // here yields the right routing destination at send time
            // for fwdsvc relays AND for direct 1:1 chats (where it
            // equals the conversation peer, making the override a
            // harmless no-op).
            arrivedViaDest = sourceHashHex,
        ))
        _events.tryEmit(EngineEvent.MessageReceived(
            messageId = savedId,
            contactHash = sourceHashHex,
            content = msg.content,
            verified = !isUnverified,
        ))

        // Active credential fetch: ask the network for this sender's
        // identity so the next message verifies, and so the announce
        // also drives the retroactive re-verify path on this message.
        // Dedup per session — one path request per unknown sender.
        if (isUnverified && pathRequestsSent.add(sourceHashHex)) {
            runCatching { requestPath(msg.sourceHash) }
                .onSuccess { _events.tryEmit(EngineEvent.Log("path? for unverified sender $sourceHashHex")) }
                .onFailure { _events.tryEmit(EngineEvent.Log("path? failed for $sourceHashHex: ${it.message}")) }
        }
    }

    /**
     * Emit a Reticulum delivery proof for an opportunistic DATA packet
     * we just decrypted. Wire format (implicit-proof default):
     *
     *   byte 0 : flags = 0x03 (HEADER_1 | DEST_SINGLE | PACKET_PROOF)
     *   byte 1 : hops = 0
     *   2..17  : truncHash[:16]   — the original DATA packet's full hash
     *   byte 18: context = 0x00 (NONE)
     *   19..82 : Ed25519 signature over the FULL 32-byte packet hash
     *
     * The dest_hash field is the original packet's truncated hash (NOT a
     * destination hash), which is how relays match the proof back to the
     * sender via Transport.reverse_table[truncHash]. Total: 83 bytes.
     */
    private suspend fun sendDeliveryProof(fullPacketHash: ByteArray, kind: TransportKind) {
        if (transports[kind] == null) return
        val id = ensureIdentity()
        val truncHash = fullPacketHash.copyOfRange(0, 16)
        val signature = id.sign(fullPacketHash)
        val proofPacket = buildPacket(
            headerType = HEADER_1,
            destType = DEST_SINGLE,
            packetType = PACKET_PROOF,
            destHash = truncHash,
            context = CTX_NONE,
            payload = signature,
        )
        sendOn(kind, proofPacket)
        _events.tryEmit(EngineEvent.Log("→ proof for ${truncHash.toHex()} on $kind"))
    }

    /** Returns true if [hashHex] was newly added (caller should treat
     *  the packet as fresh), false if we'd already seen it. Bounded
     *  LRU. */
    private fun rememberIncomingData(hashHex: String): Boolean {
        if (hashHex in seenIncomingDataHashes) return false
        seenIncomingDataHashes.add(hashHex)
        if (seenIncomingDataHashes.size > seenIncomingDataCap) {
            val it = seenIncomingDataHashes.iterator()
            it.next(); it.remove()
        }
        return true
    }

    /** Same shape as [rememberIncomingData] but for announce packets. */
    private fun rememberAnnounce(hashHex: String): Boolean {
        if (hashHex in seenAnnounceHashes) return false
        seenAnnounceHashes.add(hashHex)
        if (seenAnnounceHashes.size > seenAnnounceCap) {
            val it = seenAnnounceHashes.iterator()
            it.next(); it.remove()
        }
        return true
    }

    // ====================================================================
    //  Reticulum Relay Chat (RRC) — experimental, gated by the
    //  `experimentalRrc` preference. None of this is reachable from the UI
    //  until the Rooms screen lands; the methods fail fast when [rrcRepo]
    //  is null (the experimental flag off / tests).
    // ====================================================================

    /** A live RRC session: the protocol state machine plus the
     *  [LinkSession] it rides. Keyed in [rrcSessions] by hub destHash. */
    private class ActiveRrcSession(
        val hubDestHash: String,
        val linkIdHex: String,
        val linkSession: LinkSession,
        val rrcSession: RrcSession,
        val nick: String?,
    )

    /** Open RRC sessions, keyed by hub destination hash. Guarded by
     *  [sessionsLock] alongside [activeSessions]. */
    private val rrcSessions: MutableMap<String, ActiveRrcSession> = mutableMapOf()

    /** RrcEvent → RrcRepository bridge, built once when RRC storage is
     *  configured. Null mirrors [rrcRepo] being null. */
    private val rrcPersistence: RrcPersistence? by lazy {
        rrcRepo?.let { repo ->
            RrcPersistence(repo, nowMs) { line -> _events.tryEmit(EngineEvent.Log(line)) }
        }
    }

    /** Persist + surface one RRC event. [RrcSession.onEvent] is a plain
     *  (non-suspend) callback, so persistence is launched on [scope]. */
    private fun onRrcEvent(hubDestHash: String, event: RrcEvent) {
        _events.tryEmit(EngineEvent.RrcActivity(hubDestHash, event))
        // On every WELCOME (initial connect, reconnect, or re-HELLO),
        // re-JOIN the rooms we have persisted as joined — a fresh session
        // leaves the hub with no memory of our membership.
        if (event is RrcEvent.Welcomed) {
            scope.launch {
                runCatching { autoRejoinRooms(hubDestHash) }
                    .onFailure { _events.tryEmit(EngineEvent.Log("rrc auto-rejoin failed: ${it.message}")) }
            }
        }
        val persistence = rrcPersistence ?: return
        scope.launch {
            runCatching { persistence.onEvent(hubDestHash, event) }
                .onFailure { _events.tryEmit(EngineEvent.Log("rrc persist failed: ${it.message}")) }
        }
    }

    /**
     * Re-JOIN every room persisted as joined for [hubDestHash]. Run on
     * each WELCOME: a freshly (re)established session's hub does not know
     * which rooms we were in, so a persisted `joined=true` room would
     * otherwise display as joined while the hub never fans its messages
     * to us — the "leave then join to make it work" gap.
     */
    private suspend fun autoRejoinRooms(hubDestHash: String) {
        val repo = rrcRepo ?: return
        val active = sessionsLock.withLock { rrcSessions[hubDestHash] } ?: return
        val joined = repo.getRoomsForHub(hubDestHash).filter { it.joined }
        if (joined.isEmpty()) return
        for (room in joined) {
            runCatching { active.rrcSession.join(room.name) }
                .onFailure {
                    _events.tryEmit(EngineEvent.Log("auto-rejoin ${room.name} failed: ${it.message}"))
                }
        }
        _events.tryEmit(EngineEvent.Log("auto-rejoined ${joined.size} room(s) on $hubDestHash"))
    }

    /**
     * Open (or reuse) an RRC session to the hub at [hubDestHash]: build a
     * Reticulum Link, identify on it, then drive the HELLO→WELCOME
     * handshake. On success the session sits in [rrcSessions] and the
     * engine pump routes inbound CTX_NONE link DATA into it; the caller
     * watches [EngineEvent.RrcActivity] for the [RrcState.WELCOMED]
     * transition before calling [joinRrcRoom] / [sendRrcMessage].
     *
     * Link establishment mirrors [syncPropagation] — §2.3 HEADER_2
     * conversion for multi-hop hubs, affinity-routed LINKREQUEST,
     * adaptive proof timeout. RRC chat requires the hub to know our
     * verified identity (it rewrites the envelope `K_SRC` from the link
     * identity), so we always send a §6.6 LINKIDENTIFY after LRPROOF —
     * unlike NomadNet browsing, where identifying is an opt-in.
     */
    suspend fun openRrcSession(hubDestHash: String, nick: String? = null): Result<Unit> = runCatching {
        val repo = rrcRepo ?: error("RRC storage not configured")
        val dest = destinationRepo.get(hubDestHash) ?: error("Unknown destination $hubDestHash")
        require(dest.publicKey.size == 64) {
            "No public key for $hubDestHash yet — wait for an announce"
        }
        if (!hasAnyTransport()) error("No transport attached — connect on the Settings tab first")

        // Reuse an already-ACTIVE session rather than stacking a second link.
        val existing = sessionsLock.withLock { rrcSessions[hubDestHash] }
        if (existing != null &&
            existing.linkSession.link.state == io.github.thatsfguy.reticulum.link.LinkState.ACTIVE
        ) {
            _events.tryEmit(EngineEvent.Log("[rrc] reusing active session for $hubDestHash"))
            return@runCatching
        }

        val identity = ensureIdentity()
        val ourIdentityHash = identity.hash ?: error("identity has no hash")
        val proofTimeout = proofTimeoutForHops(dest.hopCount)

        // Make sure the hub has a persisted row so it shows in the
        // Rooms list even before WELCOME arrives. lastConnectedAt is
        // stamped later by RrcPersistence on the Welcomed event.
        if (repo.getHub(hubDestHash) == null) {
            repo.upsertHub(
                io.github.thatsfguy.reticulum.store.StoredRrcHub(
                    destHash = hubDestHash,
                    displayName = dest.effectiveDisplayName,
                    nick = nick,
                    addedAt = nowMs(),
                ),
            )
        }

        val targetSigPub = dest.publicKey.copyOfRange(32, 64)
        val (link, requestData) = Link.createInitiator(
            peerLongTermSigPub = targetSigPub,
            peerDestHash = dest.destHash,
            crypto = crypto,
            nowMs = nowMs(),
        )
        // §2.3 LINKREQ conversion — see fetchNomadPage for the full why.
        val useHeader2Lr = dest.hopCount > 1 && dest.nextHop != null
        val linkReqPacket = buildPacket(
            headerType = if (useHeader2Lr) HEADER_2 else HEADER_1,
            transportType = if (useHeader2Lr) TRANSPORT_TRANSPORT else TRANSPORT_BROADCAST,
            destType = DEST_SINGLE,
            packetType = PACKET_LINKREQ,
            destHash = dest.destHash,
            transportId = if (useHeader2Lr) dest.nextHop else null,
            payload = requestData,
        )
        val parsed = parsePacket(linkReqPacket) ?: error("self-parse failed")
        link.setLinkIdFromPacket(parsed)
        val linkIdHex = link.linkId!!.toHex()

        // Circular wiring: the LinkSession needs an onLinkData sink (the
        // RrcSession), and the RrcSession needs an RrcLink that sends
        // over the LinkSession. Break it with a captured `var`.
        var rrcSession: RrcSession? = null
        val linkSession = LinkSession(
            link = link,
            crypto = crypto,
            sender = { pkt -> sendForLink(linkIdHex, pkt) },
            nowMs = nowMs,
            logger = { line -> _events.tryEmit(EngineEvent.Log("[rrc $linkIdHex] $line")) },
            ourIdentity = identity,
            onLinkData = { frame -> rrcSession?.onInbound(frame) },
            onResourceData = { bytes -> rrcSession?.onResourcePayload(bytes) },
            onClosed = { reason ->
                // Stale-link teardown — surface it and flip the session
                // CLOSED so the UI stops showing a dead link as connected.
                onRrcEvent(hubDestHash, RrcEvent.HubError(null, "connection lost: $reason"))
                rrcSession?.close()
            },
        )
        val rrcLink = object : RrcLink {
            override suspend fun send(frame: ByteArray) = linkSession.sendData(frame)
            override fun close() {
                // The single RRC teardown path — RrcSession.close()
                // routes here. Idempotent map removes.
                scope.launch {
                    sessionsLock.withLock {
                        activeSessions.remove(linkIdHex)
                        rrcSessions.remove(hubDestHash)
                    }
                    runCatching { linkSession.dispose() }
                    _events.tryEmit(EngineEvent.Log("[rrc $linkIdHex] session closed"))
                }
            }
        }
        rrcSession = RrcSession(
            ourIdentityHash = ourIdentityHash,
            link = rrcLink,
            nowMs = nowMs,
            nick = nick,
            onEvent = { event -> onRrcEvent(hubDestHash, event) },
            logger = { line -> _events.tryEmit(EngineEvent.Log("[rrc $linkIdHex] $line")) },
            sha256 = { data -> crypto.sha256(data) },
        )

        sessionsLock.withLock { activeSessions[linkIdHex] = linkSession }
        try {
            primePath(
                destHash = dest.destHash,
                requestPath = { hash -> requestPath(hash) },
                delayMs = { ms -> delay(ms) },
                onPathFailure = { _events.tryEmit(EngineEvent.Log("[rrc $linkIdHex] path? failed: ${it.message}")) },
            )
            sendToDestination(hubDestHash, linkReqPacket)
            _events.tryEmit(EngineEvent.Log("rrc link → $hubDestHash (link_id=$linkIdHex)"))

            when (val proof = linkSession.awaitProof(proofTimeout)) {
                is LinkSession.ProofResult.Validated -> linkSession.startKeepalive(scope)
                is LinkSession.ProofResult.Invalid ->
                    error("LRPROOF rejected: ${proof.reason}. ${linkSession.diagnosticSummary()}")
                LinkSession.ProofResult.Timeout ->
                    error("no LRPROOF within ${proofTimeout / 1000}s — RRC hub may be down")
            }

            // §6.6 LINKIDENTIFY — RRC needs our verified identity on the
            // link so the hub can rewrite the envelope K_SRC.
            val identifyCipher = link.buildIdentifyPayload(identity)
            val identifyPacket = buildPacket(
                destType = io.github.thatsfguy.reticulum.protocol.DEST_LINK,
                packetType = PACKET_DATA,
                destHash = link.linkId!!,
                context = io.github.thatsfguy.reticulum.protocol.CTX_LINKIDENTIFY,
                payload = identifyCipher,
            )
            sendForLink(linkIdHex, identifyPacket)
            _events.tryEmit(EngineEvent.Log("[rrc $linkIdHex] → LINKIDENTIFY"))
            // Small settle so the hub binds our identity before HELLO.
            delay(250L)

            val active = ActiveRrcSession(hubDestHash, linkIdHex, linkSession, rrcSession, nick)
            sessionsLock.withLock { rrcSessions[hubDestHash] = active }
            rrcSession.start()  // sends HELLO; hub replies WELCOME
        } catch (e: Throwable) {
            sessionsLock.withLock {
                activeSessions.remove(linkIdHex)
                rrcSessions.remove(hubDestHash)
            }
            runCatching { linkSession.dispose() }
            throw e
        }
    }

    /** Look up a live RRC session or fail with a clear message. */
    private suspend fun requireRrcSession(hubDestHash: String): ActiveRrcSession =
        sessionsLock.withLock { rrcSessions[hubDestHash] }
            ?: error("No open RRC session for $hubDestHash — call openRrcSession first")

    /**
     * JOIN [room] on an open RRC session. Membership is persisted
     * optimistically (joined = true) so an auto-rejoin after a
     * reconnect knows which rooms to re-enter; a hub ERROR would be
     * surfaced via [EngineEvent.RrcActivity] for the UI to react to.
     */
    suspend fun joinRrcRoom(hubDestHash: String, room: String, key: String? = null) {
        val active = requireRrcSession(hubDestHash)
        active.rrcSession.join(room, key)
        rrcRepo?.upsertRoom(
            io.github.thatsfguy.reticulum.store.StoredRrcRoom(
                hubHash = hubDestHash, name = room, joined = true, lastActivityAt = nowMs(),
            ),
        )
    }

    /** PART [room]; membership is cleared in storage. */
    suspend fun partRrcRoom(hubDestHash: String, room: String) {
        val active = requireRrcSession(hubDestHash)
        active.rrcSession.part(room)
        rrcRepo?.setRoomJoined(hubDestHash, room, false)
    }

    /**
     * Send [text] to [room] over an open RRC session and persist the
     * outgoing row — [RrcSession] emits no event for our own sends.
     */
    suspend fun sendRrcMessage(hubDestHash: String, room: String, text: String) {
        val active = requireRrcSession(hubDestHash)
        // sendMessage returns the envelope K_ID; the outgoing row is
        // keyed on it so the hub's fan-out echo dedups against it
        // instead of showing the message a second time.
        val msgId = active.rrcSession.sendMessage(room, text)
        val identity = ensureIdentity()
        rrcPersistence?.recordOutgoing(
            hubHash = hubDestHash,
            room = room,
            senderIdHash = identity.hash ?: ByteArray(0),
            nick = active.nick,
            text = text,
            timestamp = nowMs(),
            msgId = msgId,
        )
    }

    /** Close an RRC session and tear its link down. Idempotent. */
    suspend fun closeRrcSession(hubDestHash: String) {
        val active = sessionsLock.withLock { rrcSessions[hubDestHash] } ?: return
        // RrcSession.close() → RrcLink.close() does the map removal +
        // link dispose (the single teardown path).
        runCatching { active.rrcSession.close() }
    }

    sealed class EngineEvent {
        data class Log(val line: String) : EngineEvent()
        data class MessagableSeen(val hash: String, val displayName: String, val rssi: Int?, val appName: String?) : EngineEvent()
        data class NodeSeen(val hash: String, val displayName: String, val rssi: Int?, val appName: String?) : EngineEvent()
        data class MessageReceived(
            val messageId: Long,
            val contactHash: String,
            val content: String,
            val verified: Boolean,
        ) : EngineEvent()
        /** An [RrcEvent] from a live RRC session, tagged with the hub
         *  destination hash it came from. The experimental Rooms UI
         *  observes these to drive its hub / room / chat views. */
        data class RrcActivity(
            val hubDestHash: String,
            val event: RrcEvent,
        ) : EngineEvent()
    }

    enum class TransportKind { Ble, BtClassic, Tcp, Usb }

    /** Connection state plus the wall-clock millis when [transport]
     *  last changed. UI uses changedAtMs to show "Connecting (12s)…"
     *  so the user can tell a slow-but-working connection apart from
     *  a wedged one. */
    data class ConnectionState(
        val transport: TransportState,
        val kind: TransportKind?,
        val changedAtMs: Long = 0L,
    )
}

/**
 * Maps a sender-claimed LXMF timestamp onto a value safe to use as the
 * chat sort key.
 *
 * Two failure modes get rewritten to our local arrival time [nowMs]:
 *   1. Pre-2020 — sender is a clockless RNode emitting seconds-since-boot
 *      (CLAUDE.md gotcha #4). Tiny values would sort at the very top of
 *      the conversation.
 *   2. Future-from-our-perspective — sender's wall clock runs ahead of
 *      ours. Without clamping, their message gets sorted *after* the
 *      reply the user just typed (which uses our [nowMs]), so the reply
 *      appears above the message it's replying to. Happens "occasionally"
 *      across mesh peers with variably-synced clocks.
 *
 * Reasonable-past values pass through so a working sender clock still
 * drives the displayed timestamp; ordering is only nudged when the sender
 * clock would otherwise corrupt the conversation flow.
 */
internal fun correctClocklessTimestamp(senderSeconds: Double, nowMs: Long): Long {
    val senderMs = (senderSeconds * 1000.0).toLong()
    if (senderMs < 1_577_836_800_000L) return nowMs
    if (senderMs > nowMs) return nowMs
    return senderMs
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun String.hexBytesOrThrow(label: String, expectedLen: Int): ByteArray {
    require(length == expectedLen * 2) { "$label must be $expectedLen bytes (${expectedLen * 2} hex chars), got $length" }
    val s = lowercase()
    return ByteArray(expectedLen) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
