package com.ditchoom.buffer.okio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PoolReleasable
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Test group D: lifetime negatives. A bridge view must not silently read/write reclaimed memory
 * once its underlying buffer is freed; it must fail fast instead.
 */
class LifetimeNegativeTest {
    @Test
    fun asOkioSource_afterPooledBufferFreed_failsFast() {
        for ((name, factory) in bridgeFactories) {
            val p = BufferPool(defaultBufferSize = 256, factory = factory)
            val pooled = p.acquire(64)
            pooled.writeBytes(patternBytes(64))
            pooled.resetForRead()

            val source = pooled.asOkioSource()
            (pooled as PlatformBuffer).freeNativeMemory() // release to pool while the bridge view is still held

            assertFailsWith<IllegalStateException>("$name freed pooled asOkioSource must fail fast") {
                source.read(Buffer(), 64L)
            }
            p.clear()
        }
    }

    @Test
    fun asOkioSink_afterPooledBufferFreed_failsFast() {
        for ((name, factory) in bridgeFactories) {
            val p = BufferPool(defaultBufferSize = 256, factory = factory)
            val pooled = p.acquire(64)
            val sink = pooled.asOkioSink()
            (pooled as PlatformBuffer).freeNativeMemory()

            val src = Buffer().apply { write(patternBytes(64)) }
            assertFailsWith<IllegalStateException>("$name freed pooled asOkioSink must fail fast") {
                sink.write(src, src.size)
            }
            p.clear()
        }
    }

    @Test
    fun asOkioSource_afterClose_throws() {
        val source = readableBufferOf(patternBytes(32)).asOkioSource()
        source.close()
        assertFailsWith<IllegalStateException> { source.read(Buffer(), 16L) }
    }

    @Test
    fun asOkioSink_afterClose_throws() {
        val sink = BufferFactory.managed().allocate(32).asOkioSink()
        sink.close()
        val src = Buffer().apply { write(patternBytes(16)) }
        assertFailsWith<IllegalStateException> { sink.write(src, src.size) }
    }

    @Test
    fun sourceClose_doesNotFreeUnderlyingBuffer() {
        // The bridge does not own the buffer: closing the view leaves the buffer usable.
        val buffer = readableBufferOf(patternBytes(32), BufferFactory.managed())
        buffer.asOkioSource().close()
        buffer.position(0)
        assertTrue(buffer.remaining() == 32, "underlying buffer must remain usable after view close")
    }

    // ========================================================================
    // Group E: pooled *slice* bridge lifetime. A bridge view taken on a
    // TrackedSlice (rather than directly on the pooled chunk) must also fail
    // fast once BOTH the slice and its parent chunk are released back to the
    // pool — releasing only one of the two must not be enough to reclaim the
    // storage (refcounted: chunk=1 ref, each slice=+1 ref). Mirrors
    // TrackedSliceLifetimeTest in :buffer's commonTest.
    // ========================================================================

    @Test
    fun asOkioSource_onSlice_afterSliceAndChunkReleased_failsFast() {
        for ((name, factory) in bridgeFactories) {
            val p = BufferPool(defaultBufferSize = 256, factory = factory)
            val chunk = p.acquire(64) as PlatformBuffer
            chunk.writeBytes(patternBytes(64))
            chunk.resetForRead()

            val slice = chunk.slice()
            val source = slice.asOkioSource()

            (slice as PoolReleasable).releaseToPool()
            chunk.freeNativeMemory()

            assertFailsWith<IllegalStateException>("$name released slice asOkioSource must fail fast") {
                source.read(Buffer(), 64L)
            }
            p.clear()
        }
    }

    @Test
    fun asOkioSink_onSlice_afterSliceAndChunkReleased_failsFast() {
        for ((name, factory) in bridgeFactories) {
            val p = BufferPool(defaultBufferSize = 256, factory = factory)
            val chunk = p.acquire(64) as PlatformBuffer
            val slice = chunk.slice()
            val sink = slice.asOkioSink()

            (slice as PoolReleasable).releaseToPool()
            chunk.freeNativeMemory()

            val src = Buffer().apply { write(patternBytes(64)) }
            assertFailsWith<IllegalStateException>("$name released slice asOkioSink must fail fast") {
                sink.write(src, src.size)
            }
            p.clear()
        }
    }

