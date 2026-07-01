@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed file; also holds file-level helpers

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_la_context_create
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_la_context_release
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_la_evaluate
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/*
 * Apple LocalAuthentication authenticator for hardware-backed keys.
 *
 * The prompt host that puts a real OS prompt on screen (Touch ID / Face ID / device passcode via
 * LocalAuthentication). The UI-facing pieces — the localized reason string, the long-lived
 * LAContext whose successful evaluation authorizes Secure Enclave signs without re-prompting —
 * cannot be expressed in common code, so the app constructs this at the platform boundary and
 * hands it to [userAuthenticated] to obtain a [UserAuthenticatedKeyProvider]. Also usable as a
 * plain advisory [HardwareAuthorization] gate.
 *
 * The LAContext lives behind the CryptoKit shim as an opaque handle (LocalAuthentication does not
 * exist on every Apple platform — no tvOS — and routing through the shim keeps this file compiling
 * for every apple target; on an unsupported platform [available] is `false`, so
 * [userAuthenticated] returns `null` and no bound key can be requested at all).
 *
 * Lifecycle: the authenticator owns one native LAContext; [close] releases it and flips
 * [available] to `false`. A closed (or never-usable) authenticator denies everything — a session
 * sign on a closed authenticator fails cleanly ([AuthorizationFailed]) rather than passing a
 * released handle to the shim.
 */
class LocalAuthAuthenticator(
    /** User-facing reason shown in the OS authentication prompt. */
    private val reason: String,
) : HardwareAuthorization,
    AutoCloseable {
    /**
     * Opaque shim handle for the long-lived LAContext (session use); `0` when LocalAuthentication
     * is unavailable on this platform.
     */
    internal val contextHandle: Long = newContextHandle()

    /** Flipped by [close]; `@Volatile` so a session sign on another thread observes the release. */
    @Volatile
    private var closed: Boolean = false

    /**
     * `true` when LocalAuthentication exists on this platform and [close] has not been called.
     * Reports `false` once closed, so [userAuthenticated] refuses a closed authenticator and an
     * in-flight session sign fails cleanly instead of signing through a released handle.
     */
    internal val available: Boolean get() = contextHandle != 0L && !closed

    /** Plain gate use (advisory / session unlock): biometric or device credential. */
    override suspend fun authorize(): Boolean = evaluate(UserAuthenticationMethod.BiometricOrCredential)

    /**
     * Prompts the user via LocalAuthentication (off the calling thread — the shim call blocks
     * until the user responds). On success the underlying LAContext is *evaluated* and will
     * authorize Enclave signs without re-prompting until [close].
     */
    internal suspend fun evaluate(method: UserAuthenticationMethod): Boolean {
        if (!available) return false
        return withContext(Dispatchers.Default) {
            bcks_la_evaluate(contextHandle, method.laMethod(), interactionAllowed = 1) == BCKS_OK
        }
    }

    /**
     * A fresh, *un-evaluated* context carrying [reason], for per-use signs (the Enclave prompts
     * during the sign itself). Caller must release it with [releaseContextHandle].
     */
    internal fun newContextHandle(): Long = bcks_la_context_create(reason)

    override fun close() {
        closed = true
        releaseContextHandle(contextHandle)
    }
}

/** [UserAuthenticationMethod] → the shim's LAPolicy selector (1 = with credential, 2 = biometric only). */
internal fun UserAuthenticationMethod.laMethod(): Int =
    when (this) {
        UserAuthenticationMethod.BiometricOrCredential -> 1
        UserAuthenticationMethod.BiometricOnly -> 2
    }

/** Releases a shim LAContext handle (idempotent; `0` is a no-op). */
internal fun releaseContextHandle(handle: Long) {
    if (handle != 0L) bcks_la_context_release(handle)
}
