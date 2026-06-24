package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer

/*
 * Test-only accessors that resolve the signature capability witness to ops, so the suites can drive
 * sign/verify uniformly across platforms (Blocking on native, AsyncOnly on web). The thin
 * `signAsync`/`verifyAsync` helpers keep the KAT/tamper/Wycheproof suites readable; they throw
 * [UnsupportedOperationException] where the scheme is [SignatureSupport.Unavailable], which the
 * suites guard against with [ed25519AsyncAvailable] / [supportsEcdsaSigningFromScalar] checks.
 */

/** The async signature ops for [scheme] (Blocking.ops also satisfies SignatureAsyncOps), or null. */
internal fun signatureAsyncOrNull(scheme: SignatureScheme): SignatureAsyncOps? =
    when (val w = CryptoCapabilities.signatures(scheme)) {
        is SignatureSupport.Blocking -> w.ops
        is SignatureSupport.AsyncOnly -> w.ops
        SignatureSupport.Unavailable -> null
    }

/** The synchronous signature ops for [scheme], or null where no blocking path exists (web). */
internal fun signatureBlockingOrNull(scheme: SignatureScheme): SignatureBlockingOps? =
    when (val w = CryptoCapabilities.signatures(scheme)) {
        is SignatureSupport.Blocking -> w.ops
        is SignatureSupport.AsyncOnly -> null
        SignatureSupport.Unavailable -> null
    }

/** Async sign through the witness; throws if [key]'s scheme is unavailable here. */
internal suspend fun signAsync(
    key: SigningKey,
    message: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val ops =
        signatureAsyncOrNull(key.scheme)
            ?: throw UnsupportedOperationException("${key.scheme.schemeName} is unavailable on this platform")
    return ops.sign(key, message, factory)
}

/** Async verify through the witness; throws if [key]'s scheme is unavailable here. */
internal suspend fun verifyAsync(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean {
    val ops =
        signatureAsyncOrNull(key.scheme)
            ?: throw UnsupportedOperationException("${key.scheme.schemeName} is unavailable on this platform")
    return ops.verify(key, message, signature)
}
