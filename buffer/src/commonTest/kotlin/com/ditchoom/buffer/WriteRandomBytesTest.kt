package com.ditchoom.buffer

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WriteRandomBytesTest {
    @Test
    fun writesExactCountAndAdvancesPosition() {
        val buffer = BufferFactory.Default.allocate(32)
        buffer.writeRandomBytes(16)
        assertEquals(16, buffer.position())
    }

    @Test
    fun deterministicWithSeededRandomAcrossInvocations() {
        val a = BufferFactory.Default.allocate(16)
        val b = BufferFactory.Default.allocate(16)
        a.writeRandomBytes(16, Random(42))
        b.writeRandomBytes(16, Random(42))
        a.resetForRead()
        b.resetForRead()
        assertContentEquals(a.readByteArray(16), b.readByteArray(16))
    }

    @Test
    fun differentSeedsProduceDifferentBytes() {
        val a = BufferFactory.Default.allocate(16)
        val b = BufferFactory.Default.allocate(16)
        a.writeRandomBytes(16, Random(1))
        b.writeRandomBytes(16, Random(2))
        a.resetForRead()
        b.resetForRead()
        assertNotEquals(
            a.readByteArray(16).toList(),
            b.readByteArray(16).toList(),
        )
    }

    @Test
    fun tailSmallerThanEightBytes() {
        // Exercises the single-byte tail loop for counts not divisible by 8.
        for (n in 1..7) {
            val buf = BufferFactory.Default.allocate(n)
            buf.writeRandomBytes(n, Random(7))
            assertEquals(n, buf.position())
        }
    }

    @Test
    fun mixedBulkAndTail() {
        // 19 bytes = 2 * 8 bulk chunks + 3 tail bytes
        val buf = BufferFactory.Default.allocate(19)
        buf.writeRandomBytes(19, Random(123))
        assertEquals(19, buf.position())
    }

    @Test
    fun zeroCountIsNoOp() {
        val buf = BufferFactory.Default.allocate(16)
        buf.writeRandomBytes(0)
        assertEquals(0, buf.position())
    }

    @Test
    fun negativeCountThrows() {
        val buf = BufferFactory.Default.allocate(16)
        assertFailsWith<IllegalArgumentException> { buf.writeRandomBytes(-1) }
    }

    @Test
    fun overflowThrows() {
        val buf = BufferFactory.Default.allocate(8)
        assertFailsWith<BufferOverflowException> { buf.writeRandomBytes(16) }
    }

    @Test
    fun outputHasReasonableEntropy() {
        // Not a statistical test — just verifies the bytes aren't all
        // zeros (catches a common "forgot to use the random parameter" bug).
        val buf = BufferFactory.Default.allocate(64)
        buf.writeRandomBytes(64, Random(0))
        buf.resetForRead()
        val bytes = buf.readByteArray(64)
        val zeros = bytes.count { it == 0.toByte() }
        assertTrue(zeros < 32, "Expected <50% zero bytes, got $zeros/64")
    }
}
