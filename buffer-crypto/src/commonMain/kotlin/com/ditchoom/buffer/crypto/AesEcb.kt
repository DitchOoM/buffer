package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/*
 * Raw single-block AES (ECB) — the bare AES block permutation, not a general-purpose cipher.
 *
 * This is a deliberately low-level primitive: it enciphers exactly one 16-byte block under an
 * AES-128 or AES-256 key, with **no** IV, no chaining, no padding, and no authentication. It exists
 * so that standard constructions which use AES only as a keyed pseudorandom permutation can be built
 * inside this module rather than reaching for raw platform crypto.
 *
 * WARNING — do NOT use this to encrypt application data. ECB reveals plaintext structure: equal
 * plaintext blocks produce equal ciphertext blocks under the same key, so it hides nothing about
 * message content and is unauthenticated (it detects no tampering). For confidentiality use the
 * AEAD family ([CryptoCapabilities.aesGcm] / [CryptoCapabilities.chaChaPoly]). The only intended
 * uses of this primitive are keystream/PRF constructions that run AES forward once, e.g. **DTLS 1.3
 * / TLS 1.3 record sequence-number encryption** (RFC 9147 §4.2.3 / RFC 8446 §5.4.1): the mask is
 * `AES-ECB-encrypt(sn_key, ciphertext_sample)` and both sender and receiver recover the sequence
 * number by XORing that mask — so the forward ([encryptBlock]) direction alone suffices there.
 *
 * Availability. The primitive is reached through a capability witness ([AesEcb]), like the AEAD
 * family, so a platform that cannot provide it is a `sealed` [AesEcb.Unavailable] value rather than a
 * throwing call. AES-ECB is [AesEcb.Blocking] (native, synchronous) on JVM, Android, Apple, and
 * Linux, and [AesEcb.Unavailable] on js/wasmJs — WebCrypto exposes no ECB mode, and DTLS 1.3 (the
 * motivating consumer) does not run on the web, so it is deliberately not polyfilled there.
 */

/** AES block size in bytes — the fixed input/output width of a single [CryptoCapabilities.aesEcb] op. */
const val AES_ECB_BLOCK_BYTES: Int = 16

// =============================================================================
// Key type (sealed: only the library-provided in-memory key; impl is internal)
// =============================================================================

/**
 * A raw-AES key for the single-block ECB primitive, carrying its [sizeBits] (128 or 256) so a key of
 * one size cannot be silently used where another was meant. A distinct type from [AesGcmKey] so an
 * authenticated-encryption key and a raw-block key can never be cross-used.
 *
 * `sealed` with a single internal in-memory implementation produced by [of]; downstream cannot supply
 * an attacker-controlled key. Key material is copied into a buffer from the factory passed to [of] —
 * by default a secure deterministic one (matching [AesGcmKey] and [SigningKey]), so the copy is zeroed
 * and freed by [close]. There is no hardware-backed variant: raw single-block AES is a software
 * construction seam, not a key-custody surface.
 */
sealed interface AesEcbKey : AutoCloseable {
    /** 128 or 256 — the AES key size in bits. */
    val sizeBits: Int

    /** This key's custody. Always [KeyCustody.ExportableSoftware] for the in-memory key. */
    val custody: KeyCustody

    /** Whether the key material is in software memory or hardware-backed. Derived from [custody]. */
    val provenance: KeyProvenance get() = custody.provenance

    /** Key size in bytes (16 for AES-128, 32 for AES-256). */
    val sizeBytes: Int get() = sizeBits / 8

    companion object {
        /**
         * Wraps [key]'s remaining bytes as an in-memory raw-AES key. The length must be exactly 16
         * (AES-128) or 32 (AES-256) bytes; any other length is rejected. The bytes are copied into a
         * buffer from [factory] — secure and deterministic by default, so the copy is wiped and freed
         * by [close] (align with [AesGcmKey]; pass a non-secure factory to opt out).
         */
        fun of(
            key: ReadBuffer,
            factory: BufferFactory = BufferFactory.deterministicSecure(),
        ): AesEcbKey {
            val n = key.remaining()
            require(n == AES_128_KEY_BYTES || n == AES_256_KEY_BYTES) {
                "AES-ECB key must be $AES_128_KEY_BYTES or $AES_256_KEY_BYTES bytes, was $n"
            }
            return InMemoryAesEcbKey(n * Byte.SIZE_BITS, copyBuffer(key, factory))
        }
    }
}

/** In-memory [AesEcbKey]: holds the raw key bytes, freed (and wiped, on a secure backing) on [close]. */
internal class InMemoryAesEcbKey(
    override val sizeBits: Int,
    private val material: PlatformBuffer,
) : AesEcbKey {
    override val custody: KeyCustody get() = KeyCustody.ExportableSoftware
    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            material.freeNativeMemory()
        }
    }

    /** The live key material; throws if the key has been [close]d. */
    fun requireOpen(): PlatformBuffer {
        check(!closed) { "AesEcbKey already closed" }
        return material
    }
}

/** The in-memory key material for the blocking native primitives; throws if the key has been closed. */
internal fun AesEcbKey.requireInMemoryMaterial(): PlatformBuffer =
    when (this) {
        is InMemoryAesEcbKey -> requireOpen()
    }

// =============================================================================
// Capability witness: operations live ON the witness the platform supplies
// =============================================================================

/**
 * Single-block AES (ECB) operations. Both directions encipher/decipher exactly one
 * [AES_ECB_BLOCK_BYTES]-byte block; any other input length is rejected. Synchronous — every platform
 * that offers the primitive at all offers it natively and blocking.
 */
interface AesEcbOps {
    /**
     * Enciphers the single [AES_ECB_BLOCK_BYTES]-byte block in [block] under [key], writing the
     * ciphertext block into [dest] at its current position (advancing it by [AES_ECB_BLOCK_BYTES]).
     * [block] must have exactly [AES_ECB_BLOCK_BYTES] bytes remaining and [dest] room for that many.
     * This is the forward AES permutation used by DTLS 1.3 sequence-number masking.
     */
    fun encryptBlock(
        key: AesEcbKey,
        block: ReadBuffer,
        dest: WriteBuffer,
    )

    /**
     * Deciphers the single [AES_ECB_BLOCK_BYTES]-byte block in [block] under [key], writing the
     * recovered block into [dest]. The inverse of [encryptBlock]; not needed for keystream/masking
     * uses (those run AES forward only) but provided for constructions that invert the permutation.
     */
    fun decryptBlock(
        key: AesEcbKey,
        block: ReadBuffer,
        dest: WriteBuffer,
    )

    /** [encryptBlock] into a fresh read-ready [AES_ECB_BLOCK_BYTES]-byte buffer from [factory]. */
    fun encryptBlock(
        key: AesEcbKey,
        block: ReadBuffer,
        factory: BufferFactory = BufferFactory.Default,
    ): PlatformBuffer {
        val out = factory.allocate(AES_ECB_BLOCK_BYTES)
        encryptBlock(key, block, out)
        out.resetForRead()
        return out
    }

    /** [decryptBlock] into a fresh read-ready [AES_ECB_BLOCK_BYTES]-byte buffer from [factory]. */
    fun decryptBlock(
        key: AesEcbKey,
        block: ReadBuffer,
        factory: BufferFactory = BufferFactory.Default,
    ): PlatformBuffer {
        val out = factory.allocate(AES_ECB_BLOCK_BYTES)
        decryptBlock(key, block, out)
        out.resetForRead()
        return out
    }
}

/**
 * Capability witness for the single-block AES primitive: [Blocking] on JVM/Android/Apple/Linux,
 * [Unavailable] on js/wasmJs (no ECB in WebCrypto). There is no async variant — a platform either has
 * a synchronous native block cipher or does not offer the primitive at all.
 */
sealed interface AesEcb {
    /** The primitive is not available on this platform (cannot be reached at all). */
    data object Unavailable : AesEcb

    /** A synchronous native path is available. */
    data class Blocking(
        val ops: AesEcbOps,
    ) : AesEcb
}

/**
 * The single-block AES (ECB) capability on this platform: [AesEcb.Blocking] on JVM/Android/Apple/Linux,
 * [AesEcb.Unavailable] on js/wasmJs. See the file header for the (narrow) intended uses and the
 * standing ECB warning.
 */
expect val CryptoCapabilities.aesEcb: AesEcb

// =============================================================================
// Witness op implementation (common — validates, then calls the platform seam)
// =============================================================================

/** Single-block AES ops over the synchronous native primitives. */
internal object AesEcbBlockingOps : AesEcbOps {
    override fun encryptBlock(
        key: AesEcbKey,
        block: ReadBuffer,
        dest: WriteBuffer,
    ) {
        requireSingleBlock(block)
        requireBlockRoom(dest)
        aesEcbEncryptBlock(key, block, dest)
    }

    override fun decryptBlock(
        key: AesEcbKey,
        block: ReadBuffer,
        dest: WriteBuffer,
    ) {
        requireSingleBlock(block)
        requireBlockRoom(dest)
        aesEcbDecryptBlock(key, block, dest)
    }
}

/** Whether the single-block AES primitive is reachable here (the witness is [AesEcb.Blocking]). */
internal val aesEcbBlockingAvailable: Boolean get() = CryptoCapabilities.aesEcb is AesEcb.Blocking

// =============================================================================
// Low-level one-shot primitives (synchronous, native-or-throw) — internal seam
// =============================================================================

/**
 * Enciphers the single [AES_ECB_BLOCK_BYTES]-byte block in [block] under [key], writing the ciphertext
 * block into [dest] at its current position. The common witness op validates the block/room; this seam
 * is the raw platform call.
 */
internal expect fun aesEcbEncryptBlock(
    key: AesEcbKey,
    block: ReadBuffer,
    dest: WriteBuffer,
)

/** Inverse of [aesEcbEncryptBlock]. */
internal expect fun aesEcbDecryptBlock(
    key: AesEcbKey,
    block: ReadBuffer,
    dest: WriteBuffer,
)

// =============================================================================
// Internal validation helpers (shared by the witness op)
// =============================================================================

/** Rejects an input that is not exactly one [AES_ECB_BLOCK_BYTES]-byte block. */
internal fun requireSingleBlock(block: ReadBuffer) {
    require(block.remaining() == AES_ECB_BLOCK_BYTES) {
        "AES-ECB operates on a single $AES_ECB_BLOCK_BYTES-byte block; input had ${block.remaining()} bytes"
    }
}

/** Rejects a destination without room for one [AES_ECB_BLOCK_BYTES]-byte block. */
internal fun requireBlockRoom(dest: WriteBuffer) {
    require(dest.remaining() >= AES_ECB_BLOCK_BYTES) {
        "dest needs $AES_ECB_BLOCK_BYTES bytes remaining, has ${dest.remaining()}"
    }
}
