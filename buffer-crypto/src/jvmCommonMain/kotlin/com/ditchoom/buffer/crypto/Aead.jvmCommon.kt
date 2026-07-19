package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managedMemoryAccess
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * JVM/Android AEAD backed by JCA.
 *
 * AES-GCM uses `AES/GCM/NoPadding`; ChaCha20-Poly1305 uses the `ChaCha20-Poly1305` transform
 * (JDK 11+ / Android via Conscrypt). Both are authenticated one-shot ciphers: `Cipher.doFinal`
 * in DECRYPT mode verifies the tag and only then returns plaintext, throwing
 * `AEADBadTagException` on a bad tag — which we normalize to the opaque VerificationFailed.
 *
 * The tag-length checks below enforce the fixed 128-bit tag, and the common layer already
 * enforces the 96-bit nonce, so the cipher is never invoked with a weakened parameter set.
 */

/** AES-GCM tag length in bits, fixed at 128. */
private const val GCM_TAG_BITS: Int = AEAD_TAG_BYTES * 8

/** Probe once whether the JCA `ChaCha20-Poly1305` transform is present (absent on old JDKs). */
private val chaChaPolyAvailable: Boolean =
    try {
        Cipher.getInstance("ChaCha20-Poly1305")
        true
    } catch (_: Throwable) {
        false
    }

/** AES-GCM is synchronous via JCA. */
actual val CryptoCapabilities.aesGcm: Aead<AesGcmKey, SyncCapableAesGcmKey> get() = Aead.Blocking(AesGcmBlockingOps)

/** ChaCha20-Poly1305 is synchronous via JCA when the transform is present (JDK 11+ / Conscrypt). */
actual val CryptoCapabilities.chaChaPoly: OptionalAead<ChaChaPolyKey>
    get() =
        if (chaChaPolyAvailable) {
            OptionalAead.Blocking(ChaChaPolyBlockingOps)
        } else {
            OptionalAead.Unavailable
        }

internal actual fun aesGcmSeal(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
) {
    requireNonce(nonce)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        keySpec(key.requireInMemoryMaterial(), "AES"),
        GCMParameterSpec(GCM_TAG_BITS, nonceBytes(nonce)),
    )
    aad?.let { cipher.updateAADRemaining(it) }
    finalInto(cipher, plaintext, dest)
}

internal actual fun aesGcmOpen(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
) {
    requireNonce(nonce)
    requireTagged(ciphertextAndTag)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        keySpec(key.requireInMemoryMaterial(), "AES"),
        GCMParameterSpec(GCM_TAG_BITS, nonceBytes(nonce)),
    )
    aad?.let { cipher.updateAADRemaining(it) }
    openInto(cipher, ciphertextAndTag, dest)
}

internal actual fun chaChaPolySeal(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
) {
    if (!chaChaPolyAvailable) {
        throw UnsupportedOperationException("ChaCha20-Poly1305 is not supported on this platform")
    }
    requireNonce(nonce)
    val cipher = Cipher.getInstance("ChaCha20-Poly1305")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        keySpec(key.requireInMemoryMaterial(), "ChaCha20"),
        IvParameterSpec(nonceBytes(nonce)),
    )
    aad?.let { cipher.updateAADRemaining(it) }
    finalInto(cipher, plaintext, dest)
}

internal actual fun chaChaPolyOpen(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
) {
    if (!chaChaPolyAvailable) {
        throw UnsupportedOperationException("ChaCha20-Poly1305 is not supported on this platform")
    }
    requireNonce(nonce)
    requireTagged(ciphertextAndTag)
    val cipher = Cipher.getInstance("ChaCha20-Poly1305")
    cipher.init(
        Cipher.DECRYPT_MODE,
        keySpec(key.requireInMemoryMaterial(), "ChaCha20"),
        IvParameterSpec(nonceBytes(nonce)),
    )
    aad?.let { cipher.updateAADRemaining(it) }
    openInto(cipher, ciphertextAndTag, dest)
}

// Native async simply delegates to the synchronous JCA path — no event loop needed off-web.

internal actual suspend fun aesGcmSealWithNonceAsync(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer {
    val out = factory.allocate(plaintext.remaining() + AEAD_TAG_BYTES)
    aesGcmSeal(key, nonce, aad, plaintext, out)
    out.resetForRead()
    return out
}

internal actual suspend fun aesGcmOpenWithNonceAsync(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer {
    val out = factory.allocate(maxOf(0, ciphertextAndTag.remaining() - AEAD_TAG_BYTES))
    aesGcmOpen(key, nonce, aad, ciphertextAndTag, out)
    out.resetForRead()
    return out
}

// =============================================================================
// JCA glue
// =============================================================================

internal fun requireNonce(nonce: ReadBuffer) {
    require(nonce.remaining() == AEAD_NONCE_BYTES) {
        "nonce must be $AEAD_NONCE_BYTES bytes, was ${nonce.remaining()}"
    }
}

internal fun requireTagged(ciphertextAndTag: ReadBuffer) {
    require(ciphertextAndTag.remaining() >= AEAD_TAG_BYTES) {
        "ciphertext+tag must be at least $AEAD_TAG_BYTES bytes, was ${ciphertextAndTag.remaining()}"
    }
}

/**
 * Builds a [SecretKeySpec] from the key buffer's remaining bytes (non-destructive). The staging
 * array is zeroed as soon as the spec is constructed — [SecretKeySpec] copies the bytes internally,
 * so the wipe cannot affect the spec and the raw key does not linger on the heap. The wipe runs in
 * a `finally` so the staged key is erased even when the spec constructor rejects the material.
 */
internal fun keySpec(
    key: ReadBuffer,
    algorithm: String,
): SecretKeySpec {
    val staged = remainingBytes(key)
    try {
        return SecretKeySpec(staged, algorithm)
    } finally {
        staged.fill(0)
    }
}

internal fun nonceBytes(nonce: ReadBuffer): ByteArray = remainingBytes(nonce)

/** Materializes a buffer's remaining bytes into an array without disturbing its position. */
private fun remainingBytes(buffer: ReadBuffer): ByteArray {
    val n = buffer.remaining()
    val start = buffer.position()
    val managed = buffer.managedMemoryAccess
    if (managed != null) {
        return managed.backingArray.copyOfRange(managed.arrayOffset + start, managed.arrayOffset + start + n)
    }
    val out = ByteArray(n)
    for (i in 0 until n) out[i] = buffer.get(start + i)
    return out
}

/** Feeds a buffer's remaining bytes into the cipher as AAD, zero-copy where possible. */
internal fun Cipher.updateAADRemaining(buffer: ReadBuffer) {
    if (buffer.remaining() == 0) return
    val managed = buffer.managedMemoryAccess
    if (managed != null) {
        updateAAD(managed.backingArray, managed.arrayOffset + buffer.position(), buffer.remaining())
    } else {
        updateAAD(remainingBytes(buffer))
    }
}

/**
 * Runs `doFinal` over [input]'s remaining bytes (ENCRYPT mode), writing `ciphertext ‖ tag`
 * into [dest]. Direct-dest fast path writes straight into the destination's backing array.
 */
internal fun finalInto(
    cipher: Cipher,
    input: ReadBuffer,
    dest: WriteBuffer,
) {
    val inBytes = remainingBytes(input)
    val outLen = cipher.getOutputSize(inBytes.size)
    require(dest.remaining() >= outLen) { "dest needs $outLen bytes remaining, has ${dest.remaining()}" }
    val managed = dest.managedMemoryAccess
    if (managed != null) {
        val pos = dest.position()
        val written = cipher.doFinal(inBytes, 0, inBytes.size, managed.backingArray, managed.arrayOffset + pos)
        dest.position(pos + written)
    } else {
        dest.writeBytes(cipher.doFinal(inBytes))
    }
}

/**
 * Runs `doFinal` over [ciphertextAndTag] (DECRYPT mode). The tag is verified inside `doFinal`;
 * a bad tag throws `AEADBadTagException`, which we collapse to the opaque [VerificationFailed]
 * so the failure carries no oracle-friendly reason.
 */
internal fun openInto(
    cipher: Cipher,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
) {
    val inBytes = remainingBytes(ciphertextAndTag)
    try {
        val managed = dest.managedMemoryAccess
        if (managed != null) {
            val pos = dest.position()
            val written = cipher.doFinal(inBytes, 0, inBytes.size, managed.backingArray, managed.arrayOffset + pos)
            dest.position(pos + written)
        } else {
            dest.writeBytes(cipher.doFinal(inBytes))
        }
    } catch (_: AEADBadTagException) {
        throw VerificationFailed()
    } catch (_: javax.crypto.IllegalBlockSizeException) {
        // Some providers surface a too-short/garbled ciphertext as IllegalBlockSizeException
        // rather than a bad-tag exception; both mean "did not authenticate".
        throw VerificationFailed()
    }
}
