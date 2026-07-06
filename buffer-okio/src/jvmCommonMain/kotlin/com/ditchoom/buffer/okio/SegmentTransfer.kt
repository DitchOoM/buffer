package com.ditchoom.buffer.okio

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.unwrapFully

/**
 * JVM/Android segment transfer with a `ByteBuffer.hasArray()` branch.
 *
 * On Android, `ByteBuffer.allocateDirect()` is MemoryRef-backed, so a
 * `DirectJvmBuffer`'s underlying `ByteBuffer` reports `hasArray() == true` at
 * every size — but `DirectJvmBuffer` only implements `NativeMemoryAccess`, so
 * interface dispatch misses the backing array. `System.arraycopy` against
 * `array()`/`arrayOffset()` skips Android's JNI bulk-access hop
 * (`Memory.peekByteArray`/`pokeByteArray` inside `ByteBuffer.get/put`).
 *
 * On desktop JVM, direct buffers are never array-backed, so this branch is
 * Android-only in effect (heap buffers already take `System.arraycopy` inside
 * `BaseJvmBuffer`). `hasArray()` is `true` only for non-read-only array-backed
 * buffers, so `array()` cannot throw here; read-only views fall back.
 *
 * `unwrapFully()` (never the `as? PlatformBuffer` unwrap anti-pattern) keeps
 * wrapper transparency AND fail-fast: a freed PooledBuffer throws its
 * use-after-free `IllegalStateException` from `unwrap()`. Wrappers delegate
 * position to the buffer they wrap, so advancing the unwrapped buffer keeps
 * both views in sync (same invariant BaseJvmBuffer's own fast paths rely on).
 */
internal actual fun ReadBuffer.readIntoSegment(
    dst: ByteArray,
    dstOffset: Int,
    length: Int,
) {
    val base = unwrapFully() as? BaseJvmBuffer
    val bb = base?.byteBuffer
    if (bb != null && bb.hasArray() && length <= base.remaining()) {
        val pos = base.position()
        System.arraycopy(bb.array(), bb.arrayOffset() + pos, dst, dstOffset, length)
        base.position(pos + length)
    } else {
        // Fallback keeps the library's exception surface (underflow) and covers
        // non-BaseJvmBuffer implementations and non-array-backed ByteBuffers.
        readInto(dst, dstOffset, length)
    }
}

internal actual fun WriteBuffer.writeSegmentBytes(
    src: ByteArray,
    srcOffset: Int,
    length: Int,
) {
    // All shipped WriteBuffer wrappers (PooledBuffer, TrackedSlice) implement PlatformBuffer,
    // so the ReadBuffer cast holds for them; anything else takes the writeBytes fallback.
    val base = (this as? ReadBuffer)?.unwrapFully() as? BaseJvmBuffer
    val bb = base?.byteBuffer
    if (bb != null && bb.hasArray() && length <= base.remaining()) {
        val pos = base.position()
        System.arraycopy(src, srcOffset, bb.array(), bb.arrayOffset() + pos, length)
        base.position(pos + length)
    } else {
        // Fallback keeps the library's exception surface (overflow) and covers
        // read-only views (hasArray() == false) and non-array-backed ByteBuffers.
        writeBytes(src, srcOffset, length)
    }
}
