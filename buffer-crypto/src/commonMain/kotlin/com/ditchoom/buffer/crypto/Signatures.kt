package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.deterministic

/*
 * Digital signatures: Ed25519 + ECDSA over the NIST P-curves.
 *
 * Operations are reached through a **capability witness**, not throwing top-level functions: the
 * platform's witness for a scheme ([CryptoCapabilities.signatures]) reifies what that platform
 * supports as a `sealed` value, so an operation a platform lacks (a synchronous path on the web,
 * Ed25519 on Android, the sync API on JS/WASM) is simply not reachable — the variance lives in the
 * type, not in a runtime throw. A consumer `when`s over the witness exhaustively and the compiler
 * proves every reachable path is satisfiable. See [SignatureSupport].
 *
 * Two capabilities stay as plain values rather than folding into the op witness because they are
 * **not** op availability:
 *  - [ecdsaSignatureEncoding] is a *wire-format* property (DER vs P1363), needed to pick the right
 *    test vectors and interpret bytes regardless of which op path runs.
 *  - [supportsEcdsaSigningFromScalar] is a *key-construction* capability (a platform may verify ECDSA
 *    but be unable to build a signing key from a bare scalar); it gates key creation, not the op.
 */

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

// =============================================================================
// Key types (sealed: only library-provided keys; impls are internal)
// =============================================================================

/**
 * A **private** signing key bound to a [scheme]. Misuse-resistant: it is a distinct type from
 * [VerifyKey] so a private key can never be passed where a public key is expected, and vice versa.
 *
 * This is a `sealed interface`; the only implementation downstream can construct is the in-memory
 * one produced by the scheme factories ([ed25519], [ecdsaP256], …). Because downstream cannot name
 * the implementation, it cannot write an exhaustive `when` over it — which is what lets a
 * hardware-backed variant be added later as a non-breaking minor. The key carries its [provenance]
 * (software/hardware) so a caller can branch on whether the material is exportable without
 * inspecting it.
 *
 * In-memory key material lives in a wiped [SecureBuffer] (via [deterministicSecure]) and is erased
 * on [close]. Treat instances as one-shot resources:
 *
 * ```kotlin
 * val signatures = CryptoCapabilities.signatures(SignatureScheme.Ed25519)
 * SigningKey.ed25519(seed).use { sk ->
 *     when (signatures) {
 *         is SignatureSupport.Blocking -> signatures.ops.signInto(sk, message, dest)
 *         is SignatureSupport.AsyncOnly -> signatures.ops.sign(sk, message)
 *         SignatureSupport.Unavailable -> error("Ed25519 not available here")
 *     }
 * }
 * ```
 *
 * The exact bytes the material holds are platform-defined (a PKCS#8 blob on JCA, a raw seed on
 * Apple/WebCrypto); callers never inspect them. The factories accept the *standard import encoding*
 * for that scheme.
 */
sealed interface SigningKey : AutoCloseable {
    /** The scheme this key signs under. */
    val scheme: SignatureScheme

    /** Whether the key material is in software memory or hardware-backed. */
    val provenance: KeyProvenance

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
        ): SigningKey = InMemorySigningKey(scheme, copyBuffer(raw, factory))
    }
}

/** In-memory [SigningKey]: holds the raw key material in a [SecureBuffer], wiped on [close]. */
internal class InMemorySigningKey(
    override val scheme: SignatureScheme,
    private val material: PlatformBuffer,
) : SigningKey {
    override val provenance: KeyProvenance get() = KeyProvenance.Software
    private var closed = false

    /** Zeroes and frees the key material. Idempotent. */
    override fun close() {
        if (!closed) {
            closed = true
            material.freeNativeMemory()
        }
    }

    /** The live key material; throws if the key has been [close]d. */
    fun requireOpen(): PlatformBuffer {
        check(!closed) { "SigningKey already closed" }
        return material
    }
}

/**
 * A **public** verification key bound to a [scheme]. Distinct from [SigningKey] by type so the two
 * can never be cross-used, and `sealed` with an internal in-memory impl like [SigningKey] (so a
 * hardware-backed verify key can be added later). Public keys are not secret, so the in-memory
 * variant is a plain buffer (no wipe needed).
 *
 * Construct via the scheme factories ([ed25519], [ecdsaP256], …) using the standard import
 * encoding: a raw 32-byte key for Ed25519, an uncompressed SEC1 point (`04 ‖ X ‖ Y`) for ECDSA.
 */
sealed interface VerifyKey {
    /** The scheme this key verifies under. */
    val scheme: SignatureScheme

    /** Whether the key material is in software memory or hardware-backed. */
    val provenance: KeyProvenance

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
        ): VerifyKey = InMemoryVerifyKey(scheme, copyBuffer(raw, BufferFactory.Default))
    }
}

/** In-memory [VerifyKey]: holds the (non-secret) public key bytes. */
internal class InMemoryVerifyKey(
    override val scheme: SignatureScheme,
    val material: ReadBuffer,
) : VerifyKey {
    override val provenance: KeyProvenance get() = KeyProvenance.Software
}

/**
 * The in-memory signing material, for the platform bridges. A hardware-backed key (added later)
 * holds no exportable material and would route through a provider path instead, so this seam is
 * only reached for in-memory keys. Throws if the key was already [SigningKey.close]d.
 */
internal fun SigningKey.requireInMemoryMaterial(): PlatformBuffer =
    when (this) {
        is InMemorySigningKey -> requireOpen()
    }

/** See [SigningKey.requireInMemoryMaterial]. */
internal fun VerifyKey.requireInMemoryMaterial(): ReadBuffer =
    when (this) {
        is InMemoryVerifyKey -> material
    }

/** Returns a deterministic, secure-erasing factory for private key material. */
internal fun BufferFactory.Companion.deterministicSecure(): BufferFactory = BufferFactory.deterministic().secure()

// =============================================================================
// Wire-format + key-construction capabilities (NOT op availability — see file header)
// =============================================================================

/** The ECDSA signature encoding this platform produces and consumes (see [EcdsaSignatureEncoding]). */
expect val ecdsaSignatureEncoding: EcdsaSignatureEncoding

/**
 * Whether ECDSA **signing from a bare private scalar** is supported on this platform.
 *
 *  - JVM / Android: `true` — JCA derives the public key from the scalar.
 *  - JS / WASM: `true` — WebCrypto imports the PKCS#8 we assemble from the scalar.
 *  - Apple: `true` — CryptoKit's `P###.Signing.PrivateKey(rawRepresentation:)` builds the key from
 *    the scalar (Security.framework cannot, but the CryptoKit shim does).
 *  - Linux: `true`.
 *
 * This is a **key-construction** capability, not an op witness variant: ECDSA *verification* is
 * available on every platform regardless, so it cannot live on the op witness.
 */
expect val supportsEcdsaSigningFromScalar: Boolean

/**
 * Whether Ed25519 sign/verify is available through the **async** API on this platform/runtime.
 * Unlike the synchronous capability — which is reified in the [signatures] witness — Ed25519 on the
 * web is **feature-detected** at runtime against the engine's WebCrypto (a Promise, hence the
 * suspend), so it cannot be a synchronous witness variant:
 *
 *  - JVM: same as the Ed25519 witness being [SignatureSupport.Blocking] (JDK 15+).
 *  - Android: `false` (no raw-key Ed25519 import path).
 *  - Apple / Linux: `true`.
 *  - JS / WASM: `true` on Chrome 137+/Firefox 129+/Safari 17+/Node stable, `false` otherwise.
 *
 * When `false`, the Ed25519 async ops throw [UnsupportedOperationException].
 */
expect suspend fun ed25519AsyncAvailable(): Boolean

// Upper-bound signature sizes (bytes). Ed25519 is fixed at 64; the ECDSA values are the DER
// worst case (SEQUENCE + two INTEGERs, each tag+len+sign-pad+coordinate) for each curve.
private const val ED25519_SIGNATURE_BYTES = 64
private const val ECDSA_P256_MAX_DER_BYTES = 72
private const val ECDSA_P384_MAX_DER_BYTES = 104
private const val ECDSA_P521_MAX_DER_BYTES = 139

/** Upper bound on the signature size (bytes) a [scheme] can produce, for sizing a destination. */
fun maxSignatureBytes(scheme: SignatureScheme): Int =
    when (scheme) {
        SignatureScheme.Ed25519 -> ED25519_SIGNATURE_BYTES
        SignatureScheme.EcdsaP256 -> ECDSA_P256_MAX_DER_BYTES
        SignatureScheme.EcdsaP384 -> ECDSA_P384_MAX_DER_BYTES
        SignatureScheme.EcdsaP521 -> ECDSA_P521_MAX_DER_BYTES
    }

// =============================================================================
// Capability witness: operations live ON the witness the platform supplies
// =============================================================================

/**
 * Signature operations available through *some* path. Both sign and verify are `suspend`; on
 * platforms with a synchronous native signature stack the witness is a [SignatureBlockingOps],
 * which adds the non-suspend entry points.
 */
interface SignatureAsyncOps {
    /** Signs [message] under [key], returning a read-ready signature buffer. */
    suspend fun sign(
        key: SigningKey,
        message: ReadBuffer,
        factory: BufferFactory = BufferFactory.Default,
    ): ReadBuffer

    /**
     * Verifies that [signature] is a valid signature of [message] under [key]. Returns `true` iff
     * it verifies; a tampered signature / message / key returns `false`, never throw-as-valid.
     */
    suspend fun verify(
        key: VerifyKey,
        message: ReadBuffer,
        signature: ReadBuffer,
    ): Boolean
}

/** Signatures with a synchronous (non-`suspend`) path, in addition to the inherited async one. */
interface SignatureBlockingOps : SignatureAsyncOps {
    /**
     * Signs the remaining bytes of [message] under [key], writing the signature into [dest] at its
     * current position and advancing it. Returns the number of signature bytes written. [dest] must
     * have at least [maxSignatureBytes] of [key]'s scheme remaining.
     */
    fun signInto(
        key: SigningKey,
        message: ReadBuffer,
        dest: WriteBuffer,
    ): Int

    /** Synchronous [SignatureAsyncOps.sign]: a freshly allocated, read-ready signature buffer. */
    fun signBlocking(
        key: SigningKey,
        message: ReadBuffer,
        factory: BufferFactory = BufferFactory.Default,
    ): ReadBuffer

    /** Synchronous [SignatureAsyncOps.verify]. */
    fun verifyBlocking(
        key: VerifyKey,
        message: ReadBuffer,
        signature: ReadBuffer,
    ): Boolean
}

/**
 * Capability witness for a signature scheme on this platform:
 *
 *  - [Blocking] — a synchronous path exists (JVM/Android/Apple/Linux for the supported schemes);
 *    [ops] also satisfies [SignatureAsyncOps].
 *  - [AsyncOnly] — only the async path exists (JS/WASM, where WebCrypto is `suspend`-only).
 *  - [Unavailable] — the scheme cannot be reached at all on this platform (e.g. Ed25519 on Android,
 *    which has no raw-key import path).
 *
 * Reached via [CryptoCapabilities.signatures]. Because an unsupported op is not a member of the
 * resolved witness, it is unrepresentable rather than a runtime throw.
 */
sealed interface SignatureSupport {
    /** The scheme is not available on this platform (cannot be reached at all). */
    data object Unavailable : SignatureSupport

    /** Async-only path available (e.g. WebCrypto). Only [SignatureAsyncOps] is reachable. */
    data class AsyncOnly(
        val ops: SignatureAsyncOps,
    ) : SignatureSupport

    /** A synchronous native path is available; [ops] also satisfies [SignatureAsyncOps]. */
    data class Blocking(
        val ops: SignatureBlockingOps,
    ) : SignatureSupport
}

/**
 * The signature capability for [scheme] on this platform: [SignatureSupport.Blocking] on
 * JVM/Android/Apple/Linux for supported schemes, [SignatureSupport.AsyncOnly] on JS/WASM (WebCrypto
 * is `suspend`-only), [SignatureSupport.Unavailable] where the scheme is absent (Ed25519 on
 * Android). Ed25519 on the web is reported [SignatureSupport.AsyncOnly]; whether the engine actually
 * supports it is feature-detected at call time (see [ed25519AsyncAvailable]).
 */
expect fun CryptoCapabilities.signatures(scheme: SignatureScheme): SignatureSupport

// =============================================================================
// Witness op implementations (common — call the per-platform expect primitives)
// =============================================================================

/** Signature ops over the synchronous native primitives. Native async == sync. */
internal object SignatureBlockingOpsImpl : SignatureBlockingOps {
    override fun signInto(
        key: SigningKey,
        message: ReadBuffer,
        dest: WriteBuffer,
    ): Int = signIntoPlatform(key, message, dest)

    override fun signBlocking(
        key: SigningKey,
        message: ReadBuffer,
        factory: BufferFactory,
    ): ReadBuffer {
        val out = factory.allocate(maxSignatureBytes(key.scheme))
        val written = signIntoPlatform(key, message, out)
        out.position(0)
        out.setLimit(written)
        return out
    }

    override fun verifyBlocking(
        key: VerifyKey,
        message: ReadBuffer,
        signature: ReadBuffer,
    ): Boolean = verifyPlatform(key, message, signature)

    override suspend fun sign(
        key: SigningKey,
        message: ReadBuffer,
        factory: BufferFactory,
    ): ReadBuffer = signBlocking(key, message, factory)

    override suspend fun verify(
        key: VerifyKey,
        message: ReadBuffer,
        signature: ReadBuffer,
    ): Boolean = verifyBlocking(key, message, signature)
}

/** Signature ops over the async-only primitives (WebCrypto on JS/WASM). */
internal object SignatureAsyncOpsImpl : SignatureAsyncOps {
    override suspend fun sign(
        key: SigningKey,
        message: ReadBuffer,
        factory: BufferFactory,
    ): ReadBuffer = signAsyncPlatform(key, message, factory)

    override suspend fun verify(
        key: VerifyKey,
        message: ReadBuffer,
        signature: ReadBuffer,
    ): Boolean = verifyAsyncPlatform(key, message, signature)
}

// =============================================================================
// Per-platform primitives — internal seam driven by the witness ops above
// =============================================================================
//
// On JS/WASM the synchronous primitives throw (the witness there is AsyncOnly, so they are never
// reached). On native platforms the async primitives fulfil synchronously.

/** Synchronous sign-into-[dest]. Throws on JS/WASM (witness is AsyncOnly there). */
internal expect fun signIntoPlatform(
    key: SigningKey,
    message: ReadBuffer,
    dest: WriteBuffer,
): Int

/** Synchronous verify. Throws on JS/WASM (witness is AsyncOnly there). */
internal expect fun verifyPlatform(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean

/** Async sign — native fulfils synchronously; JS/WASM goes through WebCrypto. */
internal expect suspend fun signAsyncPlatform(
    key: SigningKey,
    message: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer

/** Async verify — native fulfils synchronously; JS/WASM goes through WebCrypto. */
internal expect suspend fun verifyAsyncPlatform(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean
