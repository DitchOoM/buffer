package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.toReadBuffer

/*
 * Hybrid Public Key Encryption — HPKE (RFC 9180) — and its key-encapsulation layer DHKEM.
 *
 * HPKE is composed entirely over the already-landed, native-backed primitive families in this
 * module; NOTHING cryptographic is hand-rolled here:
 *
 *  - DHKEM key encapsulation is built on the key-agreement family (KeyAgreementCurve + the internal
 *    raw-DH seam dhRawSecret) and HKDF. Supported KEMs: DHKEM(X25519, HKDF-SHA256),
 *    DHKEM(P-256, HKDF-SHA256), DHKEM(P-384, HKDF-SHA384), DHKEM(P-521, HKDF-SHA512).
 *  - The KDF layer (HpkeKdf) is HKDF-SHA256/384/512 (the landed Hkdf / HkdfSha384 / HkdfSha512
 *    engines), used for HPKE's LabeledExtract/LabeledExpand.
 *  - The AEAD layer (HpkeAead) is AES-128-GCM / AES-256-GCM / ChaCha20-Poly1305 (the landed
 *    aesGcmSealWithNonceAsync / chaChaPolySeal primitives).
 *
 * Modes: all four RFC 9180 modes are exposed as a typed HpkeMode (never magic ints): Base, Psk,
 * Auth, AuthPsk.
 *
 * Domain separation: every KDF call is domain-separated exactly per RFC 9180 §4. DHKEM uses
 * suite_id = "KEM" || I2OSP(kem_id, 2); the HPKE key schedule uses
 * suite_id = "HPKE" || I2OSP(kem_id, 2) || I2OSP(kdf_id, 2) || I2OSP(aead_id, 2). The
 * LabeledExtract/LabeledExpand constructions prefix "HPKE-v1" and suite_id to every label.
 *
 * Security contract:
 *  - Nonce reuse is impossible by construction. Each context owns a monotonically increasing
 *    per-message sequence number; the AEAD nonce is base_nonce XOR I2OSP(seq, Nn). The sequence is
 *    advanced only after a successful AEAD op, and MessageLimitReached is thrown before the counter
 *    could wrap, so a (key, nonce) pair is never reused.
 *  - Authenticated decryption only. open returns plaintext only if the AEAD tag verifies; a tampered
 *    ciphertext/tag/aad/enc surfaces as the opaque VerificationFailed — no oracle.
 *  - No raw secret material leaves the API. Shared secrets, the HPKE secret, exporter_secret, and
 *    AEAD keys live in wiped SecureBuffers; only AEAD outputs and explicitly-requested export secrets
 *    (via HpkeContext.export) are returned, and no secret ever appears in an exception or log.
 *  - Secret-dependent compares route through constantTimeEquals; this surface performs none that
 *    would leak (AEAD verify is the native primitive's).
 *
 * Platform / capability gating: HPKE is suspend throughout so the web (WebCrypto, Promise-only) is
 * covered. A suite is usable on a platform only if all three of its primitives are: e.g. a
 * ChaCha20-Poly1305 suite is unsupported on the web (ChaCha is not in WebCrypto) and an X25519 KEM
 * is unsupported where the platform lacks X25519. An unsupported suite throws
 * UnsupportedOperationException (never a silent fallback).
 */

// =============================================================================
// Suite components (typed; numeric IDs are RFC 9180 §7 registry values)
// =============================================================================

/** An HPKE KEM (RFC 9180 §7.1). [Nsecret]/[Nenc]/[Npk]/[Nsk] are the per-KEM byte lengths. */
sealed interface HpkeKem {
    /** The RFC 9180 KEM identifier (used in `suite_id`). */
    val id: Int

    /** The underlying key-agreement curve. */
    val curve: KeyAgreementCurve

    /** The KDF this KEM derives with (the KEM's own HKDF, distinct from the suite KDF). */
    val kdf: HpkeKdf

    /** Length of the KEM shared secret (== KDF hash length). */
    val nSecret: Int

    /** Length of an encapsulated key (== public-key encoding length). */
    val nEnc: Int

    /** Length of a serialized public key. */
    val nPk: Int

    /** Length of a serialized private key. */
    val nSk: Int

