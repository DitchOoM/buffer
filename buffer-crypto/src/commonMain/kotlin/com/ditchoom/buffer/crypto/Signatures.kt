package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.deterministic

/**
 * A digital-signature scheme. Each scheme **binds its curve to a single hash** so a caller can
 * never pair, say, P-256 with SHA-512 — the curve↔hash binding is part of the type, closing the
 * "curve/hash mismatch" misuse vector (see the threat model).
 *
 * Only the schemes the module actually wraps appear here:
 *
 *  - [Ed25519] — pure Ed25519 (RFC 8032). **Not** Ed25519ph / Ed25519ctx; the prehash/context
 *    variants are deliberately excluded to avoid the cross-variant forgery vector.
 *  - [EcdsaP256] / [EcdsaP384] / [EcdsaP521] — ECDSA over the NIST prime curves, each paired with
 *    its matching SHA-2 hash (P-256↔SHA-256, P-384↔SHA-384, P-521↔SHA-512) as FIPS 186 intends.
 */
sealed interface SignatureScheme {
    /** Human-readable scheme name, safe to log (no secret material). */
    val schemeName: String

    /** Pure Ed25519 (RFC 8032). 32-byte private seed, 32-byte public key, 64-byte signature. */
    data object Ed25519 : SignatureScheme {
        override val schemeName: String get() = "Ed25519"
    }

    /** ECDSA over NIST P-256 (secp256r1) with SHA-256. */
    data object EcdsaP256 : SignatureScheme {
        override val schemeName: String get() = "ECDSA-P256-SHA256"
    }

    /** ECDSA over NIST P-384 (secp384r1) with SHA-384. */
    data object EcdsaP384 : SignatureScheme {
        override val schemeName: String get() = "ECDSA-P384-SHA384"
    }

    /** ECDSA over NIST P-521 (secp521r1) with SHA-512. */
    data object EcdsaP521 : SignatureScheme {
        override val schemeName: String get() = "ECDSA-P521-SHA512"
    }
}

/** True iff [scheme] is one of the ECDSA P-curve schemes (as opposed to Ed25519). */
internal val SignatureScheme.isEcdsa: Boolean
    get() =
        this is SignatureScheme.EcdsaP256 ||
            this is SignatureScheme.EcdsaP384 ||
            this is SignatureScheme.EcdsaP521

/**
 * Encoding a platform uses for an ECDSA signature on the wire.
 *
 *  - [Der] — ASN.1 DER `SEQUENCE { INTEGER r, INTEGER s }` (JCA / JVM / Android / Apple Security).
 *  - [P1363] — fixed-width raw `r ‖ s`, each integer left-padded to the curve's byte length
 *    (WebCrypto). This is what `ecdsa_..._p1363_test.json` exercises.
 *
 * Ed25519 has a single canonical 64-byte encoding on every platform, so this only describes ECDSA.
 * Pinned per platform via [ecdsaSignatureEncoding] and asserted by the Wycheproof suite, which
 * runs *both* the DER and the P1363 vector files.
 */
enum class EcdsaSignatureEncoding {
    Der,
    P1363,
}

/**
 * A **private** signing key bound to a [scheme]. Misuse-resistant: it is a distinct type from
 * [VerifyKey] so a private key can never be passed where a public key is expected, and vice versa.
 *
 * The key material lives in a [SecureBuffer] ([material]) so it is wiped from memory on [close].
 * Treat instances as one-shot resources:
 *
 * ```kotlin
 * SigningKey.ed25519(seed).use { sk -> signInto(sk, message, dest) }
 * ```
 *
 * The exact bytes [material] holds are platform-defined (a PKCS#8 blob on JCA, a raw seed on
 * Apple/WebCrypto); callers never inspect them. Construct via the scheme-specific factories
 * ([ed25519], [ecdsaP256], …) which accept the *standard import encoding* for that scheme.
 */
class SigningKey private constructor(
    val scheme: SignatureScheme,
    internal val material: PlatformBuffer,
) : AutoCloseable {
    private var closed = false

    /** Zeroes and frees the key material. Idempotent. */
    override fun close() {
        if (!closed) {
            closed = true
            material.freeNativeMemory()
        }
    }

    internal fun requireOpen(): PlatformBuffer {
        check(!closed) { "SigningKey already closed" }
        return material
    }

    companion object {
        /**
         * An Ed25519 signing key from its 32-byte raw private seed (RFC 8032 §5.1.5).
         * The seed is copied into a wiped [SecureBuffer]; the caller may free [seed] afterward.
         */
        fun ed25519(
            seed: ReadBuffer,
            factory: BufferFactory = BufferFactory.deterministicSecure(),
        ): SigningKey = of(SignatureScheme.Ed25519, seed, factory)

        /** An ECDSA P-256 signing key from its raw private scalar `d` (32 bytes, big-endian). */
        fun ecdsaP256(
            privateScalar: ReadBuffer,
            factory: BufferFactory = BufferFactory.deterministicSecure(),
        ): SigningKey = of(SignatureScheme.EcdsaP256, privateScalar, factory)

        /** An ECDSA P-384 signing key from its raw private scalar `d` (48 bytes, big-endian). */
        fun ecdsaP384(
            privateScalar: ReadBuffer,
            factory: BufferFactory = BufferFactory.deterministicSecure(),
        ): SigningKey = of(SignatureScheme.EcdsaP384, privateScalar, factory)

        /** An ECDSA P-521 signing key from its raw private scalar `d` (66 bytes, big-endian). */
        fun ecdsaP521(
            privateScalar: ReadBuffer,
            factory: BufferFactory = BufferFactory.deterministicSecure(),
        ): SigningKey = of(SignatureScheme.EcdsaP521, privateScalar, factory)

        private fun of(
            scheme: SignatureScheme,
            raw: ReadBuffer,
            factory: BufferFactory,
        ): SigningKey = SigningKey(scheme, copyBuffer(raw, factory))
    }
}

/**
 * A **public** verification key bound to a [scheme]. Distinct from [SigningKey] by type so the two
 * can never be cross-used. Public keys are not secret, so this is a plain buffer (no wipe needed).
 *
 * Construct via the scheme factories ([ed25519], [ecdsaP256], …) using the standard import
 * encoding: a raw 32-byte key for Ed25519, an uncompressed SEC1 point (`04 ‖ X ‖ Y`) for ECDSA.
 */
class VerifyKey private constructor(
    val scheme: SignatureScheme,
    internal val material: ReadBuffer,
) {
    companion object {
        /** An Ed25519 verify key from its 32-byte raw public key (RFC 8032 §5.1.5). */
        fun ed25519(publicKey: ReadBuffer): VerifyKey = of(SignatureScheme.Ed25519, publicKey)

        /** An ECDSA P-256 verify key from an uncompressed SEC1 point (`04 ‖ X ‖ Y`, 65 bytes). */
        fun ecdsaP256(point: ReadBuffer): VerifyKey = of(SignatureScheme.EcdsaP256, point)

        /** An ECDSA P-384 verify key from an uncompressed SEC1 point (`04 ‖ X ‖ Y`, 97 bytes). */
        fun ecdsaP384(point: ReadBuffer): VerifyKey = of(SignatureScheme.EcdsaP384, point)

        /** An ECDSA P-521 verify key from an uncompressed SEC1 point (`04 ‖ X ‖ Y`, 133 bytes). */
        fun ecdsaP521(point: ReadBuffer): VerifyKey = of(SignatureScheme.EcdsaP521, point)

        private fun of(
            scheme: SignatureScheme,
            raw: ReadBuffer,
        ): VerifyKey = VerifyKey(scheme, copyBuffer(raw, BufferFactory.Default))
    }
}

/** Returns a deterministic, secure-erasing factory for private key material. */
internal fun BufferFactory.Companion.deterministicSecure(): BufferFactory = BufferFactory.deterministic().secure()

// ============================================================================
// Synchronous API (native platforms: JVM / Android / Apple)
// ============================================================================

/**
 * Whether this platform can sign/verify Ed25519 **synchronously**.
 *
 *  - JVM: `true` on JDK 15+ (the `Ed25519` JCA algorithm); `false` on older JDKs.
 *  - Android: `true` only at runtime `SDK_INT >= 34` (Conscrypt added Ed25519 in Android 14);
 *    `false` on API 28–33 — the gate is a *runtime* check, not compile-time.
 *  - Apple: `true` (CryptoKit `Curve25519.Signing`).
 *  - JS / WASM: `false` — WebCrypto sign/verify is async-only; use [signAsync] / [verifyAsync].
 */
expect val supportsSyncEd25519: Boolean

/**
 * Whether this platform can sign/verify ECDSA over the NIST P-curves **synchronously**.
 *
 *  - JVM / Android / Apple: `true`.
 *  - JS / WASM: `false` — WebCrypto is async-only; use [signAsync] / [verifyAsync].
 */
expect val supportsSyncEcdsa: Boolean

