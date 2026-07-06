package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PoolReleasable
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Test group D: lifetime negatives. A bridge view must not silently read/write reclaimed memory
 * once its underlying buffer is freed; it must fail fast instead.
 */
class LifetimeNegativeTest {
    @Test
    fun asRawSource_afterPooledBufferFreed_failsFast() {
        for ((name, factory) in bridgeFactories) {
            val p = BufferPool(defaultBufferSize = 256, factory = factory)
            val pooled = p.acquire(64)
            pooled.writeBytes(patternBytes(64))
            pooled.resetForRead()

            val source = pooled.asRawSource()
            (pooled as PlatformBuffer).freeNativeMemory() // release to pool while the bridge view is still held

            assertFailsWith<IllegalStateException>("$name freed pooled asRawSource must fail fast") {
                source.readAtMostTo(Buffer(), 64L)
            }
            p.clear()
        }
    }

    @Test
    fun asRawSink_afterPooledBufferFreed_failsFast() {
        for ((name, factory) in bridgeFactories) {
            val p = BufferPool(defaultBufferSize = 256, factory = factory)
            val pooled = p.acquire(64)
            val sink = pooled.asRawSink()
            (pooled as PlatformBuffer).freeNativeMemory()

            val src = Buffer().apply { write(patternBytes(64)) }
            assertFailsWith<IllegalStateException>("$name freed pooled asRawSink must fail fast") {
                sink.write(src, src.size)
            }
            p.clear()
        }
    }

    @Test
    fun asRawSource_afterClose_throws() {
        val source = readableBufferOf(patternBytes(32)).asRawSource()
        source.close()
        assertFailsWith<IllegalStateException> { source.readAtMostTo(Buffer(), 16L) }
    }

    @Test
    fun asRawSink_afterClose_throws() {
        val sink = BufferFactory.managed().allocate(32).asRawSink()
        sink.close()
        val src = Buffer().apply { write(patternBytes(16)) }
        assertFailsWith<IllegalStateException> { sink.write(src, src.size) }
    }

    @Test
    fun sourceClose_doesNotFreeUnderlyingBuffer() {
        // The bridge does not own the buffer: closing the view leaves the buffer usable.
        val buffer = readableBufferOf(patternBytes(32), BufferFactory.managed())
        buffer.asRawSource().close()
        buffer.position(0)
        assertTrue(buffer.remaining() == 32, "underlying buffer must remain usable after view close")
    }

    // ========================================================================
    // Bridge views held over a TrackedSlice (pool.acquire().slice()), not just a
    // PooledBuffer chunk directly. A TrackedSlice aliases the parent chunk's backing
    // storage; once the slice (and the chunk reference pinning it) are released, the
    // raw buffer can be handed to the next acquirer. A bridge view opened over the
    // slice before release must fail fast on first use afterward instead of silently
    // reading/writing whatever now occupies that storage.
    // ========================================================================

    @Test
    fun asRawSource_afterSliceReleasedBackToPool_failsFast() {
        for ((name, factory) in bridgeFactories) {
            val p = BufferPool(defaultBufferSize = 256, factory = factory)
            val chunk = p.acquire(64) as PlatformBuffer
            chunk.writeBytes(patternBytes(64))
            chunk.resetForRead()

            val slice = chunk.slice()
            val source = slice.asRawSource()

            // Release the slice and the chunk reference pinning it, so the raw buffer
            // returns to the pool's freelist while the bridge view is still held.
            (slice as PoolReleasable).releaseToPool()
            chunk.freeNativeMemory()

            assertFailsWith<IllegalStateException>("$name released slice asRawSource must fail fast") {
                source.readAtMostTo(Buffer(), 64L)
            }
            p.clear()
        }
    }

    @Test
    fun asRawSink_afterSliceReleasedBackToPool_failsFast() {
        for ((name, factory) in bridgeFactories) {
            val p = BufferPool(defaultBufferSize = 256, factory = factory)
            val chunk = p.acquire(64) as PlatformBuffer
            val slice = chunk.slice()
            val sink = slice.asRawSink()

            (slice as PoolReleasable).releaseToPool()
            chunk.freeNativeMemory()

            val src = Buffer().apply { write(patternBytes(64)) }
            assertFailsWith<IllegalStateException>("$name released slice asRawSink must fail fast") {
                sink.write(src, src.size)
            }
            p.clear()
        }
    }

    @Test
    fun asRawSource_overReleasedSlice_doesNotLeakNextAcquirersData() {
        for ((name, factory) in bridgeFactories) {
            val p = BufferPool(defaultBufferSize = 64, factory = factory)

            val chunk = p.acquire(64) as PlatformBuffer
            chunk.writeBytes(patternBytes(64)) // pattern A
            chunk.resetForRead()

            val staleSlice = chunk.slice()
            val source = staleSlice.asRawSource()

            // Drop every reference so the raw buffer returns to the freelist.
            chunk.freeNativeMemory()
            (staleSlice as PoolReleasable).releaseToPool()
            assertEquals(1, p.stats().currentPoolSize, "$name: raw buffer must be back in the pool")

            // Next acquire pops the SAME raw buffer and writes a different, distinguishable pattern.
            val reused = p.acquire(64) as PlatformBuffer
            assertEquals(0, p.stats().currentPoolSize, "$name: re-acquire must reuse the pooled buffer")
            reused.writeBytes(ByteArray(64) { 0xFF.toByte() }) // pattern B
            reused.resetForRead()

            // The stale bridge view must fail fast before copying a single byte into the
            // sink — never silently hand back pattern B, the next acquirer's live data.
            val sink = Buffer()
            assertFailsWith<IllegalStateException>(
                "$name: bridge view over released slice must not observe reused buffer's data",
            ) {
                source.readAtMostTo(sink, 64L)
            }
            assertEquals(0L, sink.size, "$name: no bytes may cross the bridge before the fail-fast check fires")

            reused.freeNativeMemory()
            p.clear()
        }
    }
}
