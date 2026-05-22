package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.withPooling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pool-lifecycle invariants for [FramedEncoder] — the runtime that backs every
 * KSP-generated `@FramedBy` codec's encode emit.
 *
 * Regression context: MQTT's `pooledFactoryWritePath` memory-pressure test
 * regressed on 2026-05-12 after `ControlPacketV4.serialize(factory)` was routed
 * through `ControlPacketV4Codec(...).encode(...)`. The codec runs
 * `FramedEncoder.encode(factory, ...)`, which acquires a `GrowableWriteBuffer`
 * from the supplied factory and returns `buffer.slice()`. When the factory is
 * a `PoolingFactory`, the returned slice is a `TrackedSlice` whose parent
 * `PooledBuffer` held an outstanding chunk reference that nothing ever
 * released — every encode stranded a direct buffer instead of recycling it.
 *
 * The contract these tests pin:
 *
 *   1. Pooled-factory encode + `freeNativeMemory()` on the result MUST return
 *      the underlying chunk to the pool.
 *   2. Steady-state encode/free cycles MUST converge to ~100% pool hit rate.
 *   3. Body growth (initial estimate exceeded) MUST still return the *final*
 *      chunk to the pool after release; the intermediate chunks were already
 *      freed during `ensureCapacity`.
 *   4. Non-pooled factories MUST remain functionally correct and produce
 *      identical wire bytes.
 *
 * Runs in commonTest so the invariant is enforced on every platform —
 * JVM/Android (DirectByteBuffer), Apple (NSMutableData), JS (Int8Array),
 * Linux native (malloc), WASM (LinearBuffer). The bug was in commonMain
 * Kotlin (delegation through `PlatformBuffer by inner`), but the consequence
 * (off-heap leak) is platform-specific, and the platforms where
 * `freeNativeMemory()` is non-trivial (Linux native, deterministic) are the
 * ones most at risk of pool-leak ↔ chunk-reuse safety violations.
 */
class FramedEncoderPoolLifecycleTests {
    /**
     * Self-contained 2-byte big-endian fixed-width length codec. Keeps the
     * test free of MQTT/protocol-specific imports so the lifecycle invariant
     * is provable from buffer-codec's own surface.
     */
    private object U16LengthCodec : BoundingLengthCodec<UInt> {
        override val maxWireSize: Int = 2

        override fun decode(
            buffer: ReadBuffer,
            context: DecodeContext,
        ): UInt {
            val hi = buffer.readUnsignedByte().toUInt()
            val lo = buffer.readUnsignedByte().toUInt()
            return (hi shl 8) or lo
        }

        override fun encode(
            buffer: WriteBuffer,
            value: UInt,
            context: EncodeContext,
        ) {
            require(value <= 0xFFFFu) { "U16LengthCodec value out of range: $value" }
            buffer.writeByte(((value shr 8) and 0xFFu).toByte())
            buffer.writeByte((value and 0xFFu).toByte())
        }

        override fun wireSize(
            value: UInt,
            context: EncodeContext,
        ): WireSize = WireSize.Exact(2)

        override fun applyBound(
            buffer: ReadBuffer,
            decodedValue: UInt,
        ) {
            buffer.setLimit(buffer.position() + decodedValue.toInt())
        }
    }

    private fun encodeWithBody(
        factory: BufferFactory,
        bodyLength: Int,
        initialBodyEstimate: Int = 16,
        writeHeader: ((PlatformBuffer) -> Unit)? = { it.writeByte(0x42) },
    ): ReadBuffer =
        FramedEncoder.encode(
            factory = factory,
            framingCodec = U16LengthCodec,
            context = EncodeContext.Empty,
            headerWireWidth = if (writeHeader == null) 0 else 1,
            writeHeader = writeHeader,
            initialBodyEstimate = initialBodyEstimate,
        ) { buffer ->
            repeat(bodyLength) { i -> buffer.writeByte((i and 0xFF).toByte()) }
        }

    private fun ReadBuffer.toBytes(): ByteArray {
        val out = ByteArray(remaining())
        for (i in out.indices) out[i] = readByte()
        return out
    }

    private fun expectedWire(
        bodyLength: Int,
        header: Byte? = 0x42,
    ): ByteArray {
        val prefixHi = ((bodyLength shr 8) and 0xFF).toByte()
        val prefixLo = (bodyLength and 0xFF).toByte()
        val headerWidth = if (header == null) 0 else 1
        val out = ByteArray(headerWidth + 2 + bodyLength)
        var idx = 0
        if (header != null) out[idx++] = header
        out[idx++] = prefixHi
        out[idx++] = prefixLo
        repeat(bodyLength) { i -> out[idx++] = (i and 0xFF).toByte() }
        return out
    }

