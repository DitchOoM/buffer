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
 * **User authentication** is resolved at generation into a [ResolvedApplePolicy] — each variant
 * carries exactly the authenticator its sign path needs, so a policy without its authenticator is
 * unrepresentable (no nullable state downstream):
 *  - [ResolvedApplePolicy.Advisory] ([UserAuthenticationRequirement.None]) — no `SecAccessControl`;
 *    the [HardwareAuthorization] gate is advisory, evaluated before every sign (the pre-biometrics
 *    behavior and default).
 *  - [ResolvedApplePolicy.Session] — the key is generated with
 *    `SecAccessControl(privateKeyUsage + userPresence/biometryCurrentSet)`, so the *Enclave*
 *    refuses an unauthenticated sign. Signs go through the [LocalAuthAuthenticator]'s long-lived
 *    LAContext; the provider prompts (evaluates the context) lazily when the library-tracked
 *    validity window is stale — the Enclave has no timed window of its own, so the *window* is
 *    library-enforced while the *authentication itself* stays OS-enforced.
 *  - [ResolvedApplePolicy.PerUse] — same `SecAccessControl` binding, but every sign uses a fresh,
 *    un-evaluated LAContext, so the OS prompts for that exact operation.
 *
 * Session/PerUse generation requires a [LocalAuthAuthenticator] (it carries the prompt reason and
 * owns the LAContext); any other gate throws [HardwareKeyException.UserAuthenticatorRequired] at
 * generation. On platforms without LocalAuthentication (tvOS) the authenticator reports
 * unavailable and generation fails the same way.
 */
internal class SecureEnclaveHardwareKeyProvider : HardwareKeyProvider {
    override val dedicatedSecureElement: Boolean get() = true

    override fun eligible(alg: HardwareAlgorithm): Boolean = alg == HardwareAlgorithm.EcdsaP256

    override suspend fun generateAesGcm(spec: HardwareKeySpec): AesGcmKey =
        // No app-controlled non-exportable symmetric Enclave key exists on Apple.
        throw HardwareKeyException.AlgorithmNotEligible()

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: HardwareKeySpec,
    ): SigningKey {
        if (!eligible(scheme.toHardwareAlgorithm())) throw HardwareKeyException.AlgorithmNotEligible()
        val policy = resolvePolicy(spec)
        val generated =
            when (val req = spec.userAuthentication) {
                UserAuthenticationRequirement.None -> enclaveGenerateP256()
                is UserAuthenticationRequirement.Session -> enclaveGenerateP256AccessControlled(req.method)
                is UserAuthenticationRequirement.PerUse -> enclaveGenerateP256AccessControlled(req.method)
            }
        return HardwareSigningKey(
            scheme = SignatureScheme.EcdsaP256,
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
        val window = SessionWindow(policy.validity, policy.method, policy.authenticator)
        return { message, factory ->
            window.ensureAuthenticated()
            // The evaluated LAContext authorizes the Enclave sign without a prompt; a stale/
            // invalidated context surfaces as the keystore-style "authenticate and retry" state.
            withContext(Dispatchers.Default) {
                enclaveSignP256Into(blob, policy.authenticator.contextHandle, message, factory)
            } ?: throw HardwareKeyException.UserAuthenticationRequired()
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
}

/**
 * The key's user-auth policy resolved once at generation. Each variant carries exactly what its
 * sign path needs — [Session]/[PerUse] hold their (non-null, available) [LocalAuthAuthenticator]
 * by construction, so "auth-bound key without an authenticator" is unrepresentable.
 */
private sealed interface ResolvedApplePolicy {
    class Advisory(
        val gate: HardwareAuthorization,
    ) : ResolvedApplePolicy

    class Session(
        val validity: Duration,
        val method: UserAuthenticationMethod,
        val authenticator: LocalAuthAuthenticator,
    ) : ResolvedApplePolicy

    class PerUse(
        val authenticator: LocalAuthAuthenticator,
    ) : ResolvedApplePolicy
}

private fun resolvePolicy(spec: HardwareKeySpec): ResolvedApplePolicy =
    when (val req = spec.userAuthentication) {
        UserAuthenticationRequirement.None -> ResolvedApplePolicy.Advisory(spec.authorization)
        is UserAuthenticationRequirement.Session ->
            ResolvedApplePolicy.Session(req.validity, req.method, spec.authorization.requireLocalAuth())
        is UserAuthenticationRequirement.PerUse ->
            ResolvedApplePolicy.PerUse(spec.authorization.requireLocalAuth())
    }

/** An OS-auth-bound key needs the library's LocalAuthentication authenticator, usable on this platform. */
private fun HardwareAuthorization.requireLocalAuth(): LocalAuthAuthenticator =
    (this as? LocalAuthAuthenticator)?.takeIf { it.available }
        ?: throw HardwareKeyException.UserAuthenticatorRequired()

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

internal actual fun platformHardwareKeyProvider(): HardwareKeyProvider? = appleProvider
