package io.github.thatsfguy.reticulum.platform

import io.github.thatsfguy.reticulum.engine.IdentityCard
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.engine.RrcEvent
import io.github.thatsfguy.reticulum.rrc.RrcRoomListing
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * iOS-side construction site for a fully wired [ReticulumEngine].
 * Mirrors what [io.github.thatsfguy.reticulum.android.service.ReticulumService]
 * does on Android: assemble crypto + repos + scope, hand them to the
 * engine, and surface a way for the app to detach + cancel.
 *
 * Swift consumers grab the engine plus the [scope] via a single call:
 *
 * ```swift
 * let factory = IosEngineFactory()
 * let engine = factory.engine
 * // ...
 * factory.shutdown()  // cancels scope, detaches all transports
 * ```
 *
 * The displayName provider defaults to "Reticulum Mobile" for now —
 * Phase 3 follow-up will plumb a SwiftUI text field through.
 */
class IosEngineFactory(
    repositories: IosRepositories = IosRepositories.create(),
    crypto: IosCryptoProvider = IosCryptoProvider(),
    private val displayNameProvider: () -> String = { "Reticulum Mobile" },
    /** Drops inbound LXMF whose signature can't be verified against
     *  a known announce when this returns true. Wired to a SwiftUI
     *  @AppStorage toggle. Audit reference: 2026-05-13 MED-6. */
    private val dropUnverifiedProvider: () -> Boolean = { false },
) {
    /**
     * Catches every uncaught coroutine exception thrown by children
     * of [scope] so they don't terminate the iOS process. Without
     * this, Kotlin/Native's default unhandled-exception handler calls
     * `terminateWithUnhandledException` even on a [SupervisorJob]-
     * rooted scope — producing the "use → background → unlock →
     * crash" pattern the tester saw in v1.0.13: iOS invalidates a
     * CBPeripheral / closes a TCP socket while the app is suspended,
     * a coroutine wakes on resume, the next `peripheral.writeValue`
     * raises an NSException, K/N translates it to Throwable, and
     * with no handler the runtime kills the app.
     *
     * Lands the line on TWO surfaces: (1) `println` to the iOS device
     * console (Xcode "Devices and Simulators" → tester's iPhone →
     * Open Console), and (2) the engine's diagnostic event channel,
     * which surfaces in the in-app Settings → Diagnostics view. v1.0.14
     * shipped only the println half, which left silent failures
     * invisible to testers without Xcode access — biting us in the
     * "TCP connects but no announces" investigation 2026-05-10.
     * Wrapping the engine emit in runCatching so that an exception
     * during engine initialization (very early, before `engine` is
     * assigned) doesn't itself recur into the handler.
     */
    private val crashGuard = kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
        val line = "[uncaught] ${t::class.simpleName}: ${t.message}"
        println("[ReticulumEngine] $line")
        runCatching { engine.logExternal(line) }
    }

    /** Survives the app lifetime; cancelled by [shutdown]. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)

    val repos: IosRepositories = repositories

    /** Off-row attachment store (docs/ATTACHMENT-STORE.md), shared with
     *  [engine]. The SwiftUI bubble renderer reads it to load an image
     *  / file payload from its on-row token. */
    val attachmentStore: io.github.thatsfguy.reticulum.store.AttachmentStore =
        io.github.thatsfguy.reticulum.store.createIosAttachmentStore()

    val engine: ReticulumEngine = ReticulumEngine(
        crypto = crypto,
        identityRepo = repositories.identity,
        destinationRepo = repositories.destinations,
        messageRepo = repositories.messages,
        scope = scope,
        // Native has no java.lang.System.currentTimeMillis(); kotlinx-
        // datetime's Clock.System is the multiplatform equivalent.
        nowMs = { Clock.System.now().toEpochMilliseconds() },
        displayNameProvider = displayNameProvider,
        dropUnverifiedProvider = dropUnverifiedProvider,
        nomadPageCache = repositories.nomadPageCache,
        rrcRepo = repositories.rrc,
        attachmentStore = attachmentStore,
    )

    /** Detach every transport and cancel every coroutine the engine
     *  spawned. Callers should invoke from the SwiftUI scene's
     *  `onDisappear` or app-terminate handler. */
    fun shutdown() {
        engine.detach(null)
        scope.cancel()
    }
}

