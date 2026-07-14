@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_AUTH
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INPUT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_secure_enclave_available
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_secure_enclave_p256_generate
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_secure_enclave_p256_generate_ac
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_secure_enclave_p256_sign_ctx
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.posix.size_tVar
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/*
 * Apple Secure Enclave hardware-backed key provider (CryptoKit `SecureEnclave.P256.Signing`, via the
 * CryptoKitShim).
 *
 * The Enclave generates and holds a P-256 private key that never leaves the element; signing routes
 * through the shim, which reconstructs the key from its opaque encrypted blob and signs *inside* the
 * Enclave (DER output, matching [ecdsaSignatureEncoding] == Der and the JVM/Android contract). The
 * public key the Enclave produced is captured into the returned [SigningKey] so the verifier can be
 * published. All byte transport is buffer-native: the shim reads from / writes into buffer memory
 * directly ([withRemainingBytes] / [withWritablePointer]); no ByteArray round-trips.
 *
 * **Only ECDSA P-256 is backed.** AES-GCM is not eligible: CryptoKit exposes no symmetric Secure
 * Enclave key, and the only "Enclave-tied" AES one could build (ECDH a P-256 Enclave key → derive a
 * symmetric key → run AES.GCM in software) would put the AES key in process memory, violating the
 * non-exportable hardware-key contract. (The Enclave *does* contain an AES engine, but Apple exposes
 * no developer API to drive it as an app-controlled non-exportable AEAD key.)
 *
 * **User authentication** is a [ResolvedApplePolicy], fixed at generation — each variant carries
 * exactly the authenticator its sign path needs, so a policy without its authenticator is
 * unrepresentable (no nullable state downstream):
 *  - [ResolvedApplePolicy.Advisory] — the base provider's keys: no `SecAccessControl`; the
 *    [HardwareAuthorization] gate is advisory, evaluated before every sign (the pre-biometrics
 *    behavior).
 *  - [ResolvedApplePolicy.Session] — via [userAuthenticated]: the key is generated with
 *    `SecAccessControl(privateKeyUsage + userPresence/biometryCurrentSet)`, so the *Enclave*
 *    refuses an unauthenticated sign. Signs go through the [LocalAuthAuthenticator]'s long-lived
 *    LAContext; the provider prompts (evaluates the context) lazily when the library-tracked
 *    validity window is stale — the Enclave has no timed window of its own, so the *window* is
 *    library-enforced while the *authentication itself* stays OS-enforced. If the OS invalidates
 *    the context mid-window, the sign re-prompts once (see [sessionSign]) rather than failing for
 *    the rest of the window; a closed authenticator is refused with [AuthorizationFailed].
 *  - [ResolvedApplePolicy.PerUse] — via [userAuthenticated]: same `SecAccessControl` binding, but
 *    every sign uses a fresh, un-evaluated LAContext, so the OS prompts for that exact operation.
 *
 * [userAuthenticated] captures the [LocalAuthAuthenticator] (prompt reason + LAContext owner) at
 * the platform boundary and returns `null` where LocalAuthentication cannot be driven (tvOS), so
 * no bound-key request ever reaches this provider without a usable prompt host.
 */
internal class SecureEnclaveHardwareKeyProvider : HardwareKeyProvider {
    override val dedicatedSecureElement: Boolean get() = true

    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean = alg == ProtectedKeyAlgorithm.EcdsaP256

    override suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey =
        // No app-controlled non-exportable symmetric Enclave key exists on Apple.
        throw HardwareKeyException.AlgorithmNotEligible()

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey = generateSigningBound(ResolvedApplePolicy.Advisory(spec.authorization), scheme)

    override suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair =
        // The Secure Enclave backs no app-controlled ECDH agreement key through this SPI; not eligible.
        throw HardwareKeyException.AlgorithmNotEligible()

    /** Generates an Enclave P-256 key under [policy] — the shared path for unbound and OS-bound keys. */
    internal suspend fun generateSigningBound(
        policy: ResolvedApplePolicy,
        scheme: SignatureScheme,
    ): SigningKey {
        if (!eligible(scheme.toProtectedKeyAlgorithm())) throw HardwareKeyException.AlgorithmNotEligible()
        val generated =
            when (policy) {
                is ResolvedApplePolicy.Advisory -> enclaveGenerateP256()
                is ResolvedApplePolicy.Session -> enclaveGenerateP256AccessControlled(policy.method)
                is ResolvedApplePolicy.PerUse -> enclaveGenerateP256AccessControlled(policy.method)
            }
        return ProtectedSigningKey(
            scheme = SignatureScheme.EcdsaP256,
            custody = custody,
            gatedSign = gatedSign(policy, generated.blob),
            verifyKey = VerifyKey.ecdsaP256(generated.point),
        )
    }

