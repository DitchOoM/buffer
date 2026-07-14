package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/**
 * Elliptic-curve key-agreement primitives: X25519 (Curve25519 ECDH, RFC 7748) and ECDH over
 * the NIST prime curves P-256 / P-384 / P-521. Each platform wraps its **native** stack — JCA
 * (`XDH`/`ECDH`) on JVM/Android, CryptoKit on Apple, WebCrypto on js/wasmJs — never a hand-rolled
 * scalar multiplication.
 *
 * # Security contract
 *
 * **Raw Diffie–Hellman output is never handed back as a key.** A raw X25519/ECDH shared secret
 * is not a uniformly random key (it lives in a structured subset of the field), so every public
 * entry point here runs the raw secret through HKDF-Extract-then-Expand ([Hkdf]) with a
 * caller-supplied [Info] (and optional [Salt]) for domain separation, and returns the *derived*
 * key material. The raw secret is allocated in a wiped [SecureBuffer] and zeroed before return,
 * even on the failure path. The library exposes no API that returns the raw secret.
 *
 * **Public-key validation.** Peer public keys are validated before use:
 *  - X25519: low-order / small-subgroup points produce an all-zero shared secret; this is the
 *    [RFC 7748 §6.1] check and is enforced explicitly in the glue — an all-zero raw secret is
 *    rejected with [InvalidPublicKey] rather than fed to the KDF.
 *  - ECDH: the native provider rejects off-curve points, the point at infinity, and points in a
 *    small subgroup (invalid-curve attack); the glue surfaces those rejections as [InvalidPublicKey].
 *
 * # Encoding (pinned, cross-platform consistent)
 *
 * The buffer API is encoding-consistent on every platform; the glue converts to/from each
 * provider's native key encoding (JCA SPKI/X.509, WebCrypto raw):
 *  - **X25519 public key** — 32-byte little-endian u-coordinate (RFC 7748 raw form).
 *  - **X25519 private key** — 32-byte scalar (clamping is applied natively).
 *  - **ECDH public key** — uncompressed SEC1 point `0x04 ‖ X ‖ Y` (raw point; matches WebCrypto
 *    `raw` and the Wycheproof `…_webcrypto_test.json` vectors). Field-element width is the curve's
 *    coordinate size (32 / 48 / 66 bytes for P-256 / P-384 / P-521).
 *  - **ECDH private key** — big-endian scalar of the curve's coordinate width.
 *
 * # API shape (capability witness)
 *
 * Key-agreement operations are reached through a **capability witness**, not throwing top-level
 * functions: the platform's witness for a curve ([CryptoCapabilities.keyAgreement]) reifies what
 * that platform supports as a `sealed` value, so an operation a platform lacks (a synchronous path
 * on the web) is simply not reachable — the variance lives in the type, not in a runtime throw. A
 * consumer `when`s over the witness exhaustively and the compiler proves every reachable path is
 * satisfiable. See [KeyAgreementSupport]. Key construction stays as the plain
 * [KeyAgreementPublicKey.of] / [importPrivateKey] factories (it never "lacks" on a platform).
 */
sealed interface KeyAgreementCurve {
    /** Human-readable curve name, used only in [InvalidPublicKey.curve] (never carries secrets). */
    val curveName: String

    /** Encoded length of a public key on this curve, in bytes. */
    val publicKeyBytes: Int

    /** Encoded length of a private key on this curve, in bytes. */
    val privateKeyBytes: Int

    /** Length of the raw Diffie–Hellman shared secret on this curve, in bytes. */
    val sharedSecretBytes: Int

    /** Curve25519 (RFC 7748). 32-byte raw u-coordinate public keys, 32-byte scalars. */
    data object X25519 : KeyAgreementCurve {
        override val curveName: String get() = "X25519"
        override val publicKeyBytes: Int get() = 32
        override val privateKeyBytes: Int get() = 32
        override val sharedSecretBytes: Int get() = 32
    }

    /** NIST P-256 (secp256r1). Uncompressed-point public keys (65 bytes), 32-byte scalars. */
    data object P256 : KeyAgreementCurve {
        override val curveName: String get() = "P-256"
        override val publicKeyBytes: Int get() = 65
        override val privateKeyBytes: Int get() = 32
        override val sharedSecretBytes: Int get() = 32
    }

    /** NIST P-384 (secp384r1). Uncompressed-point public keys (97 bytes), 48-byte scalars. */
    data object P384 : KeyAgreementCurve {
        override val curveName: String get() = "P-384"
        override val publicKeyBytes: Int get() = 97
        override val privateKeyBytes: Int get() = 48
        override val sharedSecretBytes: Int get() = 48
    }

    /** NIST P-521 (secp521r1). Uncompressed-point public keys (133 bytes), 66-byte scalars. */
    data object P521 : KeyAgreementCurve {
        override val curveName: String get() = "P-521"
        override val publicKeyBytes: Int get() = 133
        override val privateKeyBytes: Int get() = 66
        override val sharedSecretBytes: Int get() = 66
    }
}

// =============================================================================
// Key types (sealed: only library-provided keys; impls are internal)
// =============================================================================

/**
 * A peer's **public** key for [curve], encoded per the [KeyAgreementCurve] contract.
 *
 * Public keys are not secret, so [encoded] is an ordinary [ReadBuffer]. This is a `sealed
 * interface` with an internal in-memory impl (like [VerifyKey]) so a hardware-backed public key can
 * be added later as a non-breaking minor; it carries its [provenance] for symmetry with the private
 * key. Construct one from received bytes with [of]; the length is validated against
 * [KeyAgreementCurve.publicKeyBytes] eagerly so a wrong-curve or truncated key fails fast.
 */
sealed interface KeyAgreementPublicKey {
    /** The curve this public key lives on. */
    val curve: KeyAgreementCurve

    /** Whether the key material is in software memory or hardware-backed. */
    val provenance: KeyProvenance

    /** The encoded public key bytes, kept read-ready (position 0 .. limit). Not secret. */
    val encoded: ReadBuffer

    companion object {
        /**
         * A public key for [curve] from its [encoded] bytes (the [KeyAgreementCurve] public-key
         * encoding). The length is validated eagerly; the buffer is sliced so the caller may reuse
         * theirs. Throws [IllegalArgumentException] on a wrong-length encoding.
         */
        fun of(
            curve: KeyAgreementCurve,
            encoded: ReadBuffer,
        ): KeyAgreementPublicKey {
            require(encoded.remaining() == curve.publicKeyBytes) {
                "${curve.curveName} public key must be ${curve.publicKeyBytes} bytes, was ${encoded.remaining()}"
            }
            return InMemoryKeyAgreementPublicKey(curve, encoded.slice())
        }
    }
}

/** In-memory [KeyAgreementPublicKey]: holds the (non-secret) public key bytes. */
internal class InMemoryKeyAgreementPublicKey(
    override val curve: KeyAgreementCurve,
    override val encoded: ReadBuffer,
) : KeyAgreementPublicKey {
    override val provenance: KeyProvenance get() = KeyProvenance.Software
}

/**
 * A **private** key for [curve]. This is a `sealed interface` (like [SigningKey]); the only
 * implementation downstream can construct is the in-memory one produced by [generateKeyPair] /
 * [importPrivateKey]. Because downstream cannot name the implementation, it cannot write an
 * exhaustive `when` over it — which is what lets a hardware-backed variant be added later as a
 * non-breaking minor. The key carries its [provenance] (software/hardware) so a caller can branch
 * on whether the material is exportable without inspecting it.
 *
 * In-memory key material lives in a wiped [SecureBuffer] and is erased on [close]. Treat instances
 * as one-shot resources; pair with a [KeyAgreementPublicKey] of the same curve.
 */
