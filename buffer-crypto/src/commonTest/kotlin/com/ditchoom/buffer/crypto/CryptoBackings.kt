package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool

/**
 * Shared backing-matrix harness for every crypto test suite.
 *
 * A crypto primitive must produce identical output regardless of how its input and
 * destination buffers are backed, because each backing exercises a different branch of the
 * platform bridges:
 *  - **heap** (managed `ByteArray`) → `managedMemoryAccess` array path / `SecretKeySpec(array, …)`
 *  - **direct** (native) → native-pointer / `ByteBuffer` path / `SecretKeySpec(copy)`
 *  - **pooled / slice** → wrapper delegation (the CLAUDE.md wrapper-transparency contract)
 *
 * A regression in one branch (e.g. a broken direct-dest fast path) would otherwise pass CI
 * silently, since a single KAT only hits one backing per platform incidentally. Every family
 * runs its ops across [inputs] × [dests] so all branches are covered uniformly.
 */
object CryptoBackings {
    enum class Backing { HEAP, DIRECT, POOLED, SLICE }

    /** Backings valid as a read source (slice is read-only but readable). */
    val inputs = listOf(Backing.HEAP, Backing.DIRECT, Backing.POOLED, Backing.SLICE)

    /** Backings valid as a writable destination (slice is read-only ⇒ excluded). */
    val dests = listOf(Backing.HEAP, Backing.DIRECT, Backing.POOLED)

    /**
     * Copies [source]'s remaining bytes into a read-ready buffer with the requested [kind]
     * backing. Non-destructive: [source]'s position is left untouched.
     */
    fun place(
        kind: Backing,
        source: ReadBuffer,
        pool: BufferPool,
    ): ReadBuffer {
        val n = source.remaining()
        val start = source.position()
        val backing: ReadWriteBuffer =
            when (kind) {
                Backing.HEAP, Backing.SLICE -> BufferFactory.managed().allocate(n)
                Backing.DIRECT -> BufferFactory.Default.allocate(n)
                Backing.POOLED -> pool.acquire(n)
            }
        for (i in 0 until n) backing.writeByte(source.get(start + i))
        backing.resetForRead()
        return if (kind == Backing.SLICE) backing.slice() else backing
    }

    /** An empty writable destination of [size] bytes with the requested [kind] backing. */
    fun dest(
        kind: Backing,
        size: Int,
        pool: BufferPool,
    ): ReadWriteBuffer =
        when (kind) {
            Backing.HEAP -> BufferFactory.managed().allocate(size)
            Backing.DIRECT -> BufferFactory.Default.allocate(size)
            Backing.POOLED -> pool.acquire(size)
            Backing.SLICE -> error("slice is read-only; not a valid destination")
        }
}
