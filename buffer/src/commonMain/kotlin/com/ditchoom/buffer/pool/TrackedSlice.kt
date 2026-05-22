package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.bufferEquals
import com.ditchoom.buffer.bufferHashCode

/**
 * Slice of a pooled buffer that tracks parent lifetime via reference counting.
 * When released, decrements the parent's refCount. The parent buffer is returned
 * to the pool only when all slices and the original chunk reference are released.
 *
 * Implements [PlatformBuffer] (writable) by delegating to [inner], which is the
 * concrete slice produced by the underlying buffer's `slice(byteOrder)`. Because
 * `PlatformBuffer.slice()` is contractually a `PlatformBuffer`, every slice
 * returned through a PooledBuffer is writable; writes propagate to the parent
 * buffer's underlying memory.
 */
internal class TrackedSlice(
    internal val inner: PlatformBuffer,
    private val parent: PooledBuffer,
) : PlatformBuffer by inner,
    PoolReleasable {
    private var released = false

    override fun releaseToPool() {
        if (!released) {
            released = true
            parent.releaseRef()
        }
    }

    // PlatformBuffer-by-delegation would resolve freeNativeMemory() to inner.slice()'s
    // freeNativeMemory, which is detached from the parent's refcount and would never
    // return the underlying pooled buffer to the pool. Route through releaseToPool()
    // instead so consumer-facing `freeNativeMemory()` decrements the parent refcount.
    override fun freeNativeMemory() {
        releaseToPool()
    }

    override fun slice(byteOrder: ByteOrder): PlatformBuffer {
        parent.addRef()
        return TrackedSlice(inner.slice(byteOrder), parent)
    }

    override fun equals(other: Any?): Boolean = bufferEquals(this, other)

    override fun hashCode(): Int = bufferHashCode(this)
}
