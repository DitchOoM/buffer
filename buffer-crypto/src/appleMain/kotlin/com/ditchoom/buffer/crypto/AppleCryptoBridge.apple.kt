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
 * Invokes [block] with a pointer to [count] writable bytes at this buffer's current position,
 * then advances the position by [count]. Native buffers expose their memory pointer; heap
 * buffers pin their own backing array. No array is allocated — the system call writes straight
 * into the destination buffer. [count] must fit within `remaining()`.
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
