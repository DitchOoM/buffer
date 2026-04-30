package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PooledBuffer
import com.ditchoom.buffer.pool.TrackedSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Deeper coverage for `slice(byteOrder)` — beyond the field-level
 * contract in [BufferSliceByteOrderTests], these exercise that every
 * read/write path the slice's `byteOrder` field is supposed to drive
 * actually honors it. Catches backend bugs where the field is set
 * correctly but the impl reads through to `parent.byteOrder` anyway.
 *
 * Cross-platform: every test runs on every backend reachable through
 * [BufferFactory] presets on the host platform, plus the wrapper
 * types (`PooledBuffer`, `TrackedSlice`) per CLAUDE.md's
 * wrapper-transparency rule.
 */
class BufferSliceByteOrderBehaviorTests {
    private fun factories(): List<Pair<String, BufferFactory>> =
        listOf(
            "Default" to BufferFactory.Default,
            "managed" to BufferFactory.managed(),
            "deterministic" to BufferFactory.deterministic(),
        )

    /**
     * Filters [factories] to only those whose `slice()` is genuinely zero-copy
     * (writes through the slice are visible in the parent).
     *
     * `PlatformBuffer.slice()` is statically a `PlatformBuffer`, so writability
     * is a type-system guarantee — there's no runtime check for that. The
     * runtime check is for write *propagation*: a backend that copied on
     * slice would have a writable slice whose writes don't show up in the
     * parent, and this filter would catch it.
     *
     * The lone read-only-slice backend is `NSDataBufferSlice` (parent
     * `NSDataBuffer` wraps an immutable `NSData` and is `: ReadBuffer`,
     * not `: PlatformBuffer`). It's not reachable via the factory presets
     * enumerated here; only via `BufferFactory.Default.wrap(nsData)` directly.
     */
    private fun zeroCopySliceFactories(): List<Pair<String, BufferFactory>> =
        factories().filter { (_, factory) ->
            val parent = factory.allocate(8, ByteOrder.BIG_ENDIAN)
            val slice = parent.slice()
            slice.writeInt(0xCAFEBABE.toInt())
            // If the slice's write was visible in parent[0..3], it's zero-copy.
            parent[0] == 0xCA.toByte() && parent[1] == 0xFE.toByte() &&
                parent[2] == 0xBA.toByte() && parent[3] == 0xBE.toByte()
        }

    // ---------- read primitives respect slice byteOrder ----------

    @Test
    fun readShortRespectsSliceByteOrder() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(2, ByteOrder.BIG_ENDIAN)
            parent.writeShort(0x1234.toShort()) // wire: 12 34
            parent.resetForRead()

            val beSlice = parent.slice(ByteOrder.BIG_ENDIAN)
            assertEquals(0x1234.toShort(), beSlice.readShort(), "$name BE slice readShort")

