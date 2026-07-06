package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PoolReleasable
import com.ditchoom.buffer.pool.TrackedSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Use-after-release safety invariants for [TrackedSlice] — the slice wrapper
 * returned by [com.ditchoom.buffer.pool.PooledBuffer.slice].
 *
 * A [TrackedSlice] shares the underlying pooled chunk's backing storage. Once the
 * slice is released ([PoolReleasable.releaseToPool] / [freeNativeMemory]) AND the
 * parent chunk's refcount reaches zero, the raw buffer is returned to the pool's
 * freelist and may be handed to the next acquirer. A retained reference to a
 * released slice — or anything resolved through it (managed/native memory access,
 * `unwrapFully()`) — would then silently read/write memory owned by that next
 * acquirer. These tests pin fail-fast behavior: every data-path operation on a
 * released slice must throw [IllegalStateException] rather than corrupt memory.
 *
 * Runs on every platform via commonTest; the corruption manifests differently per
 * backend (DirectByteBuffer on JVM, malloc on Linux, NSData on Apple, Int8Array on
 * JS) but the guard lives in commonMain.
 */
class TrackedSliceLifetimeTest {
    private fun newPool(size: Int = 256): BufferPool =
        BufferPool(maxPoolSize = 4, defaultBufferSize = size, factory = BufferFactory.managed())

    // ========================================================================
    // (a) Released slice reads must throw
    // ========================================================================

    @Test
    fun releasedSliceRelativeReadThrows() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        chunk.writeInt(0x11223344)
        chunk.resetForRead()

        val slice = chunk.slice()
        (slice as PoolReleasable).releaseToPool()

        assertFailsWith<IllegalStateException>("relative read on released slice must throw") {
            slice.readInt()
        }
        chunk.freeNativeMemory()
        pool.clear()
    }

    @Test
    fun releasedSliceAbsoluteReadThrows() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        chunk.writeInt(0x11223344)
        chunk.resetForRead()

        val slice = chunk.slice()
        (slice as PoolReleasable).releaseToPool()

        assertFailsWith<IllegalStateException>("absolute get on released slice must throw") {
            slice.getInt(0)
        }
        chunk.freeNativeMemory()
        pool.clear()
    }

    @Test
    fun releasedSliceReadByteArrayThrows() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        chunk.writeBytes(byteArrayOf(1, 2, 3, 4))
        chunk.resetForRead()

        val slice = chunk.slice()
        (slice as PoolReleasable).releaseToPool()

        assertFailsWith<IllegalStateException>("readByteArray on released slice must throw") {
            slice.readByteArray(4)
        }
        assertFailsWith<IllegalStateException>("copyToByteArray on released slice must throw") {
            slice.copyToByteArray(4)
        }
        chunk.freeNativeMemory()
        pool.clear()
    }

    @Test
    fun releasedSliceSliceAndReadBytesThrow() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        chunk.writeBytes(byteArrayOf(1, 2, 3, 4))
        chunk.resetForRead()

        val slice = chunk.slice()
        (slice as PoolReleasable).releaseToPool()

        assertFailsWith<IllegalStateException>("slice() on released slice must throw") {
            slice.slice()
        }
        assertFailsWith<IllegalStateException>("readBytes() on released slice must throw") {
            slice.readBytes(2)
        }
        chunk.freeNativeMemory()
        pool.clear()
    }

    // ========================================================================
    // (b) Released slice writes must throw
    // ========================================================================

    @Test
    fun releasedSliceRelativeWriteThrows() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        val slice = chunk.slice()
        (slice as PoolReleasable).releaseToPool()

        assertFailsWith<IllegalStateException>("relative write on released slice must throw") {
            slice.writeInt(0xDEADBEEF.toInt())
        }
        chunk.freeNativeMemory()
        pool.clear()
    }

    @Test
    fun releasedSliceAbsoluteWriteThrows() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        val slice = chunk.slice()
        (slice as PoolReleasable).releaseToPool()

        assertFailsWith<IllegalStateException>("absolute set on released slice must throw") {
            slice.set(0, 0x42.toByte())
        }
        chunk.freeNativeMemory()
        pool.clear()
    }

    @Test
    fun releasedSliceBulkWriteAndFillThrow() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        val slice = chunk.slice()
        (slice as PoolReleasable).releaseToPool()

        assertFailsWith<IllegalStateException>("writeBytes on released slice must throw") {
            slice.writeBytes(byteArrayOf(1, 2, 3))
        }
        assertFailsWith<IllegalStateException>("fill on released slice must throw") {
            slice.fill(0x00.toByte())
        }
        assertFailsWith<IllegalStateException>("write(ReadBuffer) on released slice must throw") {
            val src = BufferFactory.managed().allocate(4)
            src.writeInt(1)
            src.resetForRead()
            slice.write(src)
        }
        chunk.freeNativeMemory()
        pool.clear()
    }

    // ========================================================================
    // (c) Access-bridge resolution through a released slice must throw
    // ========================================================================

    @Test
    fun releasedSliceManagedMemoryAccessThrows() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        chunk.writeBytes(byteArrayOf(1, 2, 3))
        chunk.resetForRead()

        val slice = chunk.slice()
        (slice as PoolReleasable).releaseToPool()

        assertFailsWith<IllegalStateException>("managedMemoryAccess through released slice must throw") {
            slice.managedMemoryAccess
        }
        chunk.freeNativeMemory()
        pool.clear()
    }

    @Test
    fun releasedSliceNativeMemoryAccessThrows() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        chunk.writeInt(42)
        chunk.resetForRead()

        val slice = chunk.slice()
        (slice as PoolReleasable).releaseToPool()

        assertFailsWith<IllegalStateException>("nativeMemoryAccess through released slice must throw") {
            slice.nativeMemoryAccess
        }
        chunk.freeNativeMemory()
        pool.clear()
    }

    @Test
    fun releasedSliceUnwrapFullyThrows() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        chunk.writeInt(42)
        chunk.resetForRead()

        val slice = chunk.slice()
        (slice as PoolReleasable).releaseToPool()

        assertFailsWith<IllegalStateException>("unwrapFully on released slice must throw") {
            slice.unwrapFully()
        }
        chunk.freeNativeMemory()
        pool.clear()
    }

    @Test
    fun releasedSliceToNativeDataThrows() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        chunk.writeInt(42)
        chunk.resetForRead()

        val slice = chunk.slice()
        (slice as PoolReleasable).releaseToPool()

        assertFailsWith<IllegalStateException>("toNativeData on released slice must throw") {
            slice.toNativeData()
        }
        chunk.freeNativeMemory()
        pool.clear()
    }

    // ========================================================================
    // (d) Slice released but parent still holding other references
    // ========================================================================

    @Test
    fun releasingOneSliceDoesNotDisableParentOrSiblings() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        chunk.writeInt(0x0A0B0C0D)
        chunk.resetForRead()

        val s1 = chunk.slice()
        val s2 = chunk.slice()

        // Release only s1. The parent chunk and sibling slice s2 remain valid.
        (s1 as PoolReleasable).releaseToPool()

        assertFailsWith<IllegalStateException>("released slice s1 must throw") {
            s1.readInt()
        }

        // Parent still usable (slice() does not advance the parent's position).
        assertEquals(0x0A0B0C0D, chunk.readInt(), "parent chunk must still read correctly")

        // Sibling still usable.
        assertEquals(0x0A0B0C0D, s2.readInt(), "sibling slice must still read correctly")

        (s2 as PoolReleasable).releaseToPool()
        chunk.freeNativeMemory()
        pool.clear()
    }

    // ========================================================================
    // (e) Parent fully released while slice not yet released.
    //
    // Refcount design (PooledBuffer): starts at 1; each slice() addRef()s; each
    // release decrements. The chunk returns to the pool ONLY when refCount hits 0.
    // So freeing the parent chunk alone while an un-released slice is outstanding
    // must NOT reclaim the storage — the outstanding slice keeps it pinned and
    // remains fully readable/writable.
    // ========================================================================

    @Test
    fun parentFreedWhileSliceOutstandingKeepsSliceUsable() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        chunk.writeInt(0x12345678)
        chunk.resetForRead()

        val slice = chunk.slice()

        // Free the parent chunk. refCount 2 -> 1: storage stays pinned by the slice.
        chunk.freeNativeMemory()
        assertEquals(0, pool.stats().currentPoolSize, "outstanding slice must pin the chunk")

        // The un-released slice is still valid and reads the original data.
        assertEquals(0x12345678, slice.readInt(), "outstanding slice must remain readable after parent free")

        // Releasing the slice now drops refCount to 0 and recycles the chunk.
        (slice as PoolReleasable).releaseToPool()
        assertEquals(1, pool.stats().currentPoolSize, "chunk recycles once the last reference is released")
        pool.clear()
    }

    // ========================================================================
    // Full silent-corruption repro: released slice must not read the next
    // acquirer's data.
    // ========================================================================

    @Test
    fun releasedSliceDoesNotObserveNextAcquirersWrites() {
        val pool = newPool(size = 64)

        val chunk = pool.acquire(64) as PlatformBuffer
        chunk.writeInt(0xAAAAAAAA.toInt()) // pattern A
        chunk.resetForRead()

        val staleSlice = chunk.slice()

        // Drop all references so the raw buffer returns to the freelist.
        chunk.freeNativeMemory()
        (staleSlice as PoolReleasable).releaseToPool()
        assertEquals(1, pool.stats().currentPoolSize, "raw buffer must be back in the pool")

        // Next acquire pops the SAME raw buffer and writes a different pattern.
        val reused = pool.acquire(64) as PlatformBuffer
        assertEquals(0, pool.stats().currentPoolSize, "re-acquire must reuse the pooled buffer")
        reused.writeInt(0xBBBBBBBB.toInt()) // pattern B
        reused.resetForRead()

        // The stale slice must fail fast, NOT silently observe pattern B.
        assertFailsWith<IllegalStateException>("stale slice must not read the reused buffer's data") {
            staleSlice.readInt()
        }

        reused.freeNativeMemory()
        pool.clear()
    }

    // ========================================================================
    // Sanity: a live (un-released) slice still works and is not a false positive.
    // ========================================================================

    @Test
    fun liveSliceStillReadsAndWrites() {
        val pool = newPool()
        val chunk = pool.acquire(64) as PlatformBuffer
        chunk.writeInt(0x01020304)
        chunk.resetForRead()

        val slice = chunk.slice()
        assertFalse(slice.unwrapFully() is TrackedSlice, "unwrapFully strips the wrapper on a live slice")
        assertEquals(0x01020304, slice.readInt(), "live slice must read correctly")

        assertTrue(slice.managedMemoryAccess != null, "live managed slice exposes managedMemoryAccess")

        (slice as PoolReleasable).releaseToPool()
        chunk.freeNativeMemory()
        pool.clear()
    }
}
