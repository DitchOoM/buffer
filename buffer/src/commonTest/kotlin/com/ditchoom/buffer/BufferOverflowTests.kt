package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests that all platforms throw [BufferOverflowException] consistently
 * when write operations exceed buffer capacity.
 *
 * These tests run on every platform (JVM, JS, WASM, Apple, Linux) and
 * across all factory types (Default, managed, shared).
 */
class BufferOverflowTests {
    private val factories =
        listOf(
            "Default" to BufferFactory.Default,
            "managed" to BufferFactory.managed(),
            "shared" to BufferFactory.shared(),
        )

    // ====================================================================
    // Relative write overflow tests
    // ====================================================================

    @Test
    fun writeByteOverflowThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(0)
            assertFailsWith<BufferOverflowException>("$name: writeByte on empty buffer") {
                buffer.writeByte(0x42)
            }
        }
    }

    @Test
    fun writeShortOverflowThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(1)
            assertFailsWith<BufferOverflowException>("$name: writeShort with 1 byte remaining") {
                buffer.writeShort(0x1234.toShort())
            }
        }
    }

    @Test
    fun writeIntOverflowThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(3)
            assertFailsWith<BufferOverflowException>("$name: writeInt with 3 bytes remaining") {
                buffer.writeInt(0x12345678)
            }
        }
    }

    @Test
    fun writeLongOverflowThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(7)
            assertFailsWith<BufferOverflowException>("$name: writeLong with 7 bytes remaining") {
                buffer.writeLong(0x123456789ABCDEF0)
            }
        }
    }

    @Test
    fun writeFloatOverflowThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(3)
            assertFailsWith<BufferOverflowException>("$name: writeFloat with 3 bytes remaining") {
                buffer.writeFloat(1.0f)
            }
        }
    }

    @Test
    fun writeDoubleOverflowThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(7)
            assertFailsWith<BufferOverflowException>("$name: writeDouble with 7 bytes remaining") {
                buffer.writeDouble(1.0)
            }
        }
    }

    @Test
    fun writeBytesOverflowThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(2)
            assertFailsWith<BufferOverflowException>("$name: writeBytes with insufficient space") {
                buffer.writeBytes(byteArrayOf(1, 2, 3))
            }
        }
    }

    @Test
    fun writeReadBufferOverflowThrows() {
        for ((name, factory) in factories) {
            val source = factory.allocate(4)
            source.writeInt(42)
            source.resetForRead()

            val dest = factory.allocate(2)
            assertFailsWith<BufferOverflowException>("$name: write(ReadBuffer) with insufficient space") {
                dest.write(source)
            }
        }
    }

    @Test
    fun writeUByteOverflowThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(0)
            assertFailsWith<BufferOverflowException>("$name: writeUByte on empty buffer") {
                buffer.writeUByte(0x42u)
            }
        }
    }

    @Test
    fun writeUShortOverflowThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(1)
            assertFailsWith<BufferOverflowException>("$name: writeUShort with 1 byte remaining") {
                buffer.writeUShort(0x1234u.toUShort())
            }
        }
    }

    @Test
    fun writeUIntOverflowThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(3)
            assertFailsWith<BufferOverflowException>("$name: writeUInt with 3 bytes remaining") {
                buffer.writeUInt(0x12345678u)
            }
        }
    }

    @Test
    fun writeULongOverflowThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(7)
            assertFailsWith<BufferOverflowException>("$name: writeULong with 7 bytes remaining") {
                buffer.writeULong(0x123456789ABCDEFu)
            }
        }
    }

    // ====================================================================
    // Absolute write (set) overflow tests
    // ====================================================================

    @Test
    fun setByteAtLimitThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            assertFailsWith<BufferOverflowException>("$name: set byte at limit") {
                buffer[4] = 0x42.toByte()
            }
        }
    }

    @Test
    fun setShortPastLimitThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            assertFailsWith<BufferOverflowException>("$name: set short at index 3 (needs 2 bytes, limit=4)") {
                buffer[3] = 0x1234.toShort()
            }
        }
    }

    @Test
    fun setIntPastLimitThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            assertFailsWith<BufferOverflowException>("$name: set int at index 1 (needs 4 bytes, limit=4)") {
                buffer[1] = 0x12345678
            }
        }
    }

    @Test
    fun setLongPastLimitThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(8)
            assertFailsWith<BufferOverflowException>("$name: set long at index 1 (needs 8 bytes, limit=8)") {
                buffer[1] = 0x123456789ABCDEF0L
            }
        }
    }

    @Test
    fun setByteAtNegativeIndexThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            assertFailsWith<BufferOverflowException>("$name: set byte at negative index") {
                buffer[-1] = 0x42.toByte()
            }
        }
    }

    @Test
    fun setShortAtNegativeIndexThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            assertFailsWith<BufferOverflowException>("$name: set short at negative index") {
                buffer[-1] = 0x1234.toShort()
            }
        }
    }

    @Test
    fun setIntAtNegativeIndexThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            assertFailsWith<BufferOverflowException>("$name: set int at negative index") {
                buffer[-1] = 42
            }
        }
    }

    @Test
    fun setLongAtNegativeIndexThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(8)
            assertFailsWith<BufferOverflowException>("$name: set long at negative index") {
                buffer[-1] = 42L
            }
        }
    }

    // ====================================================================
    // Buffer state preservation after failed write
    // ====================================================================

    @Test
    fun positionUnchangedAfterFailedWriteByte() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(2)
            buffer.writeByte(0x01)
            buffer.writeByte(0x02)
            val positionBefore = buffer.position()
            assertFailsWith<BufferOverflowException>("$name") {
                buffer.writeByte(0x03)
            }
            assertEquals(positionBefore, buffer.position(), "$name: position should not change after failed write")
        }
    }

    @Test
    fun positionUnchangedAfterFailedWriteInt() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(6)
            buffer.writeInt(42)
            val positionBefore = buffer.position()
            assertFailsWith<BufferOverflowException>("$name") {
                buffer.writeInt(99)
            }
            assertEquals(positionBefore, buffer.position(), "$name: position should not change after failed writeInt")
        }
    }

    @Test
    fun dataPreservedAfterFailedWrite() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            buffer.writeInt(0x12345678)
            assertFailsWith<BufferOverflowException>("$name") {
                buffer.writeByte(0xFF.toByte())
            }
            buffer.resetForRead()
            assertEquals(0x12345678, buffer.readInt(), "$name: data should be preserved after failed write")
        }
    }

    // ====================================================================
    // Exact-fit success tests (no false positives)
    // ====================================================================

    @Test
    fun writeByteExactFitSucceeds() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(1)
            buffer.writeByte(0x42)
            assertEquals(1, buffer.position(), "$name: writeByte exact fit")
        }
    }

    @Test
    fun writeShortExactFitSucceeds() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(2)
            buffer.writeShort(0x1234.toShort())
            assertEquals(2, buffer.position(), "$name: writeShort exact fit")
        }
    }

    @Test
    fun writeIntExactFitSucceeds() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            buffer.writeInt(0x12345678)
            assertEquals(4, buffer.position(), "$name: writeInt exact fit")
        }
    }

    @Test
    fun writeLongExactFitSucceeds() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(8)
            buffer.writeLong(0x123456789ABCDEF0)
            assertEquals(8, buffer.position(), "$name: writeLong exact fit")
        }
    }

    @Test
    fun writeFloatExactFitSucceeds() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            buffer.writeFloat(3.14f)
            assertEquals(4, buffer.position(), "$name: writeFloat exact fit")
        }
    }

    @Test
    fun writeDoubleExactFitSucceeds() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(8)
            buffer.writeDouble(3.14)
            assertEquals(8, buffer.position(), "$name: writeDouble exact fit")
        }
    }

    @Test
    fun writeBytesExactFitSucceeds() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(3)
            buffer.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(3, buffer.position(), "$name: writeBytes exact fit")
        }
    }

    @Test
    fun writeReadBufferExactFitSucceeds() {
        for ((name, factory) in factories) {
            val source = factory.allocate(4)
            source.writeInt(42)
            source.resetForRead()

            val dest = factory.allocate(4)
            dest.write(source)
            assertEquals(4, dest.position(), "$name: write(ReadBuffer) exact fit")
        }
    }

    // ====================================================================
    // Absolute write (set) exact-fit success
    // ====================================================================

    @Test
    fun setByteAtLastIndexSucceeds() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            buffer[3] = 0x42.toByte()
            assertEquals(0x42.toByte(), buffer.get(3), "$name: set byte at last valid index")
        }
    }

    @Test
    fun setShortAtLastValidIndexSucceeds() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            buffer[2] = 0x1234.toShort()
            assertEquals(0x1234.toShort(), buffer.getShort(2), "$name: set short at last valid index")
        }
    }

    @Test
    fun setIntAtIndex0Succeeds() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            buffer[0] = 0x12345678
            assertEquals(0x12345678, buffer.getInt(0), "$name: set int at index 0")
        }
    }

    @Test
    fun setLongAtIndex0Succeeds() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(8)
            buffer[0] = 0x123456789ABCDEF0L
            assertEquals(0x123456789ABCDEF0L, buffer.getLong(0), "$name: set long at index 0")
        }
    }

    // ====================================================================
    // Zero-byte write edge cases
    // ====================================================================

    @Test
    fun writeEmptyBytesOnFullBufferSucceeds() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(0)
            buffer.writeBytes(byteArrayOf())
            assertEquals(0, buffer.position(), "$name: empty writeBytes on empty buffer")
        }
    }

    @Test
    fun writeEmptyReadBufferOnFullBufferSucceeds() {
        for ((name, factory) in factories) {
            val source = factory.allocate(0)
            source.resetForRead()
            val dest = factory.allocate(0)
            dest.write(source)
            assertEquals(0, dest.position(), "$name: write empty ReadBuffer on empty buffer")
        }
    }
}