            parent.position(0)
            val leSlice = parent.slice(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x3412.toShort(), leSlice.readShort(), "$name LE slice readShort")
        }
    }

    @Test
    fun readLongRespectsSliceByteOrder() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(8, ByteOrder.BIG_ENDIAN)
            parent.writeLong(0x0102030405060708L) // wire: 01 02 … 08
            parent.resetForRead()

            val beSlice = parent.slice(ByteOrder.BIG_ENDIAN)
            assertEquals(0x0102030405060708L, beSlice.readLong(), "$name BE slice readLong")

            parent.position(0)
            val leSlice = parent.slice(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x0807060504030201L, leSlice.readLong(), "$name LE slice readLong")
        }
    }

    @Test
    fun readFloatRespectsSliceByteOrder() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(4, ByteOrder.BIG_ENDIAN)
            // Pick a Float whose 4-byte representation differs in BE vs LE.
            // 1.0f in IEEE 754 = 0x3F800000.
            parent.writeFloat(1.0f)
            parent.resetForRead()

            val beSlice = parent.slice(ByteOrder.BIG_ENDIAN)
            assertEquals(1.0f, beSlice.readFloat(), "$name BE slice readFloat")

            parent.position(0)
            val leSlice = parent.slice(ByteOrder.LITTLE_ENDIAN)
            // LE-decoded bytes 00 00 80 3F = Float.fromBits(0x0000803F)
            assertEquals(Float.fromBits(0x0000803F), leSlice.readFloat(), "$name LE slice readFloat")
        }
    }

    @Test
    fun readDoubleRespectsSliceByteOrder() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(8, ByteOrder.BIG_ENDIAN)
            parent.writeDouble(1.0) // 0x3FF0000000000000 BE
            parent.resetForRead()

            val beSlice = parent.slice(ByteOrder.BIG_ENDIAN)
            assertEquals(1.0, beSlice.readDouble(), "$name BE slice readDouble")

            parent.position(0)
            val leSlice = parent.slice(ByteOrder.LITTLE_ENDIAN)
            assertEquals(Double.fromBits(0x000000000000F03FL), leSlice.readDouble(), "$name LE slice readDouble")
        }
    }

    // ---------- absolute-position reads respect slice byteOrder ----------

    @Test
    fun getShortAbsoluteRespectsSliceByteOrder() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(2, ByteOrder.BIG_ENDIAN)
            parent.writeShort(0x1234.toShort())
            parent.resetForRead()

            val beSlice = parent.slice(ByteOrder.BIG_ENDIAN)
            assertEquals(0x1234.toShort(), beSlice.getShort(0), "$name BE slice getShort(0)")

            parent.position(0)
            val leSlice = parent.slice(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x3412.toShort(), leSlice.getShort(0), "$name LE slice getShort(0)")
        }
    }

    @Test
    fun getIntAbsoluteRespectsSliceByteOrder() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(4, ByteOrder.BIG_ENDIAN)
            parent.writeInt(0x12345678)
            parent.resetForRead()

            val beSlice = parent.slice(ByteOrder.BIG_ENDIAN)
            assertEquals(0x12345678, beSlice.getInt(0), "$name BE slice getInt(0)")

            parent.position(0)
            val leSlice = parent.slice(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x78563412, leSlice.getInt(0), "$name LE slice getInt(0)")
        }
    }

    @Test
    fun getLongAbsoluteRespectsSliceByteOrder() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(8, ByteOrder.BIG_ENDIAN)
            parent.writeLong(0x0102030405060708L)
            parent.resetForRead()

            val beSlice = parent.slice(ByteOrder.BIG_ENDIAN)
            assertEquals(0x0102030405060708L, beSlice.getLong(0), "$name BE slice getLong(0)")

            parent.position(0)
            val leSlice = parent.slice(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x0807060504030201L, leSlice.getLong(0), "$name LE slice getLong(0)")
        }
    }

    // ---------- writes through slice respect slice byteOrder ----------

    @Test
    fun writesThroughSliceRespectSliceByteOrderShort() {
        // Restricted to zero-copy backends so the parent[0..1] assertion is
        // meaningful. After the slice-normalization work this is every
        // factory preset on every reachable platform.
        for ((name, factory) in zeroCopySliceFactories()) {
            val parent = factory.allocate(2, ByteOrder.BIG_ENDIAN)
            val slice = parent.slice(ByteOrder.LITTLE_ENDIAN)
            // The slice IS a writeable buffer too on every backend (PlatformBuffer).
            (slice as WriteBuffer).writeShort(0x1234.toShort()) // LE wire: 34 12

            // Read raw bytes from the parent via absolute reads — slice writes
            // don't advance the parent's position, so we can't use resetForRead.
            assertEquals(0x34.toByte(), parent[0], "$name byte 0 (LE-encoded 0x1234)")
            assertEquals(0x12.toByte(), parent[1], "$name byte 1 (LE-encoded 0x1234)")
        }
    }

    @Test
    fun writesThroughSliceRespectSliceByteOrderInt() {
        for ((name, factory) in zeroCopySliceFactories()) {
            val parent = factory.allocate(4, ByteOrder.BIG_ENDIAN)
            val slice = parent.slice(ByteOrder.LITTLE_ENDIAN)
            (slice as WriteBuffer).writeInt(0x12345678) // LE wire: 78 56 34 12

            assertEquals(0x78.toByte(), parent[0], "$name byte 0")
            assertEquals(0x56.toByte(), parent[1], "$name byte 1")
            assertEquals(0x34.toByte(), parent[2], "$name byte 2")
            assertEquals(0x12.toByte(), parent[3], "$name byte 3")
        }
    }

    @Test
    fun writesThroughSliceAreVisibleInParent() {
        for ((name, factory) in zeroCopySliceFactories()) {
            val parent = factory.allocate(8, ByteOrder.BIG_ENDIAN)
            // Pre-fill parent with a known pattern.
            (parent as WriteBuffer).writeLong(0L)
            parent.resetForRead()

            val slice = parent.slice(ByteOrder.BIG_ENDIAN)
            (slice as WriteBuffer).writeInt(0xCAFEBABE.toInt())

            // The parent should now see CAFEBABE in its first 4 bytes.
            parent.position(0)
            assertEquals(0xCAFEBABE.toInt(), (parent as ReadBuffer).readInt(), "$name parent sees slice writes")
        }
    }

    /**
     * Asserts the post-normalization invariant: every factory preset returns
     * a slice whose writes propagate to the parent. Writability is enforced
     * at the type level via `PlatformBuffer.slice(): PlatformBuffer`, so the
     * runtime assertion here is purely about write propagation.
     *
     * Read-only `NSDataBuffer` (and its `NSDataBufferSlice`) sit outside the
     * `PlatformBuffer` hierarchy and aren't exposed by the factory presets
     * iterated here, so the read-only case is structurally excluded.
     */
    @Test
    fun slicePropagatesWritesToParent() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(8, ByteOrder.BIG_ENDIAN)
            val slice = parent.slice()
            slice.writeInt(0xCAFEBABE.toInt())
            assertEquals(0xCA.toByte(), parent[0], "$name slice writes must propagate to parent[0]")
            assertEquals(0xFE.toByte(), parent[1], "$name slice writes must propagate to parent[1]")
            assertEquals(0xBA.toByte(), parent[2], "$name slice writes must propagate to parent[2]")
            assertEquals(0xBE.toByte(), parent[3], "$name slice writes must propagate to parent[3]")
        }
    }

    // ---------- concurrent independent slices ----------

    @Test
    fun multipleSlicesWithDifferentByteOrdersAreIndependent() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(4, ByteOrder.BIG_ENDIAN)
            parent.writeInt(0x12345678) // wire: 12 34 56 78
            parent.resetForRead()

            val beSlice = parent.slice(ByteOrder.BIG_ENDIAN)
            val leSlice = parent.slice(ByteOrder.LITTLE_ENDIAN)

            assertEquals(ByteOrder.BIG_ENDIAN, beSlice.byteOrder, "$name BE slice retains BE")
            assertEquals(ByteOrder.LITTLE_ENDIAN, leSlice.byteOrder, "$name LE slice retains LE")

            // Read same wire bytes through both slices simultaneously.
            // Each slice starts at position 0 with its own state.
            assertEquals(0x12345678, beSlice.readInt(), "$name BE reads BE")
            assertEquals(0x78563412, leSlice.readInt(), "$name LE reads LE")

            // Confirm decoded values differ — sanity check that the byte
            // orders genuinely produced different decoded results.
            assertNotEquals(0x12345678, 0x78563412, "$name decoded values differ")
        }
    }

    // ---------- empty slice ----------

    @Test
    fun emptySliceRetainsByteOrderField() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(8, ByteOrder.BIG_ENDIAN)
            parent.writeLong(0L)
            parent.resetForRead()
            parent.position(parent.limit()) // exhaust

            val emptyDefault = parent.slice()
            assertEquals(0, emptyDefault.remaining(), "$name empty slice has 0 remaining")
            assertEquals(ByteOrder.BIG_ENDIAN, emptyDefault.byteOrder, "$name empty default slice byteOrder")

            val emptyLE = parent.slice(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0, emptyLE.remaining(), "$name empty LE slice has 0 remaining")
            assertEquals(ByteOrder.LITTLE_ENDIAN, emptyLE.byteOrder, "$name empty LE slice byteOrder")
        }
    }

    // ---------- PooledBuffer + TrackedSlice transparency (CLAUDE.md rule) ----------

    @Test
    fun pooledBufferSlicePreservesByteOrder() {
        val pool = BufferPool()
        try {
            val pooled = pool.acquire(8) as PooledBuffer
            // PooledBuffer's byteOrder reflects the underlying buffer.
            val parentOrder = pooled.byteOrder

            val slice = pooled.slice() // bare → preserves parent
            assertEquals(parentOrder, slice.byteOrder, "PooledBuffer.slice() preserves byteOrder")

            // Wrapper-transparency check: the returned slice IS a TrackedSlice.
            assertTrue(slice is TrackedSlice, "PooledBuffer.slice() returns TrackedSlice")
        } finally {
            pool.clear()
        }
    }

    @Test
    fun pooledBufferSliceWithExplicitByteOrderOverrides() {
        val pool = BufferPool()
        try {
            val pooled = pool.acquire(8) as PooledBuffer
            val parentOrder = pooled.byteOrder
            val targetOrder =
                if (parentOrder == ByteOrder.BIG_ENDIAN) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

            val slice = pooled.slice(targetOrder)
            assertEquals(targetOrder, slice.byteOrder, "PooledBuffer.slice(target) overrides")
            assertNotEquals(parentOrder, slice.byteOrder, "override disagrees with parent")
            assertTrue(slice is TrackedSlice, "still a TrackedSlice")
        } finally {
            pool.clear()
        }
    }

    @Test
    fun trackedSliceSliceOfSliceByteOrderOverride() {
        val pool = BufferPool()
        try {
            val pooled = pool.acquire(16) as PooledBuffer
            val firstSlice = pooled.slice(ByteOrder.BIG_ENDIAN)
            assertEquals(ByteOrder.BIG_ENDIAN, firstSlice.byteOrder, "first slice BE")

            // Slice the slice with a different byte order — must propagate the override.
            val secondSlice = firstSlice.slice(ByteOrder.LITTLE_ENDIAN)
            assertEquals(ByteOrder.LITTLE_ENDIAN, secondSlice.byteOrder, "slice-of-slice LE override")
            assertTrue(secondSlice is TrackedSlice, "slice-of-TrackedSlice still TrackedSlice")
        } finally {
            pool.clear()
        }
    }

    @Test
    fun pooledBufferSliceWritesPropagateToParent() {
        // Pool slice is now a writable PlatformBuffer (via PlatformBuffer.slice()
        // covariant return). Writes through the slice must land in the pool
        // buffer's underlying memory, just like for non-pooled slices.
        val pool = BufferPool()
        try {
            val pooled = pool.acquire(8) as PooledBuffer
            val slice = pooled.slice() // statically PlatformBuffer
            slice.writeInt(0xCAFEBABE.toInt())
            assertEquals(0xCA.toByte(), pooled[0], "pool slice write must propagate to pooled[0]")
            assertEquals(0xFE.toByte(), pooled[1], "pool slice write must propagate to pooled[1]")
            assertEquals(0xBA.toByte(), pooled[2], "pool slice write must propagate to pooled[2]")
            assertEquals(0xBE.toByte(), pooled[3], "pool slice write must propagate to pooled[3]")
        } finally {
            pool.clear()
        }
    }

    @Test
    fun pooledBufferSliceReadsRespectByteOrder() {
        val pool = BufferPool()
        try {
            val pooled = pool.acquire(4) as PooledBuffer
            (pooled as WriteBuffer).writeInt(0x12345678) // pool buffers default to parent's order, typically BE
            (pooled as ReadBuffer).resetForRead()

            // Take a BE slice and an LE slice, read same bytes.
            val beSlice = pooled.slice(ByteOrder.BIG_ENDIAN)
            val leSlice = pooled.slice(ByteOrder.LITTLE_ENDIAN)
            // The pooled buffer must have been BE (default) for the writeInt to have produced 12 34 56 78.
            // If the pool default ever changes, this assertion documents it.
            assertEquals(ByteOrder.BIG_ENDIAN, pooled.byteOrder, "pool buffer is BE by default")
            assertEquals(0x12345678, beSlice.readInt(), "BE slice reads BE")
            assertEquals(0x78563412, leSlice.readInt(), "LE slice reads LE")
        } finally {
            pool.clear()
        }
    }
}