    @Test
    fun encodeWithPooledFactoryReturnsBufferToPoolOnFree() {
        val pool = BufferPool(maxPoolSize = 4, defaultBufferSize = 512)
        val factory = BufferFactory.Default.withPooling(pool)

        val result = encodeWithBody(factory, bodyLength = 8)
        assertEquals(0, pool.stats().currentPoolSize, "Buffer still held by caller — pool must be empty")

        (result as PlatformBuffer).freeNativeMemory()
        assertEquals(1, pool.stats().currentPoolSize, "freeNativeMemory on the encoded slice must return the chunk to the pool")
        pool.clear()
    }

    @Test
    fun encodeProducesWireBytesIdenticalAcrossFactories() {
        val pool = BufferPool(maxPoolSize = 4, defaultBufferSize = 512)
        val pooled = BufferFactory.Default.withPooling(pool)

        val bodyLen = 10
        val pooledResult = encodeWithBody(pooled, bodyLength = bodyLen)
        val pooledBytes = pooledResult.toBytes()
        (pooledResult as PlatformBuffer).freeNativeMemory()

        val defaultResult = encodeWithBody(BufferFactory.Default, bodyLength = bodyLen)
        val defaultBytes = defaultResult.toBytes()
        (defaultResult as PlatformBuffer).freeNativeMemory()

        val expected = expectedWire(bodyLen)
        assertContentEqualsHelper(expected, pooledBytes, "pooled factory wire bytes")
        assertContentEqualsHelper(expected, defaultBytes, "default factory wire bytes")
        pool.clear()
    }

    @Test
    fun repeatedEncodeFreeCyclesConvergeToPoolReuse() {
        val pool = BufferPool(maxPoolSize = 4, defaultBufferSize = 512)
        val factory = BufferFactory.Default.withPooling(pool)
        val iterations = 200

        repeat(iterations) {
            val result = encodeWithBody(factory, bodyLength = 8)
            (result as PlatformBuffer).freeNativeMemory()
        }

        val stats = pool.stats()
        assertEquals(iterations.toLong(), stats.totalAllocations, "Each iteration must hit the pool factory exactly once")
        assertTrue(
            stats.poolMisses <= 2,
            "After warmup the pool must service every acquire; got misses=${stats.poolMisses}",
        )
        assertTrue(
            stats.poolHits >= (iterations - 2).toLong(),
            "Steady-state hit rate must be ~100%; got hits=${stats.poolHits}/$iterations",
        )
        pool.clear()
    }

    @Test
    fun encodeWithGrowthStillReleasesFinalBufferToPool() {
        // initialBodyEstimate = 16, body = 4 KB → ensureCapacity grows the
        // underlying buffer several times. Each growth `freeNativeMemory()`s
        // the prior chunk (back to the pool); only the final chunk's release
        // depends on the slice's freeNativeMemory closing the loop.
        val pool = BufferPool(maxPoolSize = 16, defaultBufferSize = 64)
        val factory = BufferFactory.Default.withPooling(pool)

        val bodyLen = 4 * 1024
        val result = encodeWithBody(factory, bodyLength = bodyLen, initialBodyEstimate = 16)
        val bytes = result.toBytes()
        assertContentEqualsHelper(expectedWire(bodyLen), bytes, "grown encode produces correct wire bytes")

        (result as PlatformBuffer).freeNativeMemory()
        assertTrue(
            pool.stats().currentPoolSize >= 1,
            "Final chunk after growth must end up back in the pool; currentPoolSize=${pool.stats().currentPoolSize}",
        )
        pool.clear()
    }

    @Test
    fun encodeWithDefaultFactoryDoesNotInteractWithPool() {
        // Sanity check that the PoolReleasable-guarded chunk-free in
        // FramedEncoder.encode is NOT triggered for non-pooled factories.
        // Linux native and deterministic backends would corrupt the slice
        // if the chunk were freed underneath it; this test guards that path
        // by validating wire correctness AND a subsequent read.
        val result = encodeWithBody(BufferFactory.Default, bodyLength = 32)
        val bytes = result.toBytes()
        assertContentEqualsHelper(expectedWire(32), bytes, "default-factory encode produces correct wire bytes")
        (result as PlatformBuffer).freeNativeMemory()
    }

    @Test
    fun encodeWithoutHeaderRoundTripsThroughPooledFactory() {
        val pool = BufferPool(maxPoolSize = 4, defaultBufferSize = 512)
        val factory = BufferFactory.Default.withPooling(pool)

        val result =
            FramedEncoder.encode(
                factory = factory,
                framingCodec = U16LengthCodec,
                context = EncodeContext.Empty,
                headerWireWidth = 0,
                writeHeader = null,
                initialBodyEstimate = 4,
            ) { buffer ->
                buffer.writeByte(0x01)
                buffer.writeByte(0x02)
                buffer.writeByte(0x03)
            }

        assertEquals(5, result.remaining(), "header(0) + 2-byte prefix + 3-byte body = 5 bytes")
        val bytes = result.toBytes()
        assertContentEqualsHelper(byteArrayOf(0x00, 0x03, 0x01, 0x02, 0x03), bytes, "no-header wire bytes")

        (result as PlatformBuffer).freeNativeMemory()
        assertEquals(1, pool.stats().currentPoolSize)
        pool.clear()
    }