    private fun gatedSign(
        policy: ResolvedApplePolicy,
        blob: PlatformBuffer,
    ): HardwareSign =
        when (policy) {
            is ResolvedApplePolicy.Advisory -> advisorySign(policy.gate, blob)
            is ResolvedApplePolicy.Session -> sessionSign(policy, blob)
            is ResolvedApplePolicy.PerUse -> perUseSign(policy.authenticator, blob)
        }

    private fun advisorySign(
        gate: HardwareAuthorization,
        blob: PlatformBuffer,
    ): HardwareSign =
        { message, factory ->
            if (!gate.authorize()) throw AuthorizationFailed()
            // No SecAccessControl on this key, so an auth-denied status is unreachable; surface
            // the impossible as the closest structured outcome rather than a silent success.
            enclaveSignP256Into(blob, laHandle = 0L, message, factory)
                ?: throw AuthorizationFailed()
        }

    private fun sessionSign(
        policy: ResolvedApplePolicy.Session,
        blob: PlatformBuffer,
    ): HardwareSign {
        val authenticator = policy.authenticator
        val window = SessionWindow(policy.validity, policy.method, authenticator)
        return { message, factory ->
            // A closed authenticator has released its LAContext; refuse before handing the shim a
            // stale handle (which would otherwise misreport as KeyInvalidated).
            if (!authenticator.available) throw AuthorizationFailed()

            window.ensureAuthenticated()
            val first =
                withContext(Dispatchers.Default) {
                    enclaveSignP256Into(blob, authenticator.contextHandle, message, factory)
                }
            if (first != null) {
                first
            } else {
                // The OS refused the sign on a window we believed fresh — the LAContext was
                // invalidated out from under us (backgrounding, device lock, biometry change,
                // reuse-duration expiry). Reset the window, re-prompt exactly once, and retry;
                // only a second refusal is the terminal "authenticate and retry" state. Without
                // this the fresh window would suppress every re-prompt for the whole validity
                // period — a self-inflicted lockout.
                window.invalidate()
                window.ensureAuthenticated()
                withContext(Dispatchers.Default) {
                    enclaveSignP256Into(blob, authenticator.contextHandle, message, factory)
                } ?: throw HardwareKeyException.UserAuthenticationRequired()
            }
        }
    }

    private fun perUseSign(
        authenticator: LocalAuthAuthenticator,
        blob: PlatformBuffer,
    ): HardwareSign =
        { message, factory ->
            // A fresh, un-evaluated context per op: the OS prompts during the sign itself,
            // binding the authentication to exactly this operation. Blocking prompt ⇒ off the
            // calling thread.
            val fresh = authenticator.newContextHandle()
            try {
                withContext(Dispatchers.Default) {
                    enclaveSignP256Into(blob, fresh, message, factory)
                } ?: throw AuthorizationFailed()
            } finally {
                releaseContextHandle(fresh)
            }
        }

    // --- persistent alias lifecycle (drives AppleKeychainKeyStore) ---------------------------------
    //
    // A persistent Enclave key's private scalar never leaves the element; what the Keychain durably
    // holds is the framed (public point + restore blob), an opaque store record — never a private
    // key. The store owns Keychain IO; this provider owns generation, framing, and re-attachment, so
    // the record layout stays in one place. A persistent key's advisory gate is [spec]'s
    // authorization; its onClose is a no-op (the Keychain item survives close, and the Enclave key is
    // deleted only when the item is).

    /**
     * Generates a persistent Enclave P-256 signing key. Returns the [SigningKey] plus its durable
     * [PlatformBuffer] record (public point + restore blob) for the store to persist under an alias.
     */
    internal fun generatePersistentSigning(spec: ProtectedKeySpec): Pair<SigningKey, PlatformBuffer> {
        val generated = enclaveGenerateP256()
        val record = frameEnclaveRecord(generated.point, generated.blob)
        val key = buildPersistentSigning(generated.blob, generated.point, spec)
        return key to record
    }

    /** Re-attaches a persistent Enclave signing key from a durable [record] (see [frameEnclaveRecord]). */
    internal fun signingFromRecord(
        record: ReadBuffer,
        spec: ProtectedKeySpec,
    ): SigningKey {
        val (blob, point) = parseEnclaveRecord(record)
        return buildPersistentSigning(blob, point, spec)
    }

