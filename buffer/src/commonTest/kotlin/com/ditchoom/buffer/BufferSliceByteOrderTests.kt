package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-platform proof that `slice()` propagates byte order consistently
 * across every backend (JVM heap/direct, Apple, JS, WASM, Native, etc.)
 * and that the optional `slice(byteOrder = …)` override works the same
 * everywhere.
 *
 * Background: Java NIO's `ByteBuffer.slice()` resets the slice's byte
 * order to BIG_ENDIAN regardless of the parent's order — a footgun the
 * `:buffer` library used to inherit on JVM. After this commit, all
 * backends preserve the parent's byte order by default and accept an
 * explicit override. These tests are the contract.
 *
 * Each test exercises every available [BufferFactory] preset on the
 * current platform — `Default`, `managed()`, `deterministic()`, etc. —
 * so it surfaces inconsistencies between backends on the same OS.
 */
class BufferSliceByteOrderTests {
    private fun factories(): List<Pair<String, BufferFactory>> =
        listOf(
            "Default" to BufferFactory.Default,
            "managed" to BufferFactory.managed(),
            "deterministic" to BufferFactory.deterministic(),
        )

    @Test
    fun bareSlicePreservesParentByteOrderBigEndian() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(16, ByteOrder.BIG_ENDIAN)
            assertEquals(ByteOrder.BIG_ENDIAN, parent.byteOrder, "$name parent")

            val slice = parent.slice()
            assertEquals(ByteOrder.BIG_ENDIAN, slice.byteOrder, "$name bare slice should preserve BE")
        }
    }

    @Test
    fun bareSlicePreservesParentByteOrderLittleEndian() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(16, ByteOrder.LITTLE_ENDIAN)
            assertEquals(ByteOrder.LITTLE_ENDIAN, parent.byteOrder, "$name parent")

            val slice = parent.slice()
            assertEquals(ByteOrder.LITTLE_ENDIAN, slice.byteOrder, "$name bare slice should preserve LE")
        }
    }

    @Test
    fun explicitByteOrderOverridesParentBeToLe() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(16, ByteOrder.BIG_ENDIAN)
            val slice = parent.slice(ByteOrder.LITTLE_ENDIAN)
            assertEquals(ByteOrder.LITTLE_ENDIAN, slice.byteOrder, "$name BE→LE override")
            // Parent unchanged.
            assertEquals(ByteOrder.BIG_ENDIAN, parent.byteOrder, "$name parent unchanged")
        }
    }

    @Test
    fun explicitByteOrderOverridesParentLeToBe() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(16, ByteOrder.LITTLE_ENDIAN)
            val slice = parent.slice(ByteOrder.BIG_ENDIAN)
            assertEquals(ByteOrder.BIG_ENDIAN, slice.byteOrder, "$name LE→BE override")
            assertEquals(ByteOrder.LITTLE_ENDIAN, parent.byteOrder, "$name parent unchanged")
        }
    }

    @Test
    fun sliceReadsRespectSliceByteOrder() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(8, ByteOrder.BIG_ENDIAN)
            // Write 0x12345678 in BE order on the parent.
            parent.writeInt(0x12345678)
            parent.resetForRead()

            // Bare slice should read BE → 0x12345678.
            val beSlice = parent.slice()
            assertEquals(0x12345678, beSlice.readInt(), "$name bare slice reads BE")

            // Slice with LE override should read the same wire bytes as 0x78563412.
            parent.position(0)
            val leSlice = parent.slice(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x78563412, leSlice.readInt(), "$name LE slice reads same bytes as 0x78563412")
        }
    }

    @Test
    fun sliceReadsAfterPositionAdvance() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(16, ByteOrder.BIG_ENDIAN)
            parent.writeInt(0xCAFEBABE.toInt()) // skip these 4 bytes
            parent.writeInt(0xDEADBEEF.toInt()) // slice should expose these
            parent.resetForRead()
            parent.position(4)

            val slice = parent.slice()
            assertEquals(4, slice.remaining(), "$name slice covers only post-position bytes")
            assertEquals(0xDEADBEEF.toInt(), slice.readInt(), "$name slice reads payload")
            // Slicing must not move the parent's position.
            assertEquals(4, parent.position(), "$name parent position unchanged by slice()")
        }
    }

    @Test
    fun sliceOfSlicePropagatesAcrossLevels() {
        for ((name, factory) in factories()) {
            val parent = factory.allocate(16, ByteOrder.LITTLE_ENDIAN)
            val firstSlice = parent.slice()
            assertEquals(ByteOrder.LITTLE_ENDIAN, firstSlice.byteOrder, "$name first slice")
            val secondSlice = firstSlice.slice()
            assertEquals(ByteOrder.LITTLE_ENDIAN, secondSlice.byteOrder, "$name slice-of-slice")
            // Override at the inner level survives.
            val overridden = firstSlice.slice(ByteOrder.BIG_ENDIAN)
            assertEquals(ByteOrder.BIG_ENDIAN, overridden.byteOrder, "$name inner override")
        }
    }
}
