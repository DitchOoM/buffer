package com.ditchoom.buffer.okio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
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
}
