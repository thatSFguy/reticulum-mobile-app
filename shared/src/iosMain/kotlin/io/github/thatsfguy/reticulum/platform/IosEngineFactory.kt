package io.github.thatsfguy.reticulum.platform

import io.github.thatsfguy.reticulum.engine.IdentityCard
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
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
