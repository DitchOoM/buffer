package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/**
 * Internal seam exposing the **raw** Diffie–Hellman shared secret, for the one consumer that
 * legitimately needs it: HPKE / DHKEM (RFC 9180). DHKEM runs the raw DH output through its own
 * `LabeledExtract`/`LabeledExpand` schedule (`ExtractAndExpand`), so it cannot use the public
 * [deriveSharedSecret], which forces a fixed HKDF over the secret and never returns it.
 *
 * This stays `internal` precisely because the public key-agreement contract is "the raw secret is
 * never returned". HPKE is in-module and audited; it is the only caller. The contract here is the
 * same as [deriveSharedSecret] minus the KDF step:
 *
 *  - Peer-public-key validation is performed by the native provider; an off-curve / low-order /
 *    identity point surfaces as [InvalidPublicKey] (no exception-type oracle).
 *  - For X25519 the RFC 7748 §6.1 all-zero output is rejected with [InvalidPublicKey] (constant-time
 *    scan), exactly as the public path does.
 *  - The returned buffer is a wiped [SecureBuffer] holding `curve.sharedSecretBytes` bytes,
 *    read-ready. **The caller owns it and MUST free it** (`use {}` / `freeNativeMemory()`).
 *
 * It is `suspend` so the web (WebCrypto, Promise-only) is covered by the same surface; on
 * JVM/Android/Apple the actual delegates to the synchronous native agreement.
 *
 * @throws InvalidPublicKey for a rejected peer point (or X25519 all-zero secret).
 * @throws UnsupportedOperationException if the curve has no support on this platform.
 */
internal expect suspend fun dhRawSecret(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
): PlatformBuffer

/**
 * Shared post-processing for a raw DH secret produced by a platform actual: enforces the
 * X25519 all-zero rejection (RFC 7748 §6.1) in one audited, constant-time place. The [rawSecret]
 * is handed in read-ready; on rejection it is wiped+freed before throwing. On success it is
 * returned unchanged (still owned by the caller).
 */
internal fun validateRawSecret(
    curve: KeyAgreementCurve,
    rawSecret: PlatformBuffer,
): PlatformBuffer {
    require(rawSecret.remaining() == curve.sharedSecretBytes) {
        "${curve.curveName} raw secret must be ${curve.sharedSecretBytes} bytes, was ${rawSecret.remaining()}"
    }
    // X25519-specific: an all-zero output is the unambiguous low-order / small-subgroup signal.
    // On the NIST P-curves a zero X-coordinate can be a valid on-curve point, and the provider has
    // already rejected genuinely invalid points, so the zero check is not applied there.
    if (curve == KeyAgreementCurve.X25519 && isAllZeroSecret(rawSecret)) {
        rawSecret.freeNativeMemory()
        throw InvalidPublicKey(curve.curveName)
    }
    return rawSecret
}

/** Constant-time all-zero scan over a buffer's remaining bytes (never short-circuits). */
private fun isAllZeroSecret(buffer: ReadBuffer): Boolean {
    val start = buffer.position()
    val n = buffer.remaining()
    var acc = 0
    for (i in 0 until n) acc = acc or (buffer.get(start + i).toInt() and BYTE_MASK)
    return acc == 0
}

private const val BYTE_MASK = 0xFF