    /** Wraps an Enclave restore [blob] + public [point] as an advisory-gated persistent [SigningKey]. */
    private fun buildPersistentSigning(
        blob: PlatformBuffer,
        point: ReadBuffer,
        spec: ProtectedKeySpec,
    ): SigningKey =
        ProtectedSigningKey(
            scheme = SignatureScheme.EcdsaP256,
            custody = custody,
            gatedSign = advisorySign(spec.authorization, blob),
            verifyKey = VerifyKey.ecdsaP256(point),
        )
}

/**
 * The key's auth behavior, fixed at generation. Each variant carries exactly what its sign path
 * needs — [Session]/[PerUse] hold the (available) [LocalAuthAuthenticator] the
 * [UserAuthenticatedKeyProvider] captured at construction, so "OS-bound key without an
 * authenticator" is unrepresentable.
 */
internal sealed interface ResolvedApplePolicy {
    /** No `SecAccessControl`; [gate] is the advisory [ProtectedKeySpec.authorization]. */
    class Advisory(
        val gate: HardwareAuthorization,
    ) : ResolvedApplePolicy

    class Session(
        val validity: Duration,
        val method: UserAuthenticationMethod,
        val authenticator: LocalAuthAuthenticator,
    ) : ResolvedApplePolicy

    class PerUse(
        val method: UserAuthenticationMethod,
        val authenticator: LocalAuthAuthenticator,
    ) : ResolvedApplePolicy
}

/**
 * The Apple [UserAuthenticatedKeyProvider]: pairs the Enclave provider with the
 * [LocalAuthAuthenticator] captured at construction, and maps each pure-data
 * [UserAuthenticationPolicy] onto a [ResolvedApplePolicy] that carries it.
 */
internal class AppleUserAuthenticatedKeyProvider(
    private val base: SecureEnclaveHardwareKeyProvider,
    private val authenticator: LocalAuthAuthenticator,
) : UserAuthenticatedKeyProvider {
    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean = base.eligible(alg)

    override suspend fun generateAesGcm(
        policy: UserAuthenticationPolicy,
        aesKeySizeBits: Int,
    ): AesGcmKey = throw HardwareKeyException.AlgorithmNotEligible()

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        policy: UserAuthenticationPolicy,
    ): SigningKey = base.generateSigningBound(policy.resolve(), scheme)

    private fun UserAuthenticationPolicy.resolve(): ResolvedApplePolicy =
        when (this) {
            is UserAuthenticationPolicy.Session -> ResolvedApplePolicy.Session(validity, method, authenticator)
            is UserAuthenticationPolicy.PerUse -> ResolvedApplePolicy.PerUse(method, authenticator)
        }
}

/**
 * Binds this provider to a [LocalAuthAuthenticator], unlocking generation of Enclave keys the
 * *element itself* refuses without user authentication (`SecAccessControl`;
 * [UserAuthenticationPolicy.Session] / [UserAuthenticationPolicy.PerUse]). The authenticator is
 * captured here — at the platform boundary, where its prompt reason already had to be written —
 * so the policy values stay pure data and a bound key without a prompt host is a compile error,
 * not a runtime state.
 *
 * Returns `null` for a provider this platform did not mint (e.g. a test fake) and on platforms
 * where LocalAuthentication cannot be driven (tvOS, or a closed authenticator).
 */
fun HardwareKeyProvider.userAuthenticated(prompt: LocalAuthAuthenticator): UserAuthenticatedKeyProvider? =
    (this as? SecureEnclaveHardwareKeyProvider)
        ?.takeIf { prompt.available }
        ?.let { AppleUserAuthenticatedKeyProvider(it, prompt) }

/**
 * Library-enforced session validity window (the Enclave has no timed window of its own). Within
 * the window the already-evaluated LAContext signs silently; past it, the authenticator
 * re-prompts. The lock serializes only the window check + prompt, never the sign.
 */
