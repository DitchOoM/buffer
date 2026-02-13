package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ReadBuffer

/**
 * Read-only slice of a pooled buffer that tracks parent lifetime via reference counting.
 * When released, decrements the parent's refCount. The parent buffer is returned to
 * the pool only when all slices and the original chunk reference are released.
 */
internal class TrackedSlice(
    internal val inner: ReadBuffer,
    private val parent: PooledBuffer,
) : ReadBuffer by inner,
    PoolReleasable {
    private var released = false

    override fun releaseToPool() {
        if (!released) {
            released = true
            parent.releaseRef()
        }
    }

    override fun slice(): ReadBuffer {
        parent.addRef()
        return TrackedSlice(inner.slice(), parent)
    }
}
