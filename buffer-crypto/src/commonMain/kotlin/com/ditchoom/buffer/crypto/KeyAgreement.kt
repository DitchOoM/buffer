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
 * caller-supplied `info` (and optional `salt`) for domain separation, and returns the *derived*
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
 * # API shape (mirrors `:buffer-compression`)
 *
 * A synchronous [deriveSharedSecret] `expect fun` for platforms with a synchronous native KA, a
 * `supportsSync…` capability flag per curve, and a [deriveSharedSecretAsync] suspend wrapper that
 * also covers web (WebCrypto KA is Promise-only). Where a curve is unsupported the sync function
 * throws [UnsupportedOperationException] and its flag is `false`.
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

/**
 * A peer's **public** key for [curve], encoded per the [KeyAgreementCurve] contract.
 *
 * Public keys are not secret, so the backing buffer is an ordinary [ReadBuffer]. Construct one
 * from received bytes with [KeyAgreementCurve] of the expected curve; the length is validated
 * against [KeyAgreementCurve.publicKeyBytes] eagerly so a wrong-curve or truncated key fails fast.
 */
class KeyAgreementPublicKey(
    val curve: KeyAgreementCurve,
    encoded: ReadBuffer,
) {
    /** The encoded public key bytes, kept read-ready (position 0 .. limit). */
    val encoded: ReadBuffer

    init {
        require(encoded.remaining() == curve.publicKeyBytes) {
            "${curve.curveName} public key must be ${curve.publicKeyBytes} bytes, was ${encoded.remaining()}"
        }
        this.encoded = encoded.slice()
    }
}

/**
 * A **private** key for [curve]. The encoded scalar lives in a [SecureBuffer] that is wiped on
 * [close]; never log or serialize it. Pair with a [KeyAgreementPublicKey] of the same curve.
 *
 * A private key normally comes from [generateKeyPair], which produces it via the platform CSPRNG.
 * Importing externally-held key bytes is supported for interop via the secondary constructor.
 */
class KeyAgreementPrivateKey internal constructor(
    val curve: KeyAgreementCurve,
    /** Secret scalar in a wiped [SecureBuffer]; do not retain references past [close]. */
    val encoded: PlatformBuffer,
) : AutoCloseable {
    override fun close() {
        encoded.freeNativeMemory()
    }
}

/** A freshly generated key pair for one [KeyAgreementCurve]. Close the [privateKey] to wipe it. */
class KeyAgreementKeyPair internal constructor(
    val curve: KeyAgreementCurve,
    val privateKey: KeyAgreementPrivateKey,
    val publicKey: KeyAgreementPublicKey,
) : AutoCloseable {
    override fun close() {
        privateKey.close()
    }
}

/**
 * Generates a [curve] key pair using the platform's native CSPRNG. The returned private key is
 * held in a wiped [SecureBuffer]; close the pair (or its private key) to zero it.
 *
 * Throws [UnsupportedOperationException] when [curve] has no synchronous native support on this
 * platform (its `supportsSync…` flag is `false`); use [generateKeyPairAsync] there.
 */
expect fun generateKeyPair(curve: KeyAgreementCurve): KeyAgreementKeyPair

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
    return KeyAgreementPrivateKey(curve, copyBuffer(encoded, secureScratch))
}

/**
 * Computes the raw Diffie–Hellman shared secret of [privateKey] and [peerPublicKey] (which must
 * be on the same curve) and immediately derives [length] bytes of key material from it with
 * HKDF — extract-then-expand keyed by the shared secret, domain-separated by [info] (required)
 * and [salt] (optional). The raw secret is wiped before this returns.
 *
 * The result is allocated from [factory]; pass `BufferFactory.deterministic().secure()` if the
 * derived material must itself be wiped after use.
 *
 * @throws InvalidPublicKey if the peer key is low-order / off-curve / identity, or (X25519) the
 *   raw secret is all-zero (RFC 7748 §6.1).
 * @throws UnsupportedOperationException if the curve lacks synchronous native support here.
 */
expect fun deriveSharedSecret(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer,
    length: Int,
    salt: ReadBuffer? = null,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer

/** Whether this platform can run [generateKeyPair]/[deriveSharedSecret] for X25519 synchronously. */
expect val supportsSyncX25519: Boolean

/** Whether this platform can run [generateKeyPair]/[deriveSharedSecret] for ECDH P-256 synchronously. */
expect val supportsSyncEcdhP256: Boolean

/** Whether this platform can run [generateKeyPair]/[deriveSharedSecret] for ECDH P-384 synchronously. */
expect val supportsSyncEcdhP384: Boolean

/** Whether this platform can run [generateKeyPair]/[deriveSharedSecret] for ECDH P-521 synchronously. */
expect val supportsSyncEcdhP521: Boolean

/** Whether [curve] has synchronous native support on this platform. */
fun supportsSync(curve: KeyAgreementCurve): Boolean =
    when (curve) {
        KeyAgreementCurve.X25519 -> supportsSyncX25519
        KeyAgreementCurve.P256 -> supportsSyncEcdhP256
        KeyAgreementCurve.P384 -> supportsSyncEcdhP384
        KeyAgreementCurve.P521 -> supportsSyncEcdhP521
    }

/**
 * Async [generateKeyPair]. On JVM/Android/Apple this delegates to the synchronous native call;
 * on js/wasmJs it drives WebCrypto's Promise-based `generateKey`, so it works in the browser
 * where no synchronous KA exists.
 */
expect suspend fun generateKeyPairAsync(curve: KeyAgreementCurve): KeyAgreementKeyPair

/**
 * Async [deriveSharedSecret]. Same KDF-on-shared-secret and public-key-validation contract; the
 * only difference is it can await WebCrypto on the web. The raw secret is wiped before returning.
 */
expect suspend fun deriveSharedSecretAsync(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer,
    length: Int,
    salt: ReadBuffer? = null,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer
