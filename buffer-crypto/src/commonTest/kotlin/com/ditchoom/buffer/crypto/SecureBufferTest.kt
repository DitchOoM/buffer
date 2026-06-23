package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecureBufferTest {
    // A managed (heap) delegate is used so the buffer stays readable AFTER freeNativeMemory()
    // (managed free is a GC no-op), letting the test observe the wipe in isolation.
    private val secureManaged = BufferFactory.managed().secure()

    @Test
    fun factoryProducesSecureBuffer() {
        assertTrue(secureManaged.allocate(16) is SecureBuffer)
    }

    @Test
    fun freeWipesEveryByte() {
        val buf = secureManaged.allocate(16)
        repeat(16) { buf.writeByte(0xFF.toByte()) }
        buf.freeNativeMemory()
        for (i in 0 until 16) assertEquals(0, buf.get(i).toInt(), "byte $i not wiped")
    }

    @Test
    fun freeWipesFullCapacityNotJustRemaining() {
        // Leave position mid-buffer: fill() only covers position..limit, so the wipe must
        // first widen the window to 0..capacity. If it didn't, bytes 0..7 would survive.
        val buf = secureManaged.allocate(16)
        repeat(16) { buf.writeByte(0xFF.toByte()) }
        buf.position(8)
        buf.freeNativeMemory()
        for (i in 0 until 16) assertEquals(0, buf.get(i).toInt(), "byte $i not wiped")
    }

    @Test
    fun doubleFreeIsIdempotent() {
        val buf = secureManaged.allocate(8)
        repeat(8) { buf.writeByte(0x7F) }
        buf.freeNativeMemory()
        buf.freeNativeMemory()
        for (i in 0 until 8) assertEquals(0, buf.get(i).toInt())
    }

    // --- DoS cap: maxAllocationBytes ---

    @Test
    fun allocateWithinCapSucceeds() {
        val secure = BufferFactory.managed().secure(maxAllocationBytes = 64)
        assertTrue(secure.allocate(64) is SecureBuffer)
        assertTrue(secure.allocate(0) is SecureBuffer)
    }

    @Test
    fun allocateAboveCapThrows() {
        val secure = BufferFactory.managed().secure(maxAllocationBytes = 64)
        assertFailsWith<IllegalArgumentException> { secure.allocate(65) }
    }

    @Test
    fun wrapAboveCapThrows() {
        val secure = BufferFactory.managed().secure(maxAllocationBytes = 4)
        assertFailsWith<IllegalArgumentException> { secure.wrap(ByteArray(5)) }
        // At the boundary it still succeeds.
        assertTrue(secure.wrap(ByteArray(4)) is SecureBuffer)
    }

    @Test
    fun nonPositiveCapIsRejected() {
        assertFailsWith<IllegalArgumentException> { BufferFactory.managed().secure(maxAllocationBytes = 0) }
        assertFailsWith<IllegalArgumentException> { BufferFactory.managed().secure(maxAllocationBytes = -1) }
    }

    @Test
    fun defaultCapIsUnbounded() {
        // No cap argument == backward-compatible unbounded behavior.
        assertTrue(BufferFactory.managed().secure().allocate(1 shl 20) is SecureBuffer)
    }
}
