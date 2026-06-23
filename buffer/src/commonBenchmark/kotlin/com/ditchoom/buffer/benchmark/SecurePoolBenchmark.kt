package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.withPooling
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup

/**
 * Measures the per-operation cost of holding small, fixed-size key material in a secure buffer,
 * to decide whether secure-pooling actually pays off versus a plain non-pooled secure allocation.
 *
 * The cost drivers are pure buffer-mechanism — wrapper allocation, double delegation, and the
 * zero-init/wipe memset — so this uses a [WipeFactory]/[WipeBuffer] stand-in that mirrors the real
 * `buffer-crypto` `SecureBufferFactory`/`SecureBuffer` exactly (zero-init on allocate/decorate,
 * full-capacity `fill(0)` on free). The crypto module has no benchmark harness and can't be
 * depended on from here (it depends on `:buffer`), so a faithful stand-in is the only way to get
 * these numbers on the existing multi-platform harness.
 *
 * Compared per op (allocate → write a 32-byte "key" → free):
 *  - [secureDeterministic]: non-pooled `deterministic().secure()` — one wrapper + native
 *    alloc/free each op + O(32) wipe.
 *  - [secureFixedPool]: `secureFixedPool(32)` — two wrappers (pooled + secure), NO native
 *    alloc/free (perfect reuse), O(32) wipe.
 *  - [secureSharedPool]: secure over a large shared pool (the `withSecureBuffer` cost) — two
 *    wrappers, no alloc/free, but O(64 KiB) wipe because the wipe is O(capacity).
 *  - [plainDeterministic]: baseline with no secure layer, to isolate the wrapper+wipe overhead.
 *
 * A secure `ScopedBuffer` (zero per-op wrappers) would be the ceiling for the hottest paths, but
 * the scoped-buffer API is not on this branch, so it is out of scope here.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class SecurePoolBenchmark {
    private val keySize = 32
    private val sharedSize = 64 * 1024

    private lateinit var secureDeterministic: BufferFactory
    private lateinit var secureFixedPool: BufferFactory
    private lateinit var secureSharedPool: BufferFactory
    private lateinit var plainDeterministic: BufferFactory

    @Setup
    fun setup() {
        secureDeterministic = WipeFactory(BufferFactory.deterministic())
        secureFixedPool =
            WipeFactory(BufferFactory.deterministic())
                .withPooling(BufferPool(defaultBufferSize = keySize, factory = BufferFactory.deterministic()))
        secureSharedPool =
            WipeFactory(BufferFactory.Default)
                .withPooling(BufferPool(defaultBufferSize = sharedSize, factory = BufferFactory.Default))
        plainDeterministic = BufferFactory.deterministic()
    }

    /** Writes a 32-byte "key", reads one long back (defeats DCE), frees. */
    private inline fun cycle(factory: BufferFactory): Long {
        val buf = factory.allocate(keySize)
        buf.writeLong(0x0102030405060708L)
        buf.writeLong(0x1112131415161718L)
        buf.writeLong(0x2122232425262728L)
        buf.writeLong(0x3132333435363738L)
        buf.position(0)
        val first = buf.readLong()
        buf.freeNativeMemory()
        return first
    }

    @Benchmark
    fun secureDeterministicCycle(): Long = cycle(secureDeterministic)

    @Benchmark
    fun secureFixedPoolCycle(): Long = cycle(secureFixedPool)

    @Benchmark
    fun secureSharedPoolCycle(): Long = cycle(secureSharedPool)

    @Benchmark
    fun plainDeterministicCycle(): Long = cycle(plainDeterministic)
}

/**
 * Stand-in for `buffer-crypto`'s `SecureBuffer`: zeroes its full backing on free. Mirrors the real
 * class's cost (one wrapper allocation + double delegation + an O(capacity) `fill(0)` memset).
 */
private class WipeBuffer(
    private val inner: PlatformBuffer,
) : PlatformBuffer by inner {
    private var wiped = false

    override fun freeNativeMemory() {
        if (!wiped) {
            wiped = true
            inner.position(0)
            inner.setLimit(inner.capacity)
            inner.fill(0.toByte())
        }
        inner.freeNativeMemory()
    }

    override fun slice(byteOrder: ByteOrder): PlatformBuffer = inner.slice(byteOrder)
}

/** Stand-in for `SecureBufferFactory` — zero-inits on allocate/decorate, wraps in [WipeBuffer]. */
private class WipeFactory(
    private val delegate: BufferFactory,
) : BufferFactory {
    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer = WipeBuffer(zeroInit(delegate.allocate(size, byteOrder)))

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = WipeBuffer(delegate.wrap(array, byteOrder))

    override fun decorate(buffer: PlatformBuffer): PlatformBuffer = WipeBuffer(zeroInit(buffer))

    private fun zeroInit(buffer: PlatformBuffer): PlatformBuffer {
        val savedPosition = buffer.position()
        val savedLimit = buffer.limit()
        buffer.position(0)
        buffer.setLimit(buffer.capacity)
        buffer.fill(0.toByte())
        buffer.position(savedPosition)
        buffer.setLimit(savedLimit)
        return buffer
    }
}
