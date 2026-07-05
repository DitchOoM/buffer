package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.io.Buffer
import kotlin.test.Test
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
}
