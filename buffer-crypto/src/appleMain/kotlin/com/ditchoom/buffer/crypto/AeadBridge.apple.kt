@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/**
 * Apple AEAD glue shared by [Aead.apple.kt][aesGcmSeal] and the ChaCha20-Poly1305 bridge.
 *
 * These helpers expose buffer memory to the CommonCrypto one-shot calls without allocating
 * arrays, mirroring [withRemainingBytes] / [withWritablePointer] but adding the small extras
 * the AEAD paths need (optional AAD pointer, absolute sub-views, a native-tag comparison view).
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

/** A non-destructive read-ready view of [length] bytes of [source] starting at absolute [from]. */
internal fun absoluteView(
    source: ReadBuffer,
    from: Int,
    length: Int,
): ReadBuffer {
    val savedPos = source.position()
    val savedLimit = source.limit()
    source.position(from)
    source.setLimit(from + length)
    val view = source.slice()
    source.position(savedPos)
    source.setLimit(savedLimit)
    return view
}

/** Wraps a native [AEAD_TAG_BYTES]-byte tag pointer as a read-ready buffer for constant-time compare. */
internal fun nativeTagBuffer(tag: CPointer<ByteVar>): ReadBuffer {
    val out = BufferFactory.Default.allocate(AEAD_TAG_BYTES)
    for (i in 0 until AEAD_TAG_BYTES) out.writeByte(tag[i])
    out.resetForRead()
    return out
}

/**
 * ChaCha20-Poly1305 on Apple via CryptoKit (`ChaChaPoly.seal` / `.open`).
 *
 * CommonCrypto exposes no Kotlin/Native-bindable ChaCha20-Poly1305 one-shot, so the algorithm
 * routes through a CryptoKit Swift shim wired up in the **macOS CI** toolchain (a Swift source
 * file + cinterop bridging header registered only when building on a Mac). On a host without
 * that shim (e.g. this Linux-hosted build) the symbol is absent: [appleChaChaPolyAvailable] is
 * `false`, so the AEAD entry points throw [UnsupportedOperationException] — never a polyfill —
 * exactly as the web target does for ChaCha.
 *
 * Mac CI replaces [appleChaChaPolyAvailable] with `true` and supplies the [appleChaChaPolySeal]
 * / [appleChaChaPolyOpen] bodies that call into the CryptoKit shim, at which point the shared
 * KAT/Wycheproof/tamper suite gates the algorithm on Apple. This default keeps the Linux build
 * honest: it compiles, reports the capability as unsupported, and never fabricates a tag.
 */
internal val appleChaChaPolyAvailable: Boolean = false

internal fun appleChaChaPolySeal(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
): Unit = throw UnsupportedOperationException("ChaCha20-Poly1305 requires the CryptoKit shim (macOS CI)")

internal fun appleChaChaPolyOpen(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
): Unit = throw UnsupportedOperationException("ChaCha20-Poly1305 requires the CryptoKit shim (macOS CI)")