/** The ECDSA signature encoding this platform produces and consumes (see [EcdsaSignatureEncoding]). */
expect val ecdsaSignatureEncoding: EcdsaSignatureEncoding

/**
 * Whether ECDSA **signing from a bare private scalar** is supported on this platform.
 *
 *  - JVM / Android: `true` — JCA derives the public key from the scalar.
 *  - JS / WASM: `true` — WebCrypto imports the PKCS#8 we assemble from the scalar.
 *  - Apple: `false` — Security.framework's `SecKeyCreateWithData` needs the *full* X9.63 private
 *    representation `04 ‖ X ‖ Y ‖ K`, which cannot be reconstructed from the scalar alone without
 *    a scalar-multiply we don't expose. ECDSA **verify** is fully supported on Apple regardless.
 *
 * Verification ([verify] / [verifyAsync]) is available on every native platform irrespective of
 * this flag; it only gates signing.
 */
expect val supportsEcdsaSigningFromScalar: Boolean

/**
 * Signs the remaining bytes of [message] under [key], writing the signature into [dest] at its
 * current position and advancing it. Returns the number of signature bytes written (Ed25519 is
 * always 64; ECDSA-DER varies, P1363 is fixed-width per curve).
 *
 * [dest] must have at least [maxSignatureBytes] of [key]'s scheme remaining. Throws
 * [UnsupportedOperationException] where the scheme's `supportsSync*` flag is `false` (e.g. Ed25519
 * on Android below API 34, or on JS/WASM).
 */
expect fun signInto(
    key: SigningKey,
    message: ReadBuffer,
    dest: WriteBuffer,
): Int

/**
 * Verifies that [signature] is a valid signature of [message] under [key].
 *
 * Returns `true` iff the signature verifies. A tampered signature / message / public key returns
 * `false` — it is **never** an accepted signature. Malformed inputs (non-canonical DER, `r=0`,
 * off-curve point, etc.) are rejected by the platform and surface as `false` here. Throws
 * [UnsupportedOperationException] where the scheme is not synchronously supported.
 *
 * The reads are non-destructive on success and failure alike; positions are restored.
 */
expect fun verify(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean

/** Upper bound on the signature size (bytes) a [scheme] can produce, for sizing [dest]. */
fun maxSignatureBytes(scheme: SignatureScheme): Int =
    when (scheme) {
        SignatureScheme.Ed25519 -> 64
        // DER overhead worst case: SEQUENCE + two INTEGER (each: tag+len+sign-pad+coord).
        SignatureScheme.EcdsaP256 -> 72
        SignatureScheme.EcdsaP384 -> 104
        SignatureScheme.EcdsaP521 -> 139
    }

/**
 * One-shot synchronous sign returning a freshly allocated, read-ready signature buffer.
 * Convenience over [signInto]; see it for the capability contract.
 */
fun sign(
    key: SigningKey,
    message: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val out = factory.allocate(maxSignatureBytes(key.scheme))
    val written = signInto(key, message, out)
    out.position(0)
    out.setLimit(written)
    return out
}

// ============================================================================
// Suspending async API (works on every platform, including browser JS/WASM)
// ============================================================================

/**
 * Signs [message] under [key], returning a read-ready signature buffer. Works on **every**
 * platform: native ones fulfil it synchronously, JS/WASM go through WebCrypto's async
 * `SubtleCrypto.sign`. Throws [UnsupportedOperationException] only where the scheme itself is
 * unavailable (e.g. Ed25519 on a WebCrypto engine without it, or Android below API 34).
 */
expect suspend fun signAsync(
    key: SigningKey,
    message: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer

/**
 * Verifies [signature] over [message] under [key] on every platform (WebCrypto async on JS/WASM,
 * native otherwise). Returns `true` iff valid; tampered inputs return `false`, never throw-as-valid.
 */
expect suspend fun verifyAsync(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean

/**
 * Whether Ed25519 sign/verify is available through the **async** API on this platform/runtime:
 *
 *  - JVM: same as [supportsSyncEd25519] (JDK 15+).
 *  - Android: `true` only at runtime `SDK_INT >= 34`.
 *  - Apple: `false` (no CryptoKit bridge; see the Apple notes).
 *  - JS / WASM: runtime **feature-detected** against the engine's WebCrypto (a Promise, hence the
 *    suspend) — `true` on Chrome 137+/Firefox 129+/Safari 17+/Node stable, `false` otherwise.
 *
 * When `false`, [signAsync] / [verifyAsync] throw [UnsupportedOperationException] for Ed25519.
 */
expect suspend fun ed25519AsyncAvailable(): Boolean