    @Test
    fun asOkioSource_onSlice_releasingOnlySliceIsNotEnough() {
        for ((name, factory) in bridgeFactories) {
            val p = BufferPool(defaultBufferSize = 256, factory = factory)
            val chunk = p.acquire(64) as PlatformBuffer
            chunk.writeBytes(patternBytes(64))
            chunk.resetForRead()

            val slice = chunk.slice()
            val source = slice.asOkioSource()

            // Release only the slice; the parent chunk still pins the storage (refCount 2 -> 1).
            (slice as PoolReleasable).releaseToPool()

            // The slice itself is released, so its own bridge view must already fail fast —
            // independent of whether the chunk still pins the underlying storage.
            assertFailsWith<IllegalStateException>(
                "$name released slice asOkioSource must fail fast even while chunk is outstanding",
            ) {
                source.read(Buffer(), 64L)
            }
            chunk.freeNativeMemory()
            p.clear()
        }
    }

    // ========================================================================
    // Full silent-corruption repro through the bridge: once a slice AND its
    // parent chunk are released, the underlying raw buffer returns to the
    // pool's freelist and may be handed to the next acquirer. A stale bridge
    // view held past that point must fail fast — it must NOT silently read or
    // write the next acquirer's bytes.
    // ========================================================================

    @Test
    fun asOkioSource_onSlice_doesNotObserveNextAcquirersWrites() {
        for ((name, factory) in bridgeFactories) {
            val p = BufferPool(defaultBufferSize = 256, factory = factory)
            val chunk = p.acquire(64) as PlatformBuffer
            chunk.writeBytes(patternBytes(64)) // pattern A
            chunk.resetForRead()

            val staleSlice = chunk.slice()
            val staleSource = staleSlice.asOkioSource()

            // Drop all references so the raw buffer returns to the pool's freelist.
            chunk.freeNativeMemory()
            (staleSlice as PoolReleasable).releaseToPool()

            // Next acquire pops the SAME raw buffer and writes a different pattern.
            val reused = p.acquire(64) as PlatformBuffer
            reused.writeBytes(ByteArray(64) { 0xEE.toByte() }) // pattern B
            reused.resetForRead()

            // The stale bridge view must fail fast — and must not have copied any of
            // pattern B into the sink before throwing.
            val sink = Buffer()
            assertFailsWith<IllegalStateException>(
                "$name stale sliced source must not read the reused buffer's data",
            ) {
                staleSource.read(sink, 64L)
            }
            assertTrue(sink.size == 0L, "$name no bytes may be exposed before the fail-fast throw")

            reused.freeNativeMemory()
            p.clear()
        }
    }

    @Test
    fun asOkioSink_onSlice_doesNotCorruptNextAcquirersBuffer() {
        for ((name, factory) in bridgeFactories) {
            val p = BufferPool(defaultBufferSize = 256, factory = factory)
            val chunk = p.acquire(64) as PlatformBuffer
            val staleSlice = chunk.slice()
            val staleSink = staleSlice.asOkioSink()

            chunk.freeNativeMemory()
            (staleSlice as PoolReleasable).releaseToPool()

            // Next acquire pops the SAME raw buffer and fills it with a known pattern.
            val reused = p.acquire(64) as PlatformBuffer
            reused.writeBytes(ByteArray(64) { 0xCC.toByte() })
            reused.resetForRead()

            val src = Buffer().apply { write(patternBytes(64)) }
            assertFailsWith<IllegalStateException>(
                "$name stale sliced sink must not write into the reused buffer",
            ) {
                staleSink.write(src, src.size)
            }

            // The reused buffer's contents must be untouched by the stale sink's write attempt.
            val observed = ByteArray(64)
            reused.readInto(observed, 0, 64)
            assertTrue(
                observed.all { it == 0xCC.toByte() },
                "$name reused buffer must not observe bytes from the stale sink's write",
            )

            reused.freeNativeMemory()
            p.clear()
        }
    }
}