sealed interface KeyAgreementPrivateKey : AutoCloseable {
    /** The curve this key agrees on. */
    val curve: KeyAgreementCurve

    /** Whether the key material is in software memory or hardware-backed. */
    val provenance: KeyProvenance

    /**
     * Exports the encoded private scalar (the [KeyAgreementCurve] private-key encoding) into a
     * freshly allocated buffer from [factory], for persistence / interop. Returns a copy for a
     * [KeyProvenance.Software] key; a hardware-backed key holds no exportable material and throws
     * [CryptoMisuseException]. Throws if the key has been [close]d.
     */
    fun exportEncoded(factory: BufferFactory = BufferFactory.Default): ReadBuffer
}

/**
 * In-memory [KeyAgreementPrivateKey]: holds the secret scalar in a wiped [SecureBuffer], zeroed on
 * [close].
 */
internal class InMemoryKeyAgreementPrivateKey(
    override val curve: KeyAgreementCurve,
    private val material: PlatformBuffer,
) : KeyAgreementPrivateKey {
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
        check(!closed) { "KeyAgreementPrivateKey already closed" }
        return material
    }

    override fun exportEncoded(factory: BufferFactory): ReadBuffer = copyBuffer(requireOpen(), factory)
}

/** A key pair for one [KeyAgreementCurve]. Close the pair (or its [privateKey]) to wipe the scalar. */
sealed interface KeyAgreementKeyPair : AutoCloseable {
    /** The curve this pair lives on. */
    val curve: KeyAgreementCurve

    /** The private key; close it (or this pair) to wipe the scalar. */
    val privateKey: KeyAgreementPrivateKey

    /** The matching public key. */
    val publicKey: KeyAgreementPublicKey

    override fun close() {
        privateKey.close()
    }
}

/**
 * A **non-exportable** [KeyAgreementPrivateKey]: an opaque handle whose private scalar never enters
 * process memory (WebCrypto `extractable:false`, or a secure element). It carries no exportable
 * material — [exportEncoded] throws — and its Diffie–Hellman routes through the provider-supplied
 * [gatedDh] closure, so it flows through the ordinary `keyAgreement()` / `hpke()` witnesses unchanged
 * (see [dhRawSecret] and the witness `deriveSharedSecret` dispatch). The [custody] param type
 * [KeyCustody.NonExportable] makes an *exportable* protected agreement key unconstructable;
 * [provenance] is derived from it and can never disagree.
 */
internal class ProtectedKeyAgreementPrivateKey(
    override val curve: KeyAgreementCurve,
    val custody: KeyCustody.NonExportable,
    val gatedDh: HardwareDh,
    private val onClose: () -> Unit = {},
) : KeyAgreementPrivateKey {
    override val provenance: KeyProvenance get() = custody.provenance

    /**
     * Opaque handle; there is no process-memory scalar to wipe. [onClose] lets a provider release an
     * out-of-process resource (e.g. drop the WebCrypto key handle). Defaults to a no-op.
     */
    override fun close() = onClose()

    /** A non-exportable key holds no exportable material; export is unsupported by construction. */
    override fun exportEncoded(factory: BufferFactory): ReadBuffer =
        throw UnsupportedOperationException("non-exportable key-agreement key has no exportable material")
}

/** In-memory [KeyAgreementKeyPair] produced by the platform glue / imports. */
internal class InMemoryKeyAgreementKeyPair(
    override val curve: KeyAgreementCurve,
    override val privateKey: KeyAgreementPrivateKey,
    override val publicKey: KeyAgreementPublicKey,
) : KeyAgreementKeyPair

/**
 * The in-memory private scalar, for the platform bridges. A hardware-backed key (added later) holds
 * no exportable material and would route through a provider path instead, so this seam is only
 * reached for in-memory keys. Throws if the key was already [KeyAgreementPrivateKey.close]d.
 */
