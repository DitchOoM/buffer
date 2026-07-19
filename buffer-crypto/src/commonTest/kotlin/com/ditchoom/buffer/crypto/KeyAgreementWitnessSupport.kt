package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/*
 * Test-only accessors that resolve the key-agreement capability witness to ops, so the suites can
 * drive generate/derive uniformly across platforms (Blocking on native, AsyncOnly on web). The thin
 * `generateKeyPair*` / `deriveSharedSecret*` / `supportsSync` helpers keep the KAT / Wycheproof /
 * round-trip suites readable — they mirror the pre-witness top-level signatures (taking a plain
 * `info: ReadBuffer` and optional `salt: ReadBuffer?`) and bridge to the `Info`/`Salt` role types.
 */

/** The async key-agreement ops for [curve] (Blocking.ops also satisfies the async interface), or null. */
internal fun keyAgreementAsyncOrNull(curve: KeyAgreementCurve): KeyAgreementAsyncOps? =
    when (val w = CryptoCapabilities.keyAgreement(curve)) {
        is KeyAgreementSupport.Blocking -> w.ops
        is KeyAgreementSupport.AsyncOnly -> w.ops
        KeyAgreementSupport.Unavailable -> null
    }

/** The synchronous key-agreement ops for [curve], or null where no blocking path exists (web). */
internal fun keyAgreementBlockingOrNull(curve: KeyAgreementCurve): KeyAgreementBlockingOps? =
    when (val w = CryptoCapabilities.keyAgreement(curve)) {
        is KeyAgreementSupport.Blocking -> w.ops
        is KeyAgreementSupport.AsyncOnly -> null
        KeyAgreementSupport.Unavailable -> null
    }

/** Whether [curve] has a synchronous (blocking) key-agreement path on this platform. */
internal fun supportsSync(curve: KeyAgreementCurve): Boolean = CryptoCapabilities.keyAgreement(curve) is KeyAgreementSupport.Blocking

private fun infoOf(info: ReadBuffer): Info = Info.Of(info)

private fun saltOf(salt: ReadBuffer?): Salt = if (salt == null) Salt.None else Salt.Of(salt)

/** Synchronous key-pair generation through the witness; throws if [curve] has no blocking path. */
internal fun generateKeyPair(curve: KeyAgreementCurve): KeyAgreementKeyPair {
    val ops =
        keyAgreementBlockingOrNull(curve)
            ?: throw UnsupportedOperationException("${curve.curveName} has no synchronous key agreement here")
    return ops.generateKeyPairBlocking()
}

/** Synchronous derive through the witness; throws if [curve] has no blocking path. */
internal fun deriveSharedSecret(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer,
    length: Int,
    salt: ReadBuffer? = null,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val ops =
        keyAgreementBlockingOrNull(privateKey.curve)
            ?: throw UnsupportedOperationException("${privateKey.curve.curveName} has no synchronous key agreement here")
    return ops.deriveSharedSecretBlocking(privateKey, peerPublicKey, infoOf(info), length, saltOf(salt), factory)
}

/** Async key-pair generation through the witness; throws if [curve] is unavailable here. */
internal suspend fun generateKeyPairAsync(curve: KeyAgreementCurve): KeyAgreementKeyPair {
    val ops =
        keyAgreementAsyncOrNull(curve)
            ?: throw UnsupportedOperationException("${curve.curveName} key agreement is unavailable on this platform")
    return ops.generateKeyPair()
}

/** Async derive through the witness; throws if the curve is unavailable here. */
internal suspend fun deriveSharedSecretAsync(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer,
    length: Int,
    salt: ReadBuffer? = null,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val ops =
        keyAgreementAsyncOrNull(privateKey.curve)
            ?: throw UnsupportedOperationException(
                "${privateKey.curve.curveName} key agreement is unavailable on this platform",
            )
    return ops.deriveSharedSecret(privateKey, peerPublicKey, infoOf(info), length, saltOf(salt), factory)
}

/** Async raw TLS premaster secret through the witness; throws if the curve is unavailable here. */
internal suspend fun deriveTlsPremasterSecret(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
): PlatformBuffer {
    val ops =
        keyAgreementAsyncOrNull(privateKey.curve)
            ?: throw UnsupportedOperationException(
                "${privateKey.curve.curveName} key agreement is unavailable on this platform",
            )
    return ops.deriveTlsPremasterSecret(privateKey, peerPublicKey)
}
