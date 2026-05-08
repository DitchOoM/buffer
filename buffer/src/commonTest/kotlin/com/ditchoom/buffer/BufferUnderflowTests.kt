package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests that all platforms throw [BufferUnderflowException] consistently
 * when read operations exceed the buffer's available bytes.
 *
 * Mirrors [BufferOverflowTests] for the read side. Runs on every platform
 * (JVM, JS, WASM, Apple, Linux) and across all factory types so the common
 * exception contract holds end-to-end — including the JVM wrap that converts
 * native [java.nio.BufferUnderflowException] in [BaseJvmBuffer].
 */
class BufferUnderflowTests {
    private val factories =
        listOf(
            "Default" to BufferFactory.Default,
            "managed" to BufferFactory.managed(),
            "shared" to BufferFactory.shared(),
        )

    private fun emptyForRead(factory: BufferFactory): ReadBuffer {
        val buffer = factory.allocate(0)
        buffer.resetForRead()
        return buffer
    }

    private fun shortForRead(
        factory: BufferFactory,
        bytes: Int,
    ): ReadBuffer {
        val buffer = factory.allocate(bytes)
        repeat(bytes) { buffer.writeByte(0x00) }
        buffer.resetForRead()
        return buffer
    }

    // ====================================================================
    // Relative read underflow
    // ====================================================================

    @Test
    fun readByteUnderflowThrows() {
        for ((name, factory) in factories) {
            val buffer = emptyForRead(factory)
            assertFailsWith<BufferUnderflowException>("$name: readByte on empty buffer") {
                buffer.readByte()
            }
        }
    }

    @Test
    fun readShortUnderflowThrows() {
        for ((name, factory) in factories) {
            val buffer = shortForRead(factory, 1)
            assertFailsWith<BufferUnderflowException>("$name: readShort with 1 byte remaining") {
                buffer.readShort()
            }
        }
    }

    @Test
    fun readIntUnderflowThrows() {
        for ((name, factory) in factories) {
            val buffer = shortForRead(factory, 3)
            assertFailsWith<BufferUnderflowException>("$name: readInt with 3 bytes remaining") {
                buffer.readInt()
            }
        }
    }

    @Test
    fun readLongUnderflowThrows() {
        for ((name, factory) in factories) {
            val buffer = shortForRead(factory, 7)
            assertFailsWith<BufferUnderflowException>("$name: readLong with 7 bytes remaining") {
                buffer.readLong()
            }
        }
    }

    @Test
    fun readFloatUnderflowThrows() {
        for ((name, factory) in factories) {
            val buffer = shortForRead(factory, 3)
            assertFailsWith<BufferUnderflowException>("$name: readFloat with 3 bytes remaining") {
                buffer.readFloat()
            }
        }
    }

    @Test
    fun readDoubleUnderflowThrows() {
        for ((name, factory) in factories) {
            val buffer = shortForRead(factory, 7)
            assertFailsWith<BufferUnderflowException>("$name: readDouble with 7 bytes remaining") {
                buffer.readDouble()
            }
        }
    }

    @Test
    fun readByteArrayUnderflowThrows() {
        for ((name, factory) in factories) {
            val buffer = shortForRead(factory, 4)
            assertFailsWith<BufferUnderflowException>("$name: readByteArray(8) with 4 bytes remaining") {
                buffer.readByteArray(8)
            }
        }
    }

    // ====================================================================
    // Absolute get underflow
    // ====================================================================

    @Test
    fun getByteOutOfBoundsThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(2)
            buffer.writeByte(0x00)
            buffer.writeByte(0x00)
            buffer.resetForRead()
            assertFailsWith<BufferUnderflowException>("$name: get(5) with limit=2") {
                buffer.get(5)
            }
        }
    }

    @Test
    fun getShortOutOfBoundsThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(2)
            buffer.writeByte(0x00)
            buffer.writeByte(0x00)
            buffer.resetForRead()
            assertFailsWith<BufferUnderflowException>("$name: getShort(1) with limit=2") {
                buffer.getShort(1)
            }
        }
    }

    @Test
    fun getIntOutOfBoundsThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            repeat(4) { buffer.writeByte(0x00) }
            buffer.resetForRead()
            assertFailsWith<BufferUnderflowException>("$name: getInt(1) with limit=4") {
                buffer.getInt(1)
            }
        }
    }

    @Test
    fun getLongOutOfBoundsThrows() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(8)
            repeat(8) { buffer.writeByte(0x00) }
            buffer.resetForRead()
            assertFailsWith<BufferUnderflowException>("$name: getLong(1) with limit=8") {
                buffer.getLong(1)
            }
        }
    }
}