internal fun KeyAgreementPrivateKey.requireInMemoryMaterial(): PlatformBuffer =
    when (this) {
        is InMemoryKeyAgreementPrivateKey -> requireOpen()
        // A non-exportable key holds no in-process scalar; its DH routes through the gated closure and
        // never reaches here. This throw is the safety net if a caller routes one into a path that
        // reads raw material. See [ProtectedKeyAgreementPrivateKey].
        is ProtectedKeyAgreementPrivateKey ->
            throw UnsupportedOperationException("non-exportable key-agreement key has no synchronous material")
    }

/**
 * Derives shared key material from a **non-exportable** private key by running its [gatedDh] closure
 * (the raw `DH(sk, peer)`) through the single audited [deriveFromRawSecret] KDF path — the same
 * all-zero rejection + HKDF the in-memory path uses. Shared by both key-agreement witness impls so a
 * non-exportable key behaves identically regardless of which witness resolved.
 */
internal suspend fun ProtectedKeyAgreementPrivateKey.deriveShared(
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer?,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer {
    require(curve == peerPublicKey.curve) { "private/public key curve mismatch" }
    return deriveFromRawSecret(curve, gatedDh(peerPublicKey), info, length, salt, factory)
}

/** Builds an in-memory private key wrapping [material] (a wiped [SecureBuffer], read-ready). */
internal fun keyAgreementPrivateKeyOf(
    curve: KeyAgreementCurve,
    material: PlatformBuffer,
): KeyAgreementPrivateKey = InMemoryKeyAgreementPrivateKey(curve, material)

/** Builds an in-memory key pair from a matching [privateKey] / [publicKey]. */
internal fun keyAgreementKeyPairOf(
    curve: KeyAgreementCurve,
    privateKey: KeyAgreementPrivateKey,
    publicKey: KeyAgreementPublicKey,
): KeyAgreementKeyPair = InMemoryKeyAgreementKeyPair(curve, privateKey, publicKey)

/**
 * Imports an externally-held private key for [curve] from its encoded scalar (the
 * [KeyAgreementCurve] private-key encoding). The scalar is copied into a wiped [SecureBuffer];
 * close the returned key to zero it. Use this for interop with keys generated elsewhere, or to
 * re-load a persisted long-term key. The caller's [encoded] buffer is read non-destructively.
 */
fun importPrivateKey(
    curve: KeyAgreementCurve,
    encoded: ReadBuffer,
): KeyAgreementPrivateKey {
    require(encoded.remaining() == curve.privateKeyBytes) {
        "${curve.curveName} private key must be ${curve.privateKeyBytes} bytes, was ${encoded.remaining()}"
    }
    return keyAgreementPrivateKeyOf(curve, copyBuffer(encoded, secureScratch))
}

// =============================================================================
// Capability witness: operations live ON the witness the platform supplies
// =============================================================================

/**
 * Key-agreement operations available through *some* path. Both [generateKeyPair] and
 * [deriveSharedSecret] are `suspend`; on platforms with a synchronous native KA the witness is a
 * [KeyAgreementBlockingOps], which adds the non-suspend entry points. Every op is bound to the
 * [curve] the witness was resolved for.
 */
interface KeyAgreementAsyncOps {
    /** The curve these ops agree on (the curve the witness was resolved for). */
    val curve: KeyAgreementCurve

    /** Generates a [curve] key pair using the platform CSPRNG. Close the pair to wipe the scalar. */
    suspend fun generateKeyPair(): KeyAgreementKeyPair

    /**
     * Computes the raw Diffie–Hellman shared secret of [privateKey] and [peerPublicKey] (which must
     * be on this [curve]) and immediately derives [length] bytes of key material from it with HKDF —
     * extract-then-expand keyed by the shared secret, domain-separated by [info] and optional
     * [salt]. The raw secret is wiped before this returns.
     *
     * The result is allocated from [factory]; pass `BufferFactory.deterministic().secure()` if the
     * derived material must itself be wiped after use.
     *
     * @throws InvalidPublicKey if the peer key is low-order / off-curve / identity, or (X25519) the
     *   raw secret is all-zero (RFC 7748 §6.1).
     */
    suspend fun deriveSharedSecret(
        privateKey: KeyAgreementPrivateKey,
        peerPublicKey: KeyAgreementPublicKey,
        info: Info,
        length: Int,
        salt: Salt = Salt.None,
        factory: BufferFactory = BufferFactory.Default,
    ): ReadBuffer
}

/** Key agreement with a synchronous (non-`suspend`) path, in addition to the inherited async one. */
interface KeyAgreementBlockingOps : KeyAgreementAsyncOps {
    /** Synchronous [KeyAgreementAsyncOps.generateKeyPair]. */
    fun generateKeyPairBlocking(): KeyAgreementKeyPair

    /** Synchronous [KeyAgreementAsyncOps.deriveSharedSecret]. */
    fun deriveSharedSecretBlocking(
        privateKey: KeyAgreementPrivateKey,
        peerPublicKey: KeyAgreementPublicKey,
        info: Info,
        length: Int,
        salt: Salt = Salt.None,
        factory: BufferFactory = BufferFactory.Default,
    ): ReadBuffer
}

/**
 * Capability witness for key agreement over a curve on this platform:
 *
 *  - [Blocking] — a synchronous native path exists (JVM/Android/Apple/Linux for the supported
 *    curves); [ops] also satisfies [KeyAgreementAsyncOps].
 *  - [AsyncOnly] — only the async path exists (JS/WASM, where WebCrypto is `suspend`-only).
 *  - [Unavailable] — the curve cannot be reached at all on this platform (e.g. X25519 on a JCA
 *    provider that lacks `XDH`, such as Android < 34).
 *
 * Reached via [CryptoCapabilities.keyAgreement]. Because an unsupported op is not a member of the
 * resolved witness, it is unrepresentable rather than a runtime throw.
 */
sealed interface KeyAgreementSupport {
    /** The curve is not available on this platform (cannot be reached at all). */
    data object Unavailable : KeyAgreementSupport

    /** Async-only path available (e.g. WebCrypto). Only [KeyAgreementAsyncOps] is reachable. */
    data class AsyncOnly(
        val ops: KeyAgreementAsyncOps,
    ) : KeyAgreementSupport

    /** A synchronous native path is available; [ops] also satisfies [KeyAgreementAsyncOps]. */
    data class Blocking(
        val ops: KeyAgreementBlockingOps,
    ) : KeyAgreementSupport
}

/**
 * The key-agreement capability for [curve] on this platform: [KeyAgreementSupport.Blocking] on
 * JVM/Android/Apple/Linux for supported curves, [KeyAgreementSupport.AsyncOnly] on JS/WASM
 * (WebCrypto is `suspend`-only), [KeyAgreementSupport.Unavailable] where the curve is absent (e.g.
 * X25519 on Android < 34). On the web, whether the engine actually supports X25519 is
 * feature-detected at call time, so the X25519 witness is [KeyAgreementSupport.AsyncOnly] and the
 * async op throws if the engine lacks it.
 */
expect fun CryptoCapabilities.keyAgreement(curve: KeyAgreementCurve): KeyAgreementSupport

/** Resolves the [curve] witness to its async ops, throwing if the curve is unavailable here. */
internal fun keyAgreementAsyncOps(curve: KeyAgreementCurve): KeyAgreementAsyncOps =
    when (val w = CryptoCapabilities.keyAgreement(curve)) {
        is KeyAgreementSupport.Blocking -> w.ops
        is KeyAgreementSupport.AsyncOnly -> w.ops
        KeyAgreementSupport.Unavailable ->
            throw UnsupportedOperationException("${curve.curveName} key agreement is unavailable on this platform")
    }

// =============================================================================
// Witness op implementations (common — call the per-platform expect primitives)
// =============================================================================

/** Key-agreement ops over the synchronous native primitives. Native async == sync. */
internal class KeyAgreementBlockingOpsImpl(
    override val curve: KeyAgreementCurve,
) : KeyAgreementBlockingOps {
    override fun generateKeyPairBlocking(): KeyAgreementKeyPair = generateKeyPairPlatform(curve)

    override fun deriveSharedSecretBlocking(
        privateKey: KeyAgreementPrivateKey,
        peerPublicKey: KeyAgreementPublicKey,
        info: Info,
        length: Int,
        salt: Salt,
        factory: BufferFactory,
    ): ReadBuffer = deriveSharedSecretPlatform(privateKey, peerPublicKey, info.bytesOrNull, length, salt.bytesOrNull, factory)

    override suspend fun generateKeyPair(): KeyAgreementKeyPair = generateKeyPairBlocking()

    override suspend fun deriveSharedSecret(
        privateKey: KeyAgreementPrivateKey,
        peerPublicKey: KeyAgreementPublicKey,
        info: Info,
        length: Int,
        salt: Salt,
        factory: BufferFactory,
    ): ReadBuffer =
        // A non-exportable key has no in-process scalar for the blocking primitive, so even on a
        // Blocking platform it derives through its gated closure.
        if (privateKey is ProtectedKeyAgreementPrivateKey) {
            privateKey.deriveShared(peerPublicKey, info.bytesOrNull, length, salt.bytesOrNull, factory)
        } else {
            deriveSharedSecretBlocking(privateKey, peerPublicKey, info, length, salt, factory)
        }
}

/** Key-agreement ops over the async-only primitives (WebCrypto on JS/WASM). */
internal class KeyAgreementAsyncOpsImpl(
    override val curve: KeyAgreementCurve,
) : KeyAgreementAsyncOps {
    override suspend fun generateKeyPair(): KeyAgreementKeyPair = generateKeyPairAsyncPlatform(curve)

    override suspend fun deriveSharedSecret(
        privateKey: KeyAgreementPrivateKey,
        peerPublicKey: KeyAgreementPublicKey,
        info: Info,
        length: Int,
        salt: Salt,
        factory: BufferFactory,
    ): ReadBuffer =
        // A non-exportable key derives through its gated closure (no in-process scalar to marshal to
        // WebCrypto); an in-memory key goes through the platform primitive.
        if (privateKey is ProtectedKeyAgreementPrivateKey) {
            privateKey.deriveShared(peerPublicKey, info.bytesOrNull, length, salt.bytesOrNull, factory)
        } else {
            deriveSharedSecretAsyncPlatform(privateKey, peerPublicKey, info.bytesOrNull, length, salt.bytesOrNull, factory)
        }
}

// =============================================================================
// Per-platform primitives — internal seam driven by the witness ops above
// =============================================================================
//
// On JS/WASM the synchronous primitives throw (the witness there is AsyncOnly, so they are never
// reached). On native platforms the async primitives fulfil synchronously.

/** Synchronous key-pair generation. Throws on JS/WASM (witness is AsyncOnly there). */
internal expect fun generateKeyPairPlatform(curve: KeyAgreementCurve): KeyAgreementKeyPair

/** Synchronous derive-shared-secret. Throws on JS/WASM (witness is AsyncOnly there). */
internal expect fun deriveSharedSecretPlatform(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer?,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer

/** Async key-pair generation — native fulfils synchronously; JS/WASM drives WebCrypto. */
internal expect suspend fun generateKeyPairAsyncPlatform(curve: KeyAgreementCurve): KeyAgreementKeyPair

/** Async derive-shared-secret — native fulfils synchronously; JS/WASM drives WebCrypto. */
internal expect suspend fun deriveSharedSecretAsyncPlatform(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer?,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer
