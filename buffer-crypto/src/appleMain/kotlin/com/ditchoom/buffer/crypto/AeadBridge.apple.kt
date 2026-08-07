@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_chachapoly_open
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_chachapoly_seal
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.size_tVar

/*
 * Apple AEAD glue shared by Aead.apple.kt (aesGcmSeal) and the ChaCha20-Poly1305 bridge.
 *
 * These helpers expose buffer memory to the CommonCrypto calls without allocating arrays,
 * mirroring withRemainingBytes / withWritablePointer but adding the small extras the AEAD paths
 * need: an optional-AAD pointer, an offset-form byte pointer (withBytesAt) and an offset-form
 * constant-time tag compare. None of them takes a reference on the caller's buffer.
 */

/**
 * Like [withRemainingBytes] but with the length pinned to [count] (the caller already knows
 * it). Pins a heap backing array or hands over a native pointer; no array is allocated.
 */
internal inline fun ReadBuffer.withRemainingBytes2(
    count: Int,
    block: (CPointer<ByteVar>) -> Unit,
) {
    if (count == 0) {
        // CommonCrypto accepts a null data pointer with length 0; emulate via a 1-byte pin.
        ByteArray(1).usePinned { block(it.addressOf(0)) }
        return
    }
    withRemainingBytes { ptr, _ -> block(ptr) }
}

/**
 * Invokes [block] with a pointer to [aad]'s remaining bytes and their length, or with a
 * harmless pointer and length 0 when [aad] is null/empty (CommonCrypto treats length-0 AAD as
 * "no associated data").
 */
internal inline fun withOptionalBytes(
    aad: ReadBuffer?,
    block: (CPointer<ByteVar>, length: Int) -> Unit,
) {
    val n = aad?.remaining() ?: 0
    if (aad == null || n == 0) {
        ByteArray(1).usePinned { block(it.addressOf(0), 0) }
        return
    }
    aad.withRemainingBytes { ptr, len -> block(ptr, len) }
}

/**
 * Constant-time compare of [count] bytes at buffer index [from] in [source] against the native
 * [expected] pointer. Mirrors [constantTimeEquals]: it OR-accumulates the XOR of every byte and
 * never short-circuits, so runtime depends only on [count], never on how many leading bytes
 * match. Reads are absolute, so [source]'s position and limit are untouched and no view — and so
 * no pooled reference (#332) — is created.
 */
internal fun constantTimeEqualsAt(
    source: ReadBuffer,
    from: Int,
    expected: CPointer<ByteVar>,
    count: Int,
): Boolean {
    var diff = 0
    for (i in 0 until count) {
        diff = diff or (source.get(from + i).toInt() xor expected[i].toInt())
    }
    return diff == 0
}

/**
 * ChaCha20-Poly1305 on Apple via CryptoKit (`ChaChaPoly.seal` / `.open`).
 *
 * CommonCrypto exposes no Kotlin/Native-bindable ChaCha20-Poly1305 one-shot, so the algorithm
 * routes through the CryptoKit Swift shim ([bcks_chachapoly_seal] / [bcks_chachapoly_open], from
 * `CryptoKitShim.swift` via the `cryptokitshim` cinterop). CryptoKit is available on every Apple
 * target this module builds for (macOS 10.15+, iOS 13+, tvOS 13+, watchOS 6+), all of which exceed
 * the module's deployment floors, so [APPLE_CHACHA_POLY_AVAILABLE] is `true` unconditionally — no
 * runtime feature-detection is needed.
 */
internal const val APPLE_CHACHA_POLY_AVAILABLE: Boolean = true

