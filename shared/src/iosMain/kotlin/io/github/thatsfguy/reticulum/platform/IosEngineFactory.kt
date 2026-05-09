package io.github.thatsfguy.reticulum.platform

import io.github.thatsfguy.reticulum.engine.IdentityCard
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
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
) {
    /** Survives the app lifetime; cancelled by [shutdown]. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repos: IosRepositories = repositories

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
        nomadPageCache = repositories.nomadPageCache,
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
