@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned

/**
 * Shared Kotlin/Native buffer-pointer helpers for the Linux BoringSSL backend. These mirror the
 * Apple backend's [AppleCryptoBridge] helpers exactly — they are platform-agnostic K/Native
 * primitives (pin a heap array, or hand over a native pointer) and depend only on cinterop, not on
 * any Apple type. Kept in `linuxMain` so they do not collide with the Apple copies.
 */

/**
 * Invokes [block] with a pointer to this buffer's remaining bytes and their length, without
 * disturbing the buffer's position or allocating any array. Native buffers hand over their memory
 * pointer directly; heap buffers pin their own backing array. No-op when there are no remaining
 * bytes. The pointer is valid only for the duration of [block].
 */
internal inline fun ReadBuffer.withRemainingBytes(block: (CPointer<ByteVar>, length: Int) -> Unit) {
    val n = remaining()
    if (n == 0) return
    val pos = position()
    val managed = managedMemoryAccess
    if (managed != null) {
        managed.backingArray.usePinned { block(it.addressOf(managed.arrayOffset + pos), n) }
        return
    }
    val native = nativeMemoryAccess
    val ptr = native?.let { (it.nativeAddress + pos).toCPointer<ByteVar>() }
    requireNotNull(ptr) { "buffer must expose native or managed memory" }
    block(ptr, n)
}

/**
 * Like [withRemainingBytes] but with the length pinned to [count] (the caller already knows it).
 * Tolerates an empty buffer by pinning a 1-byte placeholder, so callers can pass a length-0
 * pointer to BoringSSL (which accepts a non-null pointer with length 0).
 */
internal inline fun ReadBuffer.withRemainingBytes2(
    count: Int,
    block: (CPointer<ByteVar>) -> Unit,
) {
    if (count == 0) {
        ByteArray(1).usePinned { block(it.addressOf(0)) }
        return
    }
    withRemainingBytes { ptr, _ -> block(ptr) }
}

/**
 * Invokes [block] with a pointer to [aad]'s remaining bytes and their length, or with a harmless
 * pointer and length 0 when [aad] is null/empty.
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
 * Invokes [block] with a pointer to [count] writable bytes at this buffer's current position, then
 * advances the position by [count]. Native buffers expose their memory pointer; heap buffers pin
 * their own backing array. [count] must fit within `remaining()`.
 */
internal inline fun WriteBuffer.withWritablePointer(
    count: Int,
    block: (CPointer<ByteVar>) -> Unit,
) {
    require(remaining() >= count) { "dest needs $count bytes remaining, has ${remaining()}" }
    val pos = position()
    val managed = managedMemoryAccess
    if (managed != null) {
        managed.backingArray.usePinned { block(it.addressOf(managed.arrayOffset + pos)) }
        position(pos + count)
        return
    }
    val native = nativeMemoryAccess
    val ptr = native?.let { (it.nativeAddress + pos).toCPointer<ByteVar>() }
    requireNotNull(ptr) { "dest must expose native or managed memory" }
    block(ptr)
    position(pos + count)
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