internal fun appleChaChaPolySeal(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
) {
    val ptLen = plaintext.remaining()
    require(dest.remaining() >= ptLen + AEAD_TAG_BYTES) {
        "dest needs ${ptLen + AEAD_TAG_BYTES} bytes remaining, has ${dest.remaining()}"
    }
    memScoped {
        val tag = allocArray<ByteVar>(AEAD_TAG_BYTES)
        var status = -1
        key.requireInMemoryMaterial().withRemainingBytes { keyPtr, keyLen ->
            nonce.withRemainingBytes { ivPtr, ivLen ->
                withOptionalBytes(aad) { aadPtr, aadLen ->
                    plaintext.withRemainingBytes2(ptLen) { ptPtr ->
                        // Ciphertext (== plaintext length) goes straight into dest; tag to scratch.
                        dest.withWritablePointer(ptLen) { ctPtr ->
                            status =
                                bcks_chachapoly_seal(
                                    keyPtr.reinterpret(),
                                    keyLen.convert(),
                                    ivPtr.reinterpret(),
                                    ivLen.convert(),
                                    aadPtr.reinterpret(),
                                    aadLen.convert(),
                                    ptPtr.reinterpret(),
                                    ptLen.convert(),
                                    ctPtr.reinterpret(),
                                    ptLen.convert(),
                                    tag.reinterpret(),
                                    AEAD_TAG_BYTES.convert(),
                                )
                        }
                    }
                }
            }
        }
        check(status == BCKS_OK) { "ChaCha20-Poly1305 seal failed (status=$status)" }
        for (i in 0 until AEAD_TAG_BYTES) dest.writeByte(tag[i])
    }
}

internal fun appleChaChaPolyOpen(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
) {
    require(ciphertextAndTag.remaining() >= AEAD_TAG_BYTES) {
        "ciphertext+tag must be at least $AEAD_TAG_BYTES bytes"
    }
    val ctLen = ciphertextAndTag.remaining() - AEAD_TAG_BYTES
    require(dest.remaining() >= ctLen) { "dest needs $ctLen bytes remaining, has ${dest.remaining()}" }

    // Pointer offsets, not slices: a slice of a PooledBuffer takes a reference the callee would
    // have to hand back on every path including the VerificationFailed throw (#332).
    val ctStart = ciphertextAndTag.position()
    val tagStart = ctStart + ctLen

    memScoped {
        val destStart = dest.position()
        var status = -1
        var written = 0
        val writtenVar = alloc<size_tVar>()
        key.requireInMemoryMaterial().withRemainingBytes { keyPtr, keyLen ->
            nonce.withRemainingBytes { ivPtr, ivLen ->
                withOptionalBytes(aad) { aadPtr, aadLen ->
                    ciphertextAndTag.withBytesAt(ctStart, ctLen) { ctPtr ->
                        ciphertextAndTag.withBytesAt(tagStart, AEAD_TAG_BYTES) { tagPtr ->
                            // CryptoKit authenticates first; on tag mismatch it returns BCKS_ERR_AUTH
                            // and never writes plaintext into dest.
                            dest.withWritablePointer(ctLen) { ptPtr ->
                                status =
                                    bcks_chachapoly_open(
                                        keyPtr.reinterpret(),
                                        keyLen.convert(),
                                        ivPtr.reinterpret(),
                                        ivLen.convert(),
                                        aadPtr.reinterpret(),
                                        aadLen.convert(),
                                        ctPtr.reinterpret(),
                                        ctLen.convert(),
                                        tagPtr.reinterpret(),
                                        AEAD_TAG_BYTES.convert(),
                                        ptPtr.reinterpret(),
                                        ctLen.convert(),
                                        writtenVar.ptr,
                                    )
                            }
                        }
                    }
                }
            }
        }
        written = writtenVar.value.toInt()
        if (status != BCKS_OK || written != ctLen) {
            // Scrub any bytes that may have been written and reject. CryptoKit does not release
            // unverified plaintext, but we zero dest defensively to honor the no-leak contract.
            val end = dest.position()
            dest.position(destStart)
            while (dest.position() < end) dest.writeByte(0)
            dest.position(destStart)
            throw VerificationFailed()
        }
    }
}
