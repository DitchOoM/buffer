package com.ditchoom.buffer.okio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PoolReleasable
import okio.Buffer
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Test group C: every bridge entry point must work transparently when handed a PooledBuffer or a
 * TrackedSlice (the two wrapper types), not just concrete PlatformBuffers.
 */
class WrapperTransparencyBridgeTest {
    private fun pool(factory: BufferFactory = BufferFactory.Default): BufferPool {
        val poolBufferSize = 256
        return BufferPool(defaultBufferSize = poolBufferSize, factory = factory)
    }

    @Test
    fun asOkioSource_throughPooledBuffer() {
        for ((name, factory) in bridgeFactories) {
            val p = pool(factory)
            val expected = patternBytes(200)
            val pooled = p.acquire(200)
            pooled.writeBytes(expected)
            pooled.resetForRead()

            val actual = pooled.asOkioSource().buffer().readByteArray()
            assertContentEquals(expected, actual, "$name pooled asOkioSource")

            p.release(pooled)
            p.clear()
        }
    }

    @Test
    fun asOkioSource_throughTrackedSlice() {
        for ((name, factory) in bridgeFactories) {
            val p = pool(factory)
            val expected = patternBytes(200)
            val pooled = p.acquire(200)
            pooled.writeBytes(expected)
            pooled.resetForRead()

            val slice = pooled.slice()
            val actual = slice.asOkioSource().buffer().readByteArray()
            assertContentEquals(expected, actual, "$name trackedslice asOkioSource")

            (slice as? PoolReleasable)?.releaseToPool()
            p.release(pooled)
            p.clear()
        }
    }

    @Test
    fun asOkioSink_throughPooledBuffer() {
        for ((name, factory) in bridgeFactories) {
            val p = pool(factory)
            val expected = patternBytes(200)
            val src = Buffer().apply { write(expected) }
            val pooled = p.acquire(200)

            pooled.asOkioSink().write(src, src.size)
            pooled.resetForRead()
            val actual = ByteArray(200) { pooled.readByte() }
            assertContentEquals(expected, actual, "$name pooled asOkioSink")

            p.release(pooled)
            p.clear()
        }
    }

    @Test
    fun asOkioSink_throughTrackedSlice() {
        val p = pool(BufferFactory.managed())
        val expected = patternBytes(120)
        val src = Buffer().apply { write(expected) }
        val pooled = p.acquire(200)
        val slice = pooled.slice() // writable view over the pooled buffer

        slice.asOkioSink().write(src, src.size)
        slice.resetForRead()
        val actual = ByteArray(120) { slice.readByte() }
        assertContentEquals(expected, actual, "trackedslice asOkioSink")

        (slice as? PoolReleasable)?.releaseToPool()
        p.release(pooled)
        p.clear()
    }

    @Test
    fun copyToOkioBuffer_throughWrappers() {
        val p = pool(BufferFactory.managed())
        val expected = patternBytes(200)
        val pooled = p.acquire(200)
        pooled.writeBytes(expected)
        pooled.resetForRead()

        assertContentEquals(expected, pooled.copyToOkioBuffer().readByteArray(), "pooled copyToOkioBuffer")

        val slice = pooled.slice()
        assertContentEquals(expected, slice.copyToOkioBuffer().readByteArray(), "slice copyToOkioBuffer")

        (slice as? PoolReleasable)?.releaseToPool()
        p.release(pooled)
        p.clear()
    }

    @Test
    fun readInto_pooledDestination() {
        val p = pool(BufferFactory.managed())
        val expected = patternBytes(200)
        val src = readableBufferOf(expected).asOkioSource()
        val pooled = p.acquire(200)

        val moved = src.readInto(pooled)
        pooled.resetForRead()
        assertContentEquals(expected, ByteArray(moved.toInt()) { pooled.readByte() }, "readInto pooled dst")

        p.release(pooled)
        p.clear()
    }
}
