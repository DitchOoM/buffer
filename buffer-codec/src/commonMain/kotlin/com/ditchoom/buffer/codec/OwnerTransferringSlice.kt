package com.ditchoom.buffer.codec

import com.ditchoom.buffer.PlatformBuffer

/**
 * Wraps a slice whose underlying chunk has an explicit lifecycle
 * ([com.ditchoom.buffer.CloseableBuffer]) but whose `freeNativeMemory()` is
 * a no-op by design (the slice shares the chunk's memory).
 *
 * Used by [FramedEncoder] when the encode chunk came from a deterministic
 * factory, WASM `LinearBuffer`, or `NativeBuffer` (Linux). In these cases
 * the consumer only sees the returned slice and would otherwise have no way
 * to release the chunk's native memory — the `GrowableWriteBuffer` wrapper
 * goes out of scope inside the encoder. Wrapping defers the chunk free until
 * the consumer signals "done" via `freeNativeMemory()` on the result.
 *
 * For pooled chunks, the slice already participates in refcounted release
 * via [com.ditchoom.buffer.pool.PoolReleasable.releaseToPool] — those skip
 * this wrapper.
 */
internal class OwnerTransferringSlice(
    private val inner: PlatformBuffer,
    private val chunk: PlatformBuffer,
) : PlatformBuffer by inner {
    private var released = false

    override fun freeNativeMemory() {
        if (released) return
        released = true
        inner.freeNativeMemory()
        chunk.freeNativeMemory()
    }
}