/**
 * Bridges a Kotlin [Flow] to a Swift-callable subscription. Returns a
 * [FlowSubscription] handle whose [FlowSubscription.cancel] terminates
 * the collection — Swift consumers store the handle and call cancel
 * on Combine subscription teardown / view dismissal.
 *
 * The [onEach] lambda is invoked on the [scope]'s default dispatcher;
 * Swift implementations must hop to the main actor before mutating
 * `@Published` state. (This sidesteps Kotlin/Native's
 * @MainActor-equivalent gymnastics by leaving the dispatch decision
 * to Swift.)
 */
class FlowSubscription internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

fun <T> Flow<T>.subscribe(scope: CoroutineScope, onEach: (T) -> Unit): FlowSubscription {
    val job = scope.launch {
        collect { value -> onEach(value) }
    }
    return FlowSubscription(job)
}

/**
 * Swift-friendly wrapper around [IdentityCard.decode] that returns null
 * on parse failure instead of throwing. The underlying decoder uses
 * Kotlin's [error] / [require] which translate to NSException on
 * Kotlin/Native — without this wrapper Swift sees an uncatchable
 * exception unless we annotate `decode` with `@Throws`, which would
 * leak iOS concerns into common code.
 *
 * The QR scanner uses this on every successful frame decode; nil
 * triggers an "unrecognised QR" toast, a non-nil result is forwarded
 * to [ReticulumEngine.applyIdentityCard].
 */
fun decodeIdentityCardOrNull(text: String): IdentityCard.Payload? =
    runCatching { IdentityCard.decode(text) }.getOrNull()

/**
 * Swift-friendly hex encoder. Kotlin's `ByteArray.toHex()` extension
 * (transport/Kiss.kt) is not callable as `KissKt.toHex(bytes)` — the
 * Kotlin/Native exporter mangles extension-function names in ways that
 * vary by compiler version. A plain top-level function is the
 * stable bridge.
 */
fun byteArrayToHex(bytes: ByteArray): String = bytes.toHex()

/**
 * Convert a [ReticulumEngine.EngineEvent] to a single user-visible log
 * line, or null for events that aren't worth surfacing in the UI
 * (MessagableSeen / NodeSeen are already covered by the destinations
 * list view; bubbling them into the log just adds noise).
 *
 * Doing the pattern-match in Kotlin keeps the Swift consumer
 * insensitive to how Kotlin/Native names sealed-class subtypes in the
 * generated Objective-C header — which has changed between K/N
 * compiler versions and is the kind of thing where guessing produces
 * a Swift cast that compiles but always fails at runtime.
 */
fun engineEventToLogLine(event: ReticulumEngine.EngineEvent): String? = when (event) {
    is ReticulumEngine.EngineEvent.Log -> event.line
    is ReticulumEngine.EngineEvent.MessageReceived ->
        "msg #${event.messageId} from ${event.contactHash} verified=${event.verified}"
    else -> null
}

/**
 * Swift-friendly POJO for an incoming-message engine event. Avoids
 * having Swift consumers cast through the K/N-mangled sealed-class
 * subtype names (which change between Kotlin compiler versions and
 * have produced silent-fail Swift casts before — see the
 * NomadFetchResult-vs-Kotlin-Result comment above).
 *
 * iOS uses this to decide whether to post a UNNotificationRequest
 * for a freshly-received LXMF message while the app is backgrounded.
 */
data class IncomingMessageInfo(
    val messageId: Long,
    val contactHash: String,
    val content: String,
    val verified: Boolean,
)

/** Returns non-null for MessageReceived events, null otherwise. */
fun engineEventAsIncomingMessage(event: ReticulumEngine.EngineEvent): IncomingMessageInfo? =
    (event as? ReticulumEngine.EngineEvent.MessageReceived)?.let {
        IncomingMessageInfo(
            messageId = it.messageId,
            contactHash = it.contactHash,
            content = it.content,
            verified = it.verified,
        )
    }

/**
 * Swift-friendly POJO for a TCP transport node entry from
 * [io.github.thatsfguy.reticulum.transport.KnownTcpNodes.DEFAULTS].
 * The Kotlin definition uses `Pair<String, Int>` which K/N exports
 * to Swift as the opaque KotlinPair class — flat fields are
 * easier for SwiftUI consumers to bind.
 */