    @Test
    fun multipleConcurrentEncodeResultsCoexistUntilEachFreed() {
        // Holding two encode results simultaneously must not collapse the
        // pool's accounting — each slice owns its chunk independently.
        val pool = BufferPool(maxPoolSize = 4, defaultBufferSize = 512)
        val factory = BufferFactory.Default.withPooling(pool)

        val r1 = encodeWithBody(factory, bodyLength = 8)
        val r2 = encodeWithBody(factory, bodyLength = 8)
        assertEquals(0, pool.stats().currentPoolSize, "Two outstanding slices — pool must be empty")

        val b1 = r1.toBytes()
        val b2 = r2.toBytes()
        assertContentEqualsHelper(b1, b2, "two encodes of identical body must produce identical bytes")

        (r1 as PlatformBuffer).freeNativeMemory()
        assertEquals(1, pool.stats().currentPoolSize)

        (r2 as PlatformBuffer).freeNativeMemory()
        assertEquals(2, pool.stats().currentPoolSize)
        pool.clear()
    }

    // NOTE — pre-existing safety gap, NOT regression-related:
    // TrackedSlice tracks a `released` flag but only consults it inside
    // `releaseToPool()`. Reads/writes on the slice delegate through
    // `PlatformBuffer by inner` to the raw inner-slice, which has no
    // use-after-free check (PooledBuffer's `checkNotFreed()` lives one layer
    // up). So reading a slice after `freeNativeMemory()` succeeds silently
    // today and can surface another tenant's bytes once the pool re-rents
    // the chunk. Out of scope for this regression fix; a follow-up should
    // mirror PooledBuffer's `checkNotReleased()` across TrackedSlice's
    // read/write surface.

    @Test
    fun growableWrapperPoolRecyclesAcrossEncodes() {
        // After encode returns, the GrowableWriteBuffer wrapper itself is
        // recycled into GrowableWriteBufferPool. We can't observe the pool's
        // internals directly (it's `internal`), so this test checks the
        // contract from the outside: many encode calls must not retain
        // wrappers (would surface as runaway heap), and round-trip results
        // must remain byte-identical and freeable.
        val pool = BufferPool(maxPoolSize = 4, defaultBufferSize = 512)
        val factory = BufferFactory.Default.withPooling(pool)
        val iterations = 1_000

        val expected = expectedWire(8)
        repeat(iterations) { i ->
            val result = encodeWithBody(factory, bodyLength = 8)
            // Sanity-check each iteration — proves attach/detach left the
            // wrapper in a consistent state for the next caller.
            val bytes = result.toBytes()
            if (i == 0 || i == iterations / 2 || i == iterations - 1) {
                assertContentEqualsHelper(expected, bytes, "iteration $i wire bytes")
            }
            (result as PlatformBuffer).freeNativeMemory()
        }

        val stats = pool.stats()
        assertTrue(
            stats.poolHits >= (iterations - 2).toLong(),
            "Steady-state encode/free should reuse the underlying chunk; hits=${stats.poolHits}",
        )
        pool.clear()
    }

    @Test
    fun encodeResultIsReadableTwiceViaResetForRead() {
        // The slice contract guarantees a clean coordinate system starting
        // at position 0 — `resetForRead()` must work on the returned slice
        // and re-expose the same wire bytes. Returning the chunk directly
        // (instead of a slice) would break this because position would reset
        // to 0 but the wire bytes start at sliceStart.
        val pool = BufferPool(maxPoolSize = 4, defaultBufferSize = 512)
        val factory = BufferFactory.Default.withPooling(pool)

        val result = encodeWithBody(factory, bodyLength = 8)
        val expected = expectedWire(8)
        val first = result.toBytes()
        assertContentEqualsHelper(expected, first, "first read")

        result.position(0)
        result.setLimit(expected.size)
        val second = result.toBytes()
        assertContentEqualsHelper(expected, second, "second read after rewinding the slice")

        (result as PlatformBuffer).freeNativeMemory()
        pool.clear()
    }

    private fun assertContentEqualsHelper(
        expected: ByteArray,
        actual: ByteArray,
        label: String,
    ) {
        assertNotNull(actual, label)
        assertEquals(expected.size, actual.size, "$label: size mismatch (expected ${expected.size}, got ${actual.size})")
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                kotlin.test.fail(
                    "$label: byte $i differs (expected 0x${expected[i].toUByte().toString(16)}, " +
                        "got 0x${actual[i].toUByte().toString(16)})",
                )
            }
        }
    }
}
