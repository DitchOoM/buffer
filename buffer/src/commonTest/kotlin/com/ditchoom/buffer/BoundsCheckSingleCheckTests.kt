package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Structural regression tests that verify bounds checks fire exactly once per
 * write operation, not multiple times from nested delegation.
 *
 * The approach: write up to the exact limit, then verify position is correct.
 * If double-checking existed, the operations would still succeed (same result),
 * but compound writes that delegate to sub-methods would fail if the sub-method's
 * check used stale position math. This test catches the scenario where a relative
 * write (e.g., writeShort) delegates to set(index, short) and both check — if
 * either check is wrong, the test breaks.
 *
 * More importantly, these tests verify that compound writes work correctly when
 * writing right up to the buffer boundary — the exact scenario where double-check
 * bugs surface (first check passes, inner check uses a different position/index
 * calculation and may disagree).
 */
class BoundsCheckSingleCheckTests {
    private val factories =
        listOf(
            "Default" to BufferFactory.Default,
            "managed" to BufferFactory.managed(),
        )

    /**
     * Fill buffer with writeShort calls up to exact capacity.
     * If writeShort double-checks (relative + absolute), a boundary
     * rounding issue could reject the last valid write.
     */
    @Test
    fun writeShortFillsToExactCapacity() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(100)
            // Write 50 shorts = 100 bytes = exact capacity
            repeat(50) { buffer.writeShort((it % 256).toShort()) }
            assertEquals(100, buffer.position(), "$name: writeShort should fill to capacity")
        }
    }

    /**
     * Fill buffer with writeInt calls up to exact capacity.
     */
    @Test
    fun writeIntFillsToExactCapacity() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(100)
            // Write 25 ints = 100 bytes
            repeat(25) { buffer.writeInt(it) }
            assertEquals(100, buffer.position(), "$name: writeInt should fill to capacity")
        }
    }

    /**
     * Fill buffer with writeLong calls up to exact capacity.
     * writeLong is the most deeply nested (long → int × 2 on some platforms),
     * so this catches triple-check bugs.
     */
    @Test
    fun writeLongFillsToExactCapacity() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(104)
            // Write 13 longs = 104 bytes
            repeat(13) { buffer.writeLong(it.toLong()) }
            assertEquals(104, buffer.position(), "$name: writeLong should fill to capacity")
        }
    }

    /**
     * Interleave different write sizes to exercise mixed-size boundary conditions.
     */
    @Test
    fun interleavedWritesFillToExactCapacity() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(15)
            buffer.writeByte(0x01) // 1 byte, pos=1
            buffer.writeShort(0x0203.toShort()) // 2 bytes, pos=3
            buffer.writeInt(0x04050607) // 4 bytes, pos=7
            buffer.writeLong(0x08090A0B0C0D0E0FL) // 8 bytes, pos=15
            assertEquals(15, buffer.position(), "$name: interleaved writes should fill to 15")
        }
    }

    /**
     * Absolute set() at last valid indices for each type.
     * Verifies set() checks work correctly at boundary without interfering
     * with relative write state.
     */
    @Test
    fun absoluteSetAtBoundaryDoesNotCorruptPosition() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(16)
            buffer.writeInt(42) // advance position to 4
            val posBefore = buffer.position()

            // Absolute writes at last valid indices — must not change position
            buffer[14] = 0x1234.toShort() // set short at index 14, needs 2 bytes, limit=16
            buffer[12] = 0x12345678 // set int at index 12
            buffer[8] = 0x123456789ABCDEF0L // set long at index 8

            assertEquals(posBefore, buffer.position(), "$name: absolute set must not change position")
        }
    }

    /**
     * write(ReadBuffer) with exact-fit source — single check, no per-byte re-check.
     */
    @Test
    fun writeReadBufferExactFitLargePayload() {
        for ((name, factory) in factories) {
            val source = factory.allocate(8192)
            repeat(8192) { source.writeByte((it % 251).toByte()) }
            source.resetForRead()

            val dest = factory.allocate(8192)
            dest.write(source)
            assertEquals(8192, dest.position(), "$name: write(ReadBuffer) exact fit 8KB")

            // Verify data integrity
            dest.resetForRead()
            for (i in 0 until 8192) {
                assertEquals(
                    (i % 251).toByte(),
                    dest.readByte(),
                    "$name: byte mismatch at $i",
                )
            }
        }
    }
}