private class SessionWindow(
    private val validity: Duration,
    private val method: UserAuthenticationMethod,
    private val authenticator: LocalAuthAuthenticator,
) {
    private val lock = Mutex()
    private var lastAuth: TimeMark? = null

    suspend fun ensureAuthenticated() {
        lock.withLock {
            val withinWindow = lastAuth?.let { it.elapsedNow() < validity } == true
            if (!withinWindow) {
                if (!authenticator.evaluate(method)) throw AuthorizationFailed()
                lastAuth = TimeSource.Monotonic.markNow()
            }
        }
    }

    /**
     * Drops the current window so the next [ensureAuthenticated] re-prompts. Called after the OS
     * refuses a sign the window believed authorized — concurrent stale signs coalesce, since the
     * next [ensureAuthenticated] to win the lock re-authenticates and the rest see the new window.
     */
    suspend fun invalidate() {
        lock.withLock { lastAuth = null }
    }
}

/** A freshly generated Enclave key: the opaque restore [blob] and its uncompressed SEC1 [point]. */
private class EnclaveP256Key(
    val blob: PlatformBuffer,
    val point: PlatformBuffer,
)

// A Secure Enclave P-256 signing key's `dataRepresentation` is ~284 bytes (observed); 1024 gives
// comfortable margin against OS-version variation. Too-small a buffer makes generate return
// BCKS_ERR_BUFFER, which would make the provider silently fail to resolve — so keep this generous.
private const val ENCLAVE_BLOB_CAP = 1024
private const val P256_POINT_BYTES = 65

private fun enclaveGenerateP256(): EnclaveP256Key =
    enclaveGenerate { blobPtr, blobCap, blobLen, pointPtr, pointCap, pointLen ->
        bcks_secure_enclave_p256_generate(blobPtr, blobCap, blobLen, pointPtr, pointCap, pointLen)
    }

private fun enclaveGenerateP256AccessControlled(method: UserAuthenticationMethod): EnclaveP256Key =
    enclaveGenerate { blobPtr, blobCap, blobLen, pointPtr, pointCap, pointLen ->
        // The shim's authReq selector matches laMethod(): 1 = userPresence, 2 = biometryCurrentSet.
        bcks_secure_enclave_p256_generate_ac(method.laMethod(), blobPtr, blobCap, blobLen, pointPtr, pointCap, pointLen)
    }

/**
 * Runs an Enclave generate call with the shim writing straight into two freshly allocated native
 * buffers (blob + public point) — no intermediate arrays. A non-OK status is a typed
 * [HardwareKeyException.SecureElementUnavailable]: the element would not mint the key (absent,
 * unentitled, or — for the access-controlled variant — no passcode/biometric enrolled).
 */
private inline fun enclaveGenerate(
    call: (
        blobPtr: kotlinx.cinterop.CPointer<kotlinx.cinterop.UByteVar>,
        blobCap: platform.posix.size_t,
        blobLen: kotlinx.cinterop.CPointer<size_tVar>,
        pointPtr: kotlinx.cinterop.CPointer<kotlinx.cinterop.UByteVar>,
        pointCap: platform.posix.size_t,
        pointLen: kotlinx.cinterop.CPointer<size_tVar>,
    ) -> Int,
): EnclaveP256Key {
    val blob = BufferFactory.Default.allocate(ENCLAVE_BLOB_CAP)
    val point = BufferFactory.Default.allocate(P256_POINT_BYTES)
    var status = -1
    var blobWritten = 0
    var pointWritten = 0
    memScoped {
        val blobLen = alloc<size_tVar>()
        val pointLen = alloc<size_tVar>()
        blob.withWritablePointer(ENCLAVE_BLOB_CAP) { blobPtr ->
            point.withWritablePointer(P256_POINT_BYTES) { pointPtr ->
                status =
                    call(
                        blobPtr.reinterpret(),
                        ENCLAVE_BLOB_CAP.convert(),
                        blobLen.ptr,
                        pointPtr.reinterpret(),
                        P256_POINT_BYTES.convert(),
                        pointLen.ptr,
                    )
            }
        }
        blobWritten = blobLen.value.toInt()
        pointWritten = pointLen.value.toInt()
    }
    if (status != BCKS_OK) throw HardwareKeyException.SecureElementUnavailable()
    blob.position(blobWritten)
    blob.resetForRead()
    point.position(pointWritten)
    point.resetForRead()
    return EnclaveP256Key(blob, point)
}

/**
 * Signs [message] with the Enclave key restored from [blob], authorized through the LAContext
 * behind [laHandle] (`0` = none), with the DER signature written by the shim straight into a
 * buffer from the caller's [factory].
 *
 * Returns `null` when the OS denied user authentication (the caller maps it to the
 * policy-appropriate typed exception); throws [HardwareKeyException.KeyInvalidated] when the blob
 * is no longer restorable (e.g. biometric re-enrollment invalidated an access-controlled key) and
 * [HardwareKeyException.TransientHardwareFailure] for anything else.
 */
