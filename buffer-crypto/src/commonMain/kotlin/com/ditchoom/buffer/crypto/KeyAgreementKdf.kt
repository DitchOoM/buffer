package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.use

/**
 * The single audited place where a raw Diffie–Hellman shared secret becomes key material.
 *
 * Platform actuals produce *only* the raw secret (in a [SecureBuffer]); they hand it here, and
 * this function performs the two security-critical steps that must be identical on every target:
 *
 *  1. **All-zero rejection.** A raw secret of all zero bytes is the RFC 7748 §6.1 signal that the
 *     peer supplied a low-order / small-subgroup point (and the analogous degenerate ECDH output).
 *     It is rejected with [InvalidPublicKey] — never fed to the KDF — using a constant-time scan so
 *     the check itself is not a timing oracle.
 *  2. **KDF.** The raw secret is run through HKDF-Extract-then-Expand keyed on the secret, with the
 *     caller's [info] (for domain separation; `null`/[Info.None] ⇒ empty context) and optional
 *     [salt]. The library never returns the raw secret.
 *
 * The [rawSecret] buffer is wiped and freed before this returns, on every path including the
 * rejection path. [rawSecret] must be a wiped [SecureBuffer] (it is, by construction in the glue).
 */
internal fun deriveFromRawSecret(
    curve: KeyAgreementCurve,
    rawSecret: PlatformBuffer,
    info: ReadBuffer?,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer {
    require(length >= 0) { "length must be non-negative, was $length" }
    // Contract: [rawSecret] is handed in read-ready (position 0 .. limit = secret length). We must
    // NOT resetForRead here — on an already-read-ready buffer that would collapse the limit to 0.
    return try {
        require(rawSecret.remaining() == curve.sharedSecretBytes) {
            "${curve.curveName} raw secret must be ${curve.sharedSecretBytes} bytes, was ${rawSecret.remaining()}"
        }
        // The all-zero-secret rejection is the RFC 7748 §6.1 contributory-behaviour check and is
        // **X25519-specific**: on Curve25519 an all-zero output is the unambiguous signal of a
        // low-order / small-subgroup peer point. On the NIST P-curves an all-zero X-coordinate can
        // legitimately arise from a valid on-curve point (Wycheproof marks such cases `valid`), and
        // invalid/off-curve/infinity points are already rejected by the native provider — so the
        // zero check must not be applied there or it would reject those valid edge vectors.
        if (curve == KeyAgreementCurve.X25519 && isAllZero(rawSecret)) {
            throw InvalidPublicKey(curve.curveName)
        }
        Hkdf.derive(salt = salt.toSalt(), ikm = rawSecret, info = info.toInfo(), length = length, factory = factory)
    } finally {
        rawSecret.freeNativeMemory()
    }
}

/**
 * Constant-time all-zero test over a buffer's remaining bytes: ORs every byte together so the
 * loop never short-circuits on the first non-zero byte. Non-destructive.
 */
private fun isAllZero(buffer: ReadBuffer): Boolean {
    val start = buffer.position()
    val n = buffer.remaining()
    var acc = 0
    for (i in 0 until n) acc = acc or (buffer.get(start + i).toInt() and BYTE_MASK)
    return acc == 0
}

private const val BYTE_MASK = 0xFF

/** Scratch factory for the raw secret: deterministic so it can be `use {}`-freed, secure so it is wiped. */
internal val secureScratch: BufferFactory get() = BufferFactory.deterministic().secure()

/** Runs [block] with a freshly allocated, wiped scratch buffer of [size] bytes, freed afterwards. */
internal inline fun <R> withSecureScratch(
    size: Int,
    block: (PlatformBuffer) -> R,
): R = secureScratch.allocate(size).use(block)
