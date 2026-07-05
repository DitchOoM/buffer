package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PoolReleasable
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Test group C: every bridge entry point must work transparently when handed a PooledBuffer or a
 * TrackedSlice (the two wrapper types), not just concrete PlatformBuffers.
 */
class WrapperTransparencyBridgeTest {
    private fun pool(factory: BufferFactory = BufferFactory.Default) = BufferPool(defaultBufferSize = 256, factory = factory)

    @Test
    fun asRawSource_throughPooledBuffer() {
        for ((name, factory) in bridgeFactories) {
            val p = pool(factory)
            val expected = patternBytes(200)
            val pooled = p.acquire(200)
            pooled.writeBytes(expected)
            pooled.resetForRead()

            val actual = pooled.asRawSource().buffered().readByteArray()
            assertContentEquals(expected, actual, "$name pooled asRawSource")

            p.release(pooled)
            p.clear()
        }
    }

    @Test
    fun asRawSource_throughTrackedSlice() {
        for ((name, factory) in bridgeFactories) {
            val p = pool(factory)
            val expected = patternBytes(200)
            val pooled = p.acquire(200)
            pooled.writeBytes(expected)
            pooled.resetForRead()

            val slice = pooled.slice()
            val actual = slice.asRawSource().buffered().readByteArray()
            assertContentEquals(expected, actual, "$name trackedslice asRawSource")

            (slice as? PoolReleasable)?.releaseToPool()
            p.release(pooled)
            p.clear()
        }
    }

    @Test
    fun asRawSink_throughPooledBuffer() {
        for ((name, factory) in bridgeFactories) {
            val p = pool(factory)
            val expected = patternBytes(200)
            val src = Buffer().apply { write(expected) }
            val pooled = p.acquire(200)

            pooled.asRawSink().write(src, src.size)
            pooled.resetForRead()
            val actual = ByteArray(200) { pooled.readByte() }
            assertContentEquals(expected, actual, "$name pooled asRawSink")

            p.release(pooled)
            p.clear()
        }
    }

    @Test
    fun asRawSink_throughTrackedSlice() {
        val p = pool(BufferFactory.managed())
        val expected = patternBytes(120)
        val src = Buffer().apply { write(expected) }
        val pooled = p.acquire(200)
        val slice = pooled.slice() // writable view over the pooled buffer

        slice.asRawSink().write(src, src.size)
        slice.resetForRead()
        val actual = ByteArray(120) { slice.readByte() }
        assertContentEquals(expected, actual, "trackedslice asRawSink")

        (slice as? PoolReleasable)?.releaseToPool()
        p.release(pooled)
        p.clear()
    }

    @Test
    fun copyToKotlinxIoBuffer_throughWrappers() {
        val p = pool(BufferFactory.managed())
        val expected = patternBytes(200)
        val pooled = p.acquire(200)
        pooled.writeBytes(expected)
        pooled.resetForRead()

        assertContentEquals(expected, pooled.copyToKotlinxIoBuffer().readByteArray(), "pooled copyToKotlinxIoBuffer")

        val slice = pooled.slice()
        assertContentEquals(expected, slice.copyToKotlinxIoBuffer().readByteArray(), "slice copyToKotlinxIoBuffer")

        (slice as? PoolReleasable)?.releaseToPool()
        p.release(pooled)
        p.clear()
    }

    @Test
    fun readInto_pooledDestination() {
        val p = pool(BufferFactory.managed())
        val expected = patternBytes(200)
        val src = readableBufferOf(expected).asRawSource()
        val pooled = p.acquire(200)

        val moved = src.readInto(pooled)
        pooled.resetForRead()
        assertContentEquals(expected, ByteArray(moved.toInt()) { pooled.readByte() }, "readInto pooled dst")

        p.release(pooled)
        p.clear()
    }
}