private fun enclaveSignP256Into(
    blob: ReadBuffer,
    laHandle: Long,
    message: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer? {
    val cap = maxSignatureBytes(SignatureScheme.EcdsaP256)
    val out = factory.allocate(cap)
    var status = -1
    var sigWritten = 0
    memScoped {
        val sigLen = alloc<size_tVar>()
        val msgLen = message.remaining()
        blob.withRemainingBytes { blobPtr, blobLen ->
            message.withRemainingBytes2(msgLen) { msgPtr ->
                out.withWritablePointer(cap) { sigPtr ->
                    status =
                        bcks_secure_enclave_p256_sign_ctx(
                            blobPtr.reinterpret(),
                            blobLen.convert(),
                            laHandle,
                            msgPtr.reinterpret(),
                            msgLen.convert(),
                            sigPtr.reinterpret(),
                            cap.convert(),
                            sigLen.ptr,
                        )
                }
            }
        }
        sigWritten = sigLen.value.toInt()
    }
    return when (status) {
        BCKS_OK -> {
            out.position(sigWritten)
            out.resetForRead()
            out
        }
        BCKS_ERR_AUTH -> null
        BCKS_ERR_INPUT -> throw HardwareKeyException.KeyInvalidated()
        else -> throw HardwareKeyException.TransientHardwareFailure(retryable = false)
    }
}

/**
 * The Secure Enclave provider, resolved once. Returned only when the Enclave is both present
 * ([bcks_secure_enclave_available]) and actually usable — probed by a generate-and-discard, which
 * fails closed on an unentitled/unsigned binary (e.g. the macOS CLI test runner) or the simulator,
 * keeping [CryptoCapabilities.hardware] honestly [HardwareSupport.Unavailable] there.
 */
private val appleProvider: HardwareKeyProvider? by lazy {
    if (bcks_secure_enclave_available() == 0) {
        null
    } else {
        val usable =
            try {
                enclaveGenerateP256()
                true
            } catch (_: Throwable) {
                false
            }
        if (usable) SecureEnclaveHardwareKeyProvider() else null
    }
}

internal actual fun platformProtectedKeyProvider(): ProtectedKeyProvider? = appleProvider

/**
 * The Secure Enclave provider narrowed to its concrete type, or `null` where the Enclave is
 * unavailable (simulator, an unentitled CLI runner). [platformKeyStore] uses it to back a durable
 * Keychain-backed [AppleKeychainKeyStore], falling back to a software store otherwise.
 */
internal fun appleEnclaveProviderOrNull(): SecureEnclaveHardwareKeyProvider? = appleProvider as? SecureEnclaveHardwareKeyProvider

// =============================================================================
// Durable Enclave key record — `u16 pointLen | point | blob`
// =============================================================================
//
// The public point and the Enclave restore blob together fully describe a persistent signing key
// (the point builds the VerifyKey; the blob reconstructs the signer inside the Enclave). They are
// framed into one opaque buffer the store persists verbatim in a Keychain item.

private const val RECORD_POINT_LEN_BYTES = 2

/** Frames [point] + [blob] into a durable record, without disturbing either source's position. */
private fun frameEnclaveRecord(
    point: ReadBuffer,
    blob: ReadBuffer,
): PlatformBuffer {
    val pointLen = point.remaining()
    val out = BufferFactory.Default.allocate(RECORD_POINT_LEN_BYTES + pointLen + blob.remaining())
    out.writeShort(pointLen.toShort())
    appendPreservingPosition(out, point)
    appendPreservingPosition(out, blob)
    out.resetForRead()
    return out
}

/** Splits a durable record back into freshly-owned (blob, point) buffers. */
private fun parseEnclaveRecord(record: ReadBuffer): Pair<PlatformBuffer, PlatformBuffer> {
    val pointLen = record.readShort().toInt() and 0xFFFF
    val point = copyBuffer(record.readBytes(pointLen), BufferFactory.Default)
    val blob = copyBuffer(record.readBytes(record.remaining()), BufferFactory.Default)
    return blob to point
}

/** Appends [src]'s remaining bytes to [dst] without advancing [src]'s position (it may be a live key buffer). */
private fun appendPreservingPosition(
    dst: PlatformBuffer,
    src: ReadBuffer,
) {
    if (src.remaining() == 0) return
    val mark = src.position()
    dst.write(src)
    src.position(mark)
}
