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
 * Invokes [block] with a pointer to this buffer's remaining bytes and their length, without
 * disturbing the buffer's position or allocating any array. Native buffers (NSData/direct)
 * hand over their memory pointer directly; heap buffers pin their own backing array. No-op
 * when there are no remaining bytes. The pointer is valid only for the duration of [block].
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
 * Invokes [block] with a pointer to [count] bytes of this buffer starting at buffer index
 * [from], without creating a view and without disturbing position or limit.
 *
 * The offset form of [withRemainingBytes]. AEAD open paths need pointers to two sub-ranges of one
 * caller-owned buffer (ciphertext, then tag). Taking `slice()` views for them takes a *reference*
 * on a pooled/refcounted input which the callee must then hand back — issue #332, where two
 * unreleased views pinned the caller's pool chunk on every opened record. Offsetting the pointer
 * has no ownership question to answer.
 *
 * Tolerates [count] == 0 by pinning a 1-byte placeholder, so callers can hand CommonCrypto or
 * CryptoKit a valid non-null pointer with length 0. The pointer is valid only inside [block].
 *
 * The window is bounds-checked before any pointer is produced. The `slice()`-based predecessor got
 * this for free — `setLimit(from + count)` threw on an out-of-range window — and pointer arithmetic
 * has no such backstop: an unchecked `from` would hand CommonCrypto a wild pointer to read from.
 * Checked against `limit()` rather than capacity because that is the readable extent, and written
 * as `count <= limit() - from` so a hostile `from + count` cannot overflow past the check.
 */
internal inline fun ReadBuffer.withBytesAt(
    from: Int,
    count: Int,
    block: (CPointer<ByteVar>) -> Unit,
) {
    require(from >= 0 && count >= 0) { "window must be non-negative, was from=$from count=$count" }
    require(from <= limit() && count <= limit() - from) {
        "window [$from, ${from.toLong() + count}) exceeds limit ${limit()}"
    }
    if (count == 0) {
        ByteArray(1).usePinned { block(it.addressOf(0)) }
        return
    }
    val managed = managedMemoryAccess
    if (managed != null) {
        managed.backingArray.usePinned { block(it.addressOf(managed.arrayOffset + from)) }
        return
    }
    val native = nativeMemoryAccess
    val ptr = native?.let { (it.nativeAddress + from).toCPointer<ByteVar>() }
    requireNotNull(ptr) { "buffer must expose native or managed memory" }
    block(ptr)
}

/**
 * Invokes [block] with a pointer to [count] writable bytes at this buffer's current position,
 * then advances the position by [count]. Native buffers expose their memory pointer; heap
 * buffers pin their own backing array. No array is allocated — the system call writes straight
 * into the destination buffer. [count] must fit within `remaining()`.
 *
 * Tolerates [count] == 0 the same way [withBytesAt] does. Without it, a heap destination with
 * nothing remaining reaches `addressOf(backingArray.size)` and throws — reachable today from any
 * caller pairing `BufferFactory.managed()` with an empty plaintext, not a theoretical case.
 */
internal inline fun WriteBuffer.withWritablePointer(
    count: Int,
    block: (CPointer<ByteVar>) -> Unit,
) {
    require(count >= 0) { "count must be non-negative, was $count" }
    require(remaining() >= count) { "dest needs $count bytes remaining, has ${remaining()}" }
    if (count == 0) {
        ByteArray(1).usePinned { block(it.addressOf(0)) }
        return
    }
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
