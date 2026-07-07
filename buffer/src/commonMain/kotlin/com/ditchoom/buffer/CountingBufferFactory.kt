package com.ditchoom.buffer

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Returns a factory that counts every allocation and wrap that flows through it.
 *
 * The returned [CountingBufferFactory] is a transparent decorator: buffers are produced by the
 * delegate unchanged; only the counters are updated. Use it to make allocation behavior
 * *assertable* — e.g. a zero-copy regression test pins "classifying a stream must not copy the
 * payload" by asserting [CountingBufferFactory.allocationCount] and
 * [CountingBufferFactory.largestAllocationSize] instead of eyeballing the implementation:
 *
 * ```kotlin
 * val counting = BufferFactory.Default.counting()
 * val source = BufferedByteSource(rawSource, counting)
 * classify(source)
 * assertEquals(0, counting.allocationCount) // classification allocated nothing
 * ```
 */
fun BufferFactory.counting(): CountingBufferFactory = CountingBufferFactory(this)

/**
 * A [BufferFactory] decorator that counts allocations and wraps flowing through it.
 *
 * Counters are updated atomically and can be read at any time; [reset] zeroes them. Buffers are
 * not wrapped or altered — [decorate] forwards to the delegate so inner wrapping decorators
 * (e.g. a secure-erase factory) still apply.
 *
 * Create via [BufferFactory.counting].
 */
@OptIn(ExperimentalAtomicApi::class)
class CountingBufferFactory internal constructor(
    private val delegate: BufferFactory,
) : BufferFactory {
    private val allocations = AtomicLong(0L)
    private val bytes = AtomicLong(0L)
    private val largest = AtomicInt(0)
    private val wraps = AtomicLong(0L)

    /** Number of [allocate] calls since creation or the last [reset]. */
    val allocationCount: Long get() = allocations.load()

    /** Total bytes requested across all counted [allocate] calls. */
    val allocatedBytes: Long get() = bytes.load()

    /** Size of the single largest counted [allocate] call, or 0 if none. */
    val largestAllocationSize: Int get() = largest.load()

    /** Number of [wrap] calls since creation or the last [reset]. */
    val wrapCount: Long get() = wraps.load()

    /** Zeroes all counters. */
    fun reset() {
        allocations.store(0L)
        bytes.store(0L)
        largest.store(0)
        wraps.store(0L)
    }

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        allocations.fetchAndAdd(1L)
        bytes.fetchAndAdd(size.toLong())
        var current = largest.load()
        while (size > current && !largest.compareAndSet(current, size)) {
            current = largest.load()
        }
        return delegate.allocate(size, byteOrder)
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        wraps.fetchAndAdd(1L)
        return delegate.wrap(array, byteOrder)
    }

    // Transparent decorator: forward so an inner wrapping decorator (e.g. secure()) can wrap.
    override fun decorate(buffer: PlatformBuffer): PlatformBuffer = delegate.decorate(buffer)
}