    /** Human-readable name; never carries secrets. */
    val kemName: String

    /** DHKEM(X25519, HKDF-SHA256). */
    data object DhkemX25519HkdfSha256 : HpkeKem {
        override val id: Int get() = 0x0020
        override val curve: KeyAgreementCurve get() = KeyAgreementCurve.X25519
        override val kdf: HpkeKdf get() = HpkeKdf.HkdfSha256
        override val nSecret: Int get() = 32
        override val nEnc: Int get() = 32
        override val nPk: Int get() = 32
        override val nSk: Int get() = 32
        override val kemName: String get() = "DHKEM(X25519, HKDF-SHA256)"
    }

    /** DHKEM(P-256, HKDF-SHA256). */
    data object DhkemP256HkdfSha256 : HpkeKem {
        override val id: Int get() = 0x0010
        override val curve: KeyAgreementCurve get() = KeyAgreementCurve.P256
        override val kdf: HpkeKdf get() = HpkeKdf.HkdfSha256
        override val nSecret: Int get() = 32
        override val nEnc: Int get() = 65
        override val nPk: Int get() = 65
        override val nSk: Int get() = 32
        override val kemName: String get() = "DHKEM(P-256, HKDF-SHA256)"
    }

    /** DHKEM(P-384, HKDF-SHA384). */
    data object DhkemP384HkdfSha384 : HpkeKem {
        override val id: Int get() = 0x0011
        override val curve: KeyAgreementCurve get() = KeyAgreementCurve.P384
        override val kdf: HpkeKdf get() = HpkeKdf.HkdfSha384
        override val nSecret: Int get() = 48
        override val nEnc: Int get() = 97
        override val nPk: Int get() = 97
        override val nSk: Int get() = 48
        override val kemName: String get() = "DHKEM(P-384, HKDF-SHA384)"
    }

    /** DHKEM(P-521, HKDF-SHA512). */
    data object DhkemP521HkdfSha512 : HpkeKem {
        override val id: Int get() = 0x0012
        override val curve: KeyAgreementCurve get() = KeyAgreementCurve.P521
        override val kdf: HpkeKdf get() = HpkeKdf.HkdfSha512
        override val nSecret: Int get() = 64
        override val nEnc: Int get() = 133
        override val nPk: Int get() = 133
        override val nSk: Int get() = 66
        override val kemName: String get() = "DHKEM(P-521, HKDF-SHA512)"
    }
}

/** An HPKE KDF (RFC 9180 §7.2). [nh] is the hash output length. */
sealed interface HpkeKdf {
    /** The RFC 9180 KDF identifier. */
    val id: Int

    /** The hash output length `Nh`. */
    val nh: Int

    /** HKDF-Extract over this hash: writes `Nh` bytes into [dest]. Null/empty salt ⇒ zero block. */
    fun extractInto(
        salt: ReadBuffer?,
        ikm: ReadBuffer,
        dest: WriteBuffer,
    )

    /** HKDF-Expand over this hash: writes [length] bytes into [dest] from [prk] and optional [info]. */
    fun expandInto(
        prk: ReadBuffer,
        info: ReadBuffer?,
        length: Int,
        dest: WriteBuffer,
    )

    data object HkdfSha256 : HpkeKdf {
        override val id: Int get() = 0x0001
        override val nh: Int get() = 32

        override fun extractInto(
            salt: ReadBuffer?,
            ikm: ReadBuffer,
            dest: WriteBuffer,
        ) = Hkdf.extractInto(salt.toSalt(), ikm, dest)

        override fun expandInto(
            prk: ReadBuffer,
            info: ReadBuffer?,
            length: Int,
            dest: WriteBuffer,
        ) = Hkdf.expandInto(prk, info.toInfo(), length, dest)
    }

    data object HkdfSha384 : HpkeKdf {
        override val id: Int get() = 0x0002
        override val nh: Int get() = 48

        override fun extractInto(
            salt: ReadBuffer?,
            ikm: ReadBuffer,
            dest: WriteBuffer,
        ) = com.ditchoom.buffer.crypto.HkdfSha384
            .extractInto(salt.toSalt(), ikm, dest)

        override fun expandInto(
            prk: ReadBuffer,
            info: ReadBuffer?,
            length: Int,
            dest: WriteBuffer,
        ) = com.ditchoom.buffer.crypto.HkdfSha384
            .expandInto(prk, info.toInfo(), length, dest)
    }

    data object HkdfSha512 : HpkeKdf {
        override val id: Int get() = 0x0003
        override val nh: Int get() = 64

        override fun extractInto(
            salt: ReadBuffer?,
            ikm: ReadBuffer,
            dest: WriteBuffer,
        ) = com.ditchoom.buffer.crypto.HkdfSha512
            .extractInto(salt.toSalt(), ikm, dest)

        override fun expandInto(
            prk: ReadBuffer,
            info: ReadBuffer?,
            length: Int,
            dest: WriteBuffer,
        ) = com.ditchoom.buffer.crypto.HkdfSha512
            .expandInto(prk, info.toInfo(), length, dest)
    }
}

/** An HPKE AEAD (RFC 9180 §7.3). [nk]/[nn]/[nt] are key/nonce/tag lengths. */
sealed interface HpkeAead {
    /** The RFC 9180 AEAD identifier. */
    val id: Int

    /** Key length `Nk`. */
    val nk: Int

    /** Nonce length `Nn`. */
    val nn: Int get() = AEAD_NONCE_BYTES

    /** Tag length `Nt`. */
    val nt: Int get() = AEAD_TAG_BYTES

    /** Human-readable name; never carries secrets. */
    val aeadName: String

    data object Aes128Gcm : HpkeAead {
        override val id: Int get() = 0x0001
        override val nk: Int get() = AES_128_KEY_BYTES
        override val aeadName: String get() = "AES-128-GCM"
    }

    data object Aes256Gcm : HpkeAead {
        override val id: Int get() = 0x0002
        override val nk: Int get() = AES_256_KEY_BYTES
        override val aeadName: String get() = "AES-256-GCM"
    }

    data object ChaCha20Poly1305 : HpkeAead {
        override val id: Int get() = 0x0003
        override val nk: Int get() = CHACHA_KEY_BYTES
        override val aeadName: String get() = "ChaCha20Poly1305"
    }
}

/** A full HPKE cipher suite: a [kem], [kdf], and [aead] triple. */
data class HpkeSuite(
    val kem: HpkeKem,
    val kdf: HpkeKdf,
    val aead: HpkeAead,
) {
    /** `suite_id = "HPKE" || I2OSP(kem_id, 2) || I2OSP(kdf_id, 2) || I2OSP(aead_id, 2)` (RFC 9180 §5.1). */
    internal fun suiteId(): ReadBuffer {
        val out = BufferFactory.Default.allocate(4 + 6)
        out.writeBytes(HPKE_ASCII)
        i2osp2(kem.id, out)
        i2osp2(kdf.id, out)
        i2osp2(aead.id, out)
        out.resetForRead()
        return out
    }
}

/** The HPKE establishment mode (RFC 9180 §5.1). [value] is the wire `mode` byte. */
sealed interface HpkeMode {
    val value: Int

    /** Mode 0: no pre-shared key, no sender authentication. */
    data object Base : HpkeMode {
        override val value: Int get() = 0x00
    }

    /** Mode 1: pre-shared key, no sender authentication. */
    data object Psk : HpkeMode {
        override val value: Int get() = 0x01
    }

    /** Mode 2: sender authentication (static-static DH), no PSK. */
    data object Auth : HpkeMode {
        override val value: Int get() = 0x02
    }

    /** Mode 3: both pre-shared key and sender authentication. */
    data object AuthPsk : HpkeMode {
        override val value: Int get() = 0x03
    }
}

/**
 * A pre-shared key for the [HpkeMode.Psk] / [HpkeMode.AuthPsk] modes: the secret [psk] paired with
 * its non-secret identifier [pskId]. Per RFC 9180 §5.1.1 a PSK must be at least 32 bytes.
 *
 * The [psk] bytes are copied into a wiped [SecureBuffer]; close this (`use {}`) to zero them. The
 * [pskId] is not secret and is held in an ordinary buffer.
 */
class HpkePsk private constructor(
    private val psk: PlatformBuffer,
    internal val pskId: ReadBuffer,
) : AutoCloseable {
    private var closed = false

    /** Zeroes and frees the PSK bytes. Idempotent. */
    override fun close() {
        if (!closed) {
            closed = true
            psk.freeNativeMemory()
        }
    }

    /** The live PSK bytes; throws if the PSK has been [close]d. */
    internal fun requireOpen(): PlatformBuffer {
        check(!closed) { "HpkePsk already closed" }
        return psk
    }

    companion object {
        /** Minimum PSK length recommended by RFC 9180 §5.1.1 (32 bytes). */
        const val MIN_PSK_BYTES: Int = 32

        /**
         * Builds a PSK from its secret [psk] and identifier [pskId] (both read non-destructively).
         * The secret is copied into a wiped [SecureBuffer]; [psk] must be at least [MIN_PSK_BYTES].
         */
        fun of(
            psk: ReadBuffer,
            pskId: ReadBuffer,
        ): HpkePsk {
            require(psk.remaining() >= MIN_PSK_BYTES) {
                "PSK must be at least $MIN_PSK_BYTES bytes, was ${psk.remaining()}"
            }
            val secure = secureScratch.allocate(psk.remaining())
            copyInto(psk, secure)
            secure.resetForRead()
            val idCopy = BufferFactory.Default.allocate(pskId.remaining())
            copyInto(pskId, idCopy)
            idCopy.resetForRead()
            return HpkePsk(secure, idCopy)
        }
    }
}

private const val MESSAGE_LIMIT_MESSAGE = "HPKE message sequence number would overflow; rekey required"

/** Raised when a context's per-message sequence number would overflow (RFC 9180 §5.2). Carries no secret. */
class MessageLimitReached internal constructor() : CryptoMisuseException(MESSAGE_LIMIT_MESSAGE)

// =============================================================================
// Capability witness: operations live ON the witness the platform supplies
// =============================================================================

private fun aeadSupported(aead: HpkeAead): Boolean =
    when (aead) {
        // AES-GCM is reachable on every platform: its witness [CryptoCapabilities.aesGcm] is always
        // Blocking (native) or AsyncOnly (web), never Unavailable — and HPKE is suspend throughout.
        HpkeAead.Aes128Gcm, HpkeAead.Aes256Gcm -> true
        HpkeAead.ChaCha20Poly1305 -> chaChaPolyReachable
    }

/**
 * HPKE operations for a supported suite. Every operation is `suspend` (HPKE drives WebCrypto on the
 * web, where there is no synchronous path), so unlike AEAD/signatures/key-agreement there is no
 * blocking variant — hence the two-state [HpkeSupport] witness. Single-shot seal/open and the four
 * RFC 9180 setup modes take [Info] / [Aad] role types (never `null`). The per-message
 * [HpkeContext.Sender.seal] / [HpkeContext.Receiver.open] / [HpkeContext.export] live on the
 * context these return.
 */
interface HpkeOps {
    /** The suite these ops are bound to. */
    val suite: HpkeSuite

    /** Single-shot Base-mode seal (RFC 9180 §6.1): `enc || ciphertext`. */
    suspend fun sealBase(
        recipientPublicKey: HpkePublicKey,
        info: Info,
        plaintext: ReadBuffer,
        aad: Aad = Aad.None,
        factory: BufferFactory = BufferFactory.Default,
    ): HpkeSealed

    /** Single-shot Base-mode open (RFC 9180 §6.1). */
    suspend fun openBase(
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: Info,
        ciphertext: ReadBuffer,
        aad: Aad = Aad.None,
        factory: BufferFactory = BufferFactory.Default,
    ): PlatformBuffer

    /** SetupBaseS: a Base-mode sender context + the encapsulated key. */
    suspend fun setupBaseSender(
        recipientPublicKey: HpkePublicKey,
        info: Info,
    ): HpkeSenderSetup

    /** SetupPSKS: a PSK-mode sender context. */
    suspend fun setupPskSender(
        recipientPublicKey: HpkePublicKey,
        info: Info,
        psk: HpkePsk,
    ): HpkeSenderSetup

    /** SetupAuthS: a sender-authenticated context using the sender's static private key. */
    suspend fun setupAuthSender(
        recipientPublicKey: HpkePublicKey,
        info: Info,
        senderPrivateKey: HpkePrivateKey,
    ): HpkeSenderSetup

    /** SetupAuthPSKS: PSK + sender authentication. */
    suspend fun setupAuthPskSender(
        recipientPublicKey: HpkePublicKey,
        info: Info,
        psk: HpkePsk,
        senderPrivateKey: HpkePrivateKey,
    ): HpkeSenderSetup

    /** SetupBaseR: a Base-mode receiver context. */
    suspend fun setupBaseReceiver(
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: Info,
    ): HpkeContext.Receiver

    /** SetupPSKR: a PSK-mode receiver context. */
    suspend fun setupPskReceiver(
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: Info,
        psk: HpkePsk,
    ): HpkeContext.Receiver

    /** SetupAuthR: a sender-authenticated receiver context. */
    suspend fun setupAuthReceiver(
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: Info,
        senderPublicKey: HpkePublicKey,
    ): HpkeContext.Receiver

    /** SetupAuthPSKR: PSK + sender authentication. */
    suspend fun setupAuthPskReceiver(
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: Info,
        psk: HpkePsk,
        senderPublicKey: HpkePublicKey,
    ): HpkeContext.Receiver
}

/**
 * Capability witness for an HPKE [HpkeSuite] on this platform. HPKE is `suspend`-only, so there are
 * just two states: [Supported] (every primitive — KEM curve and AEAD — is reachable; the [ops] are
 * usable) and [Unsupported] (a primitive is absent — e.g. ChaCha20-Poly1305 on the web, or a curve
 * the provider lacks). Reached via [CryptoCapabilities.hpke]; an unsupported suite's ops are not a
 * member of the resolved witness, so they are unrepresentable rather than a runtime throw.
 */
sealed interface HpkeSupport {
    /** The suite cannot be used here; [missing] names the absent primitive(s), for diagnostics. */
    data class Unsupported(
        val missing: String,
    ) : HpkeSupport

    /** The suite is usable; [ops] performs its operations. */
    data class Supported(
        val ops: HpkeOps,
    ) : HpkeSupport
}

/**
 * The HPKE capability for [suite] on this platform: [HpkeSupport.Supported] when its KEM curve is
 * reachable (synchronously on native, or via async WebCrypto on the web) and its AEAD is available,
 * else [HpkeSupport.Unsupported] naming what is missing. Whether the web engine actually provides a
 * curve it advertises (e.g. X25519) is feature-detected at op time, exactly as the key-agreement
 * witness reports [KeyAgreementSupport.AsyncOnly] there.
 */
fun CryptoCapabilities.hpke(suite: HpkeSuite): HpkeSupport {
    val missing =
        buildList {
            if (CryptoCapabilities.keyAgreement(suite.kem.curve) is KeyAgreementSupport.Unavailable) {
                add(suite.kem.kemName)
            }
            if (!aeadSupported(suite.aead)) add(suite.aead.aeadName)
        }
    return if (missing.isEmpty()) {
        HpkeSupport.Supported(HpkeOpsImpl(suite))
    } else {
        HpkeSupport.Unsupported(missing.joinToString(", "))
    }
}

/** Common HPKE ops over the internal setup / single-shot functions; converts role types to bytes. */
internal class HpkeOpsImpl(
    override val suite: HpkeSuite,
) : HpkeOps {
    private fun Info.orEmpty(): ReadBuffer = bytesOrNull ?: EMPTY

    override suspend fun sealBase(
        recipientPublicKey: HpkePublicKey,
        info: Info,
        plaintext: ReadBuffer,
        aad: Aad,
        factory: BufferFactory,
    ): HpkeSealed = hpkeSealBase(suite, recipientPublicKey, info.orEmpty(), plaintext, aad.bytesOrNull, factory)

    override suspend fun openBase(
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: Info,
        ciphertext: ReadBuffer,
        aad: Aad,
        factory: BufferFactory,
    ): PlatformBuffer = hpkeOpenBase(suite, recipientPrivateKey, enc, info.orEmpty(), ciphertext, aad.bytesOrNull, factory)

    override suspend fun setupBaseSender(
        recipientPublicKey: HpkePublicKey,
        info: Info,
    ): HpkeSenderSetup = hpkeSetupBaseSender(suite, recipientPublicKey, info.orEmpty())

    override suspend fun setupPskSender(
        recipientPublicKey: HpkePublicKey,
        info: Info,
        psk: HpkePsk,
    ): HpkeSenderSetup = hpkeSetupPskSender(suite, recipientPublicKey, info.orEmpty(), psk)

    override suspend fun setupAuthSender(
        recipientPublicKey: HpkePublicKey,
        info: Info,
        senderPrivateKey: HpkePrivateKey,
    ): HpkeSenderSetup = hpkeSetupAuthSender(suite, recipientPublicKey, info.orEmpty(), senderPrivateKey)

    override suspend fun setupAuthPskSender(
        recipientPublicKey: HpkePublicKey,
        info: Info,
        psk: HpkePsk,
        senderPrivateKey: HpkePrivateKey,
    ): HpkeSenderSetup = hpkeSetupAuthPskSender(suite, recipientPublicKey, info.orEmpty(), psk, senderPrivateKey)

    override suspend fun setupBaseReceiver(
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: Info,
    ): HpkeContext.Receiver = hpkeSetupBaseReceiver(suite, recipientPrivateKey, enc, info.orEmpty())

    override suspend fun setupPskReceiver(
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: Info,
        psk: HpkePsk,
    ): HpkeContext.Receiver = hpkeSetupPskReceiver(suite, recipientPrivateKey, enc, info.orEmpty(), psk)

    override suspend fun setupAuthReceiver(
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: Info,
        senderPublicKey: HpkePublicKey,
    ): HpkeContext.Receiver = hpkeSetupAuthReceiver(suite, recipientPrivateKey, enc, info.orEmpty(), senderPublicKey)

    override suspend fun setupAuthPskReceiver(
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: Info,
        psk: HpkePsk,
        senderPublicKey: HpkePublicKey,
    ): HpkeContext.Receiver = hpkeSetupAuthPskReceiver(suite, recipientPrivateKey, enc, info.orEmpty(), psk, senderPublicKey)
}

// =============================================================================
// Public KEM key types (thin typed wrappers over the key-agreement family)
// =============================================================================

/** An HPKE recipient/sender key pair for [kem]. Close to wipe the private key. */
sealed interface HpkeKeyPair : AutoCloseable {
    /** The KEM this pair belongs to. */
    val kem: HpkeKem

    /** Whether the key material is in software memory or hardware-backed. */
    val provenance: KeyProvenance

    /** The public key. */
    val publicKey: HpkePublicKey

    /** The serialized public-key bytes (`kem.nPk` bytes), read-ready. */
    val publicKeyBytes: ReadBuffer

    /**
     * The private key, carrying its paired public key (DHKEM `Decap`/`AuthEncap` need `pkRm`/`pkSm`,
     * which cannot be re-derived from a bare scalar without a native op). Sharing the underlying
     * key-agreement private key — do not close this independently of the pair.
     */
    val privateKey: HpkePrivateKey
}

/** In-memory [HpkeKeyPair] wrapping a software key-agreement pair. */
internal class InMemoryHpkeKeyPair(
    override val kem: HpkeKem,
    val keyPair: KeyAgreementKeyPair,
) : HpkeKeyPair {
    override val provenance: KeyProvenance get() = keyPair.privateKey.provenance
    override val publicKey: HpkePublicKey get() = hpkePublicKeyOf(kem, keyPair.publicKey)
    override val publicKeyBytes: ReadBuffer get() = keyPair.publicKey.encoded
    override val privateKey: HpkePrivateKey get() = hpkePrivateKeyOf(kem, keyPair.privateKey, keyPair.publicKey.encoded)

    override fun close() {
        keyPair.close()
    }
}

/** A recipient/sender public key for [kem] (not secret). */
sealed interface HpkePublicKey {
    /** The KEM this key belongs to. */
    val kem: HpkeKem

    /** Whether the key material is in software memory or hardware-backed. */
    val provenance: KeyProvenance

    /** The serialized public-key bytes (`kem.nPk` bytes), read-ready. */
    val encoded: ReadBuffer
}

/** In-memory [HpkePublicKey] wrapping a software key-agreement public key. */
internal class InMemoryHpkePublicKey(
    override val kem: HpkeKem,
    val key: KeyAgreementPublicKey,
) : HpkePublicKey {
    override val provenance: KeyProvenance get() = key.provenance
    override val encoded: ReadBuffer get() = key.encoded
}

/**
 * A recipient/sender private key for [kem], carrying its paired public key (required by DHKEM, which
 * needs `pkRm`/`pkSm` and cannot derive a public key from a bare scalar). Close to wipe the scalar.
 */
sealed interface HpkePrivateKey : AutoCloseable {
    /** The KEM this key belongs to. */
    val kem: HpkeKem

    /** Whether the key material is in software memory or hardware-backed. */
    val provenance: KeyProvenance
}

/** In-memory [HpkePrivateKey] wrapping a software key-agreement private key + its paired public key. */
internal class InMemoryHpkePrivateKey(
    override val kem: HpkeKem,
    val key: KeyAgreementPrivateKey,
    val publicKeyEncoded: ReadBuffer,
) : HpkePrivateKey {
    override val provenance: KeyProvenance get() = key.provenance

    override fun close() {
        key.close()
    }
}

internal fun hpkeKeyPairOf(
    kem: HpkeKem,
    keyPair: KeyAgreementKeyPair,
): HpkeKeyPair = InMemoryHpkeKeyPair(kem, keyPair)

internal fun hpkePublicKeyOf(
    kem: HpkeKem,
    key: KeyAgreementPublicKey,
): HpkePublicKey = InMemoryHpkePublicKey(kem, key)

internal fun hpkePrivateKeyOf(
    kem: HpkeKem,
    key: KeyAgreementPrivateKey,
    publicKeyEncoded: ReadBuffer,
): HpkePrivateKey = InMemoryHpkePrivateKey(kem, key, publicKeyEncoded)

/** The wrapped key-agreement public key — internal seam (only in-memory keys reach it today). */
internal fun HpkePublicKey.keyAgreementKey(): KeyAgreementPublicKey =
    when (this) {
        is InMemoryHpkePublicKey -> key
    }

/** The wrapped key-agreement private key — internal seam. */
internal fun HpkePrivateKey.keyAgreementKey(): KeyAgreementPrivateKey =
    when (this) {
        is InMemoryHpkePrivateKey -> key
    }

/** The paired public key encoding carried alongside a private key (DHKEM `pkRm`/`pkSm`). */
internal fun HpkePrivateKey.publicKeyEncoded(): ReadBuffer =
    when (this) {
        is InMemoryHpkePrivateKey -> publicKeyEncoded
    }

/** Generates a fresh [kem] key pair from the platform CSPRNG. */
suspend fun hpkeGenerateKeyPair(kem: HpkeKem): HpkeKeyPair = hpkeKeyPairOf(kem, keyAgreementAsyncOps(kem.curve).generateKeyPair())

/** Imports a recipient/sender public key for [kem] from its serialized encoding (`kem.nPk` bytes). */
fun hpkeImportPublicKey(
    kem: HpkeKem,
    encoded: ReadBuffer,
): HpkePublicKey = hpkePublicKeyOf(kem, KeyAgreementPublicKey.of(kem.curve, encoded))

/**
 * Imports a recipient/sender private key for [kem] from its serialized scalar (`kem.nSk` bytes) and
 * the paired public key (`kem.nPk` bytes). Both are required: DHKEM `Decap`/`AuthEncap` need the
 * public key (`pkRm`/`pkSm`), which cannot be re-derived from the scalar without a native op.
 */
fun hpkeImportPrivateKey(
    kem: HpkeKem,
    privateEncoded: ReadBuffer,
    publicEncoded: ReadBuffer,
): HpkePrivateKey {
    require(publicEncoded.remaining() == kem.nPk) {
        "${kem.kemName} public key must be ${kem.nPk} bytes, was ${publicEncoded.remaining()}"
    }
    val pubCopy = copyBuffer(publicEncoded, BufferFactory.Default)
    return hpkePrivateKeyOf(kem, importPrivateKey(kem.curve, privateEncoded), pubCopy)
}

// =============================================================================
// Encapsulation results
// =============================================================================

/** The sender's setup result: an [HpkeContext.Sender] and the encapsulated key [enc] to transmit. */
class HpkeSenderSetup internal constructor(
    val context: HpkeContext.Sender,
    /** The encapsulated key (`kem.nEnc` bytes), read-ready, to send to the recipient. Not secret. */
    val enc: ReadBuffer,
)

// =============================================================================
// Single-shot API
// =============================================================================

/**
 * Single-shot HPKE seal (RFC 9180 §6.1, Base mode): encapsulates to [recipientPublicKey],
 * encrypts [plaintext] with [aad], and returns `enc || ciphertext` framing via [HpkeSealed].
 * The context exists for the single message only and is closed (its derived AEAD key, base
 * nonce, and exporter secret wiped) before returning; the outputs are independent copies.
 */
internal suspend fun hpkeSealBase(
    suite: HpkeSuite,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
    plaintext: ReadBuffer,
    aad: ReadBuffer? = null,
    factory: BufferFactory = BufferFactory.Default,
): HpkeSealed {
    val setup = hpkeSetupBaseSender(suite, recipientPublicKey, info)
    return try {
        val ct = setup.context.seal(plaintext, aad.toAad(), factory)
        HpkeSealed(copyBuffer(setup.enc, factory), ct)
    } finally {
        setup.context.close()
    }
}

/** Single-shot HPKE open (RFC 9180 §6.1, Base mode): decapsulates [enc] and decrypts. */
internal suspend fun hpkeOpenBase(
    suite: HpkeSuite,
    recipientPrivateKey: HpkePrivateKey,
    enc: ReadBuffer,
    info: ReadBuffer,
    ciphertext: ReadBuffer,
    aad: ReadBuffer? = null,
    factory: BufferFactory = BufferFactory.Default,
): PlatformBuffer {
    val ctx = hpkeSetupBaseReceiver(suite, recipientPrivateKey, enc, info)
    return try {
        ctx.open(ciphertext, aad.toAad(), factory)
    } finally {
        ctx.close()
    }
}

/** `enc || ciphertext` output of [hpkeSealBase]. Both buffers are read-ready. */
class HpkeSealed internal constructor(
    /** The encapsulated key, `suite.kem.nEnc` bytes. */
    val enc: ReadBuffer,
    /** The AEAD ciphertext `ct || tag`. */
    val ciphertext: PlatformBuffer,
)

// =============================================================================
// Internal helpers: I2OSP, buffer copies, labels
// =============================================================================

internal val HPKE_V1: ByteArray = "HPKE-v1".encodeToByteArray()
internal val HPKE_ASCII: ByteArray = "HPKE".encodeToByteArray()
internal val KEM_ASCII: ByteArray = "KEM".encodeToByteArray()

private const val BYTE_MASK = 0xFF

/** Writes [value] as a 2-byte big-endian (I2OSP(value, 2)) into [dest]. */
internal fun i2osp2(
    value: Int,
    dest: WriteBuffer,
) {
    dest.writeByte(((value ushr Byte.SIZE_BITS) and BYTE_MASK).toByte())
    dest.writeByte((value and BYTE_MASK).toByte())
}

/**
 * Copies [source]'s remaining bytes into [dest] without consuming [source]: the bulk
 * [WriteBuffer.write] advances the source to its limit, so its position is saved and restored.
 * Callers reuse the same source across calls (e.g. `suite_id` and the key-schedule `secret`),
 * so the non-destructive contract is load-bearing.
 */
internal fun copyInto(
    source: ReadBuffer,
    dest: WriteBuffer,
) {
    if (source.remaining() == 0) return
    val mark = source.position()
    dest.write(source)
    source.position(mark)
}

/** A fresh read-ready copy of [source]'s remaining bytes from [factory]. */
internal fun copyBuffer(
    source: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer {
    val out = factory.allocate(source.remaining())
    copyInto(source, out)
    out.resetForRead()
    return out
}

/** A read-ready buffer of [text]'s UTF-8 bytes. */
internal fun asciiBuffer(text: String): ReadBuffer = text.toReadBuffer()