data class TcpNode(val host: String, val port: Int)

/** Snapshot of the curated rotation of public TCP transport nodes
 *  (verified-reachable list maintained in commonMain). UI uses this
 *  for the "Pick another" suggestion + the first-launch random pick. */
fun knownTcpNodes(): List<TcpNode> =
    io.github.thatsfguy.reticulum.transport.KnownTcpNodes.DEFAULTS
        .map { TcpNode(host = it.first, port = it.second) }

/**
 * The image-attachment resolution tiers, in display order, for the
 * iOS compose-row "+" → Photo chooser. Returning the list from Kotlin
 * lets the Swift side iterate `tier.label` / `tier.byteBudget` and
 * pass each `ImageResolutionTier` straight back to the compressor
 * without naming the Kotlin/Native-bridged enum's entries.
 */
fun imageResolutionTiers(): List<io.github.thatsfguy.reticulum.engine.ImageResolutionTier> =
    io.github.thatsfguy.reticulum.engine.ImageResolutionTier.entries

/** Pick one entry at uniform random — for a fresh-install seed. */
fun pickRandomTcpNode(): TcpNode =
    io.github.thatsfguy.reticulum.transport.KnownTcpNodes.pickRandom()
        .let { TcpNode(host = it.first, port = it.second) }

/** Pick a different entry from the user's [currentHost]/[currentPort].
 *  Falls back to a plain random pick if the current value isn't in
 *  the curated rotation (e.g. user typed a custom host). */
fun pickDifferentTcpNode(currentHost: String, currentPort: Int): TcpNode {
    val current = currentHost to currentPort
    val pick = io.github.thatsfguy.reticulum.transport.KnownTcpNodes.pickDifferentThan(current)
    return TcpNode(host = pick.first, port = pick.second)
}

/**
 * IosEngineFactory zero-arg constructor proxy. Kotlin default-argument
 * constructors don't generate a Swift-visible no-arg `init()` —
 * Swift sees `init()` as 'unavailable'. This factory function gives
 * Swift the equivalent of `IosEngineFactory()` from Kotlin.
 */
fun createIosEngineFactory(): IosEngineFactory = IosEngineFactory()

/**
 * IosEngineFactory constructor proxy that takes a Swift-supplied
 * display-name provider. The provider is invoked every time the
 * engine builds an outbound announce; iOS reads UserDefaults under
 * the "displayName" key so a Settings → Display name field's edits
 * land in the next announce without having to restart the engine.
 *
 * Mirrors what Android's `ReticulumService` does with
 * `preferences.getDisplayName()`.
 */
fun createIosEngineFactoryWithDisplayName(
    displayName: () -> String,
): IosEngineFactory = IosEngineFactory(displayNameProvider = displayName)

/**
 * Convenience factory that exposes both the displayName provider and
 * the drop-unverified toggle (MED-6) to Swift. Mirrors Android's
 * `ReticulumService` initialization, which wires both providers from
 * its `Preferences` object. Audit reference: 2026-05-13 MED-6.
 */
fun createIosEngineFactoryWithProviders(
    displayName: () -> String,
    dropUnverified: () -> Boolean,
): IosEngineFactory = IosEngineFactory(
    displayNameProvider = displayName,
    dropUnverifiedProvider = dropUnverified,
)

/**
 * Result wrapper for [fetchNomadPageBridge]. Kotlin's stdlib `Result<T>`
 * is an inline value class which does not bridge to Swift — the
 * Kotlin/Native exporter surfaces it as opaque `Any?`. This data class
 * is a regular Swift-friendly POJO: success carries the page source,
 * failure carries the error message string.
 */
data class NomadFetchResult(
    val source: String?,
    val errorMessage: String?,
) {
    val isSuccess: Boolean get() = source != null
}

/**
 * iOS-side wrapper around [ReticulumEngine.fetchNomadPage] that
 * converts the inline `Result<String>` to a Swift-readable
 * [NomadFetchResult]. SwiftUI uses this directly — the engine's
 * native return type would force `Any?`-casting on every call site.
 *
 * Written as a regular top-level function (not an extension) because
 * Kotlin/Native exports suspend extensions on Kotlin types as
 * mangled member methods on the receiver class rather than the
 * file's "Kt" static, which makes the Swift call site unreliable
 * across Kotlin compiler versions.
 */
suspend fun fetchNomadPageBridge(
    engine: ReticulumEngine,
    destinationHash: String,
    path: String,
    identify: Boolean,
): NomadFetchResult {
    val r = engine.fetchNomadPage(
        destinationHash = destinationHash,
        path = path,
        data = null,
        identify = identify,
    )
    return r.fold(
        onSuccess = { NomadFetchResult(source = it, errorMessage = null) },
        onFailure = { NomadFetchResult(source = null, errorMessage = it.message ?: "Unknown error") },
    )
}

/**
 * Form-submit variant of [fetchNomadPageBridge]. Swift passes a
 * `[String: String]` of `field_<name>` / `var_<k>` entries; we forward
 * as the engine's `data` envelope element. The engine msgpack-encodes
 * the dict so the upstream Node.py:109 sees the same shape Android's
 * MicronView form-link tap sends. Same NomadFetchResult shape — Swift
 * doesn't need to know it's a different code path.
 *
 * Empty map is treated like a plain GET (engine.fetchNomadPage data
 * arg defaults to `null` for omitted-data semantics; sending an empty
 * map upstream would still reach Node.py as `data = {}` which is
 * subtly different from "no data" — Browser.py drops empty before
 * sending). The Swift caller can just call [fetchNomadPageBridge]
 * directly when it has nothing to submit.
 */
suspend fun fetchNomadPageWithDataBridge(
    engine: ReticulumEngine,
    destinationHash: String,
    path: String,
    identify: Boolean,
    data: Map<String, String>,
): NomadFetchResult {
    val r = engine.fetchNomadPage(
        destinationHash = destinationHash,
        path = path,
        data = if (data.isEmpty()) null else data,
        identify = identify,
    )
    return r.fold(
        onSuccess = { NomadFetchResult(source = it, errorMessage = null) },
        onFailure = { NomadFetchResult(source = null, errorMessage = it.message ?: "Unknown error") },
    )
}

/**
 * Result wrapper for [fetchNomadFileBridge]. Same rationale as
 * [NomadFetchResult] — Kotlin's `Result<T>` doesn't bridge cleanly
 * to Swift. Success carries the file bytes + server-supplied
 * filename; failure carries the error message.
 *
 * The `bytes` field is a Kotlin `ByteArray` which Kotlin/Native
 * exports to Swift as `KotlinByteArray`. The Swift caller copies
 * byte-by-byte into a `Data` (same pattern as identity export /
 * image-attach paths) before writing to a UIDocumentPicker-chosen
 * destination.
 */
data class NomadFileFetchResult(
    val filename: String?,
    val bytes: ByteArray?,
    val errorMessage: String?,
) {
    val isSuccess: Boolean get() = bytes != null && filename != null
}

/**
 * iOS-side wrapper around [ReticulumEngine.fetchNomadFile]. Mirrors
 * [fetchNomadPageBridge]'s pattern. The Swift caller checks
 * isSuccess, copies bytes into Data, and presents
 * UIDocumentPickerViewController in export mode with `filename` as
 * the suggested name.
 */
suspend fun fetchNomadFileBridge(
    engine: ReticulumEngine,
    destinationHash: String,
    path: String,
    identify: Boolean,
): NomadFileFetchResult {
    val r = engine.fetchNomadFile(
        destinationHash = destinationHash,
        path = path,
        identify = identify,
    )
    return r.fold(
        onSuccess = { NomadFileFetchResult(filename = it.filename, bytes = it.bytes, errorMessage = null) },
        onFailure = { NomadFileFetchResult(filename = null, bytes = null, errorMessage = it.message ?: "Unknown error") },
    )
}

// ---- Reticulum Relay Chat (RRC) bridge --------------------------------
//
// RRC surfaces to the engine event stream as
// `EngineEvent.RrcActivity(hubDestHash, RrcEvent)`. Rather than have the
// Swift store cast through TWO levels of Kotlin/Native-mangled sealed
// classes (EngineEvent + RrcEvent), the projection below flattens an
// RrcActivity into one POJO with a `kind` discriminator and nullable
// fields — the same approach as `engineEventAsIncomingMessage`. The
// Swift store calls `engineEventAsRrcActivity` on every event; non-null
// results carry everything an RRC view needs.

/**
 * Flattened, Swift-friendly projection of an
 * `EngineEvent.RrcActivity`. [kind] is the discriminator the Swift
 * side switches on; the remaining fields are populated per kind:
 *
 *  - "state"       → [stateName]   (RrcState: CONNECTING / WELCOMED / CLOSED)
 *  - "welcomed"    → [hubName], [maxMsgBodyBytes]
 *  - "roomMessage" → [room], [text], [senderIdHash], [nick], [timestampMs], [msgIdHex]
 *  - "notice"      → [room] (nullable), [text]
 *  - "error"       → [room] (nullable), [text]
 *  - "joined"      → [room], [memberCount]
 *  - "parted"      → [room], [memberCount]
 *  - "roomTopic"   → [room], [topic] (null = topic cleared)
 *  - "roomModes"   → [room], [modes] ("" = no modes)
 *  - "roomList"    → [rooms]
 */
data class RrcActivityInfo(
    val hubDestHash: String,
    val kind: String,
    val stateName: String? = null,
    val hubName: String? = null,
    val maxMsgBodyBytes: Int? = null,
    val room: String? = null,
    val text: String? = null,
    val senderIdHash: String? = null,
    val nick: String? = null,
    val timestampMs: Long? = null,
    val msgIdHex: String? = null,
    val memberCount: Int? = null,
    val topic: String? = null,
    val modes: String? = null,
    val rooms: List<RrcRoomListing>? = null,
)

/**
 * Returns a flattened [RrcActivityInfo] for an `EngineEvent.RrcActivity`,
 * or null for any other engine event. The Swift store calls this on
 * each `engine.events` emission.
 */
fun engineEventAsRrcActivity(event: ReticulumEngine.EngineEvent): RrcActivityInfo? {
    val activity = event as? ReticulumEngine.EngineEvent.RrcActivity ?: return null
    val hub = activity.hubDestHash
    return when (val e = activity.event) {
        is RrcEvent.StateChanged ->
            RrcActivityInfo(hub, "state", stateName = e.state.name)
        is RrcEvent.Welcomed ->
            RrcActivityInfo(hub, "welcomed", hubName = e.hubName, maxMsgBodyBytes = e.limits.maxMsgBodyBytes)
        is RrcEvent.RoomMessage ->
            RrcActivityInfo(
                hub, "roomMessage",
                room = e.room, text = e.text,
                senderIdHash = e.senderIdHash.toHex(), nick = e.nick,
                timestampMs = e.timestampMs, msgIdHex = e.msgId.toHex(),
            )
        is RrcEvent.Notice -> RrcActivityInfo(hub, "notice", room = e.room, text = e.text)
        is RrcEvent.HubError -> RrcActivityInfo(hub, "error", room = e.room, text = e.text)
        is RrcEvent.Joined -> RrcActivityInfo(hub, "joined", room = e.room, memberCount = e.members.size)
        is RrcEvent.Parted -> RrcActivityInfo(hub, "parted", room = e.room, memberCount = e.members.size)
        is RrcEvent.RoomTopic -> RrcActivityInfo(hub, "roomTopic", room = e.room, topic = e.topic)
        is RrcEvent.RoomModes -> RrcActivityInfo(hub, "roomModes", room = e.room, modes = e.modes)
        is RrcEvent.RoomList -> RrcActivityInfo(hub, "roomList", rooms = e.rooms)
        // Persisted as a system-direction row by RrcPersistence and
        // observed via the room message flow — no volatile state to fold.
        is RrcEvent.RoomSystemMessage ->
            RrcActivityInfo(hub, "roomSystemMessage", room = e.room, text = e.text)
    }
}

/**
 * iOS-side wrapper around [ReticulumEngine.openRrcSession]. The engine
 * returns a Kotlin `Result<Unit>`, which doesn't bridge to Swift —
 * same rationale as [fetchNomadPageBridge]. Returns null on success,
 * or the error message string on failure.
 */
suspend fun openRrcSessionBridge(
    engine: ReticulumEngine,
    hubDestHash: String,
    nick: String?,
): String? {
    val r = engine.openRrcSession(hubDestHash = hubDestHash, nick = nick)
    return r.fold(
        onSuccess = { null },
        onFailure = { it.message ?: "Failed to open RRC session" },
    )
}
