package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 0.6 — pins desktop JVM's documented `isDirect` / `hasArray()` shape
 * across the `BufferFactory` presets and a range of sizes. Informational
 * (drives doc), not load-bearing for the buffer-codec lockdown design.
 *
 * Desktop OpenJDK shape (verified via JDK 22 source — `ByteBuffer.java:1486`
 * + `DirectByteBuffer` constructors): `DirectByteBuffer` has `hb == null`
 * and therefore `hasArray() == false`, so `BaseJvmBuffer.readByteArray`
 * takes the `ByteBuffer.get(byte[])` branch to copy. `HeapByteBuffer` has
 * `hasArray() == true` and uses `System.arraycopy`.
 *
 * Android takes the OTHER branch — `DirectByteBuffer` is `MemoryRef`-backed
 * with `hasArray() == true`. That's verified by source reading
 * (android.googlesource.com libcore) and would be pinned by an
 * `androidInstrumentedTest` variant on a future AVD pass; the test below
 * stays desktop-only.
 */
class DirectBufferBackingArrayCharacterizationTests {
    private val sizes = intArrayOf(8, 256, 16 * 1024, 1 * 1024 * 1024)

    @Test
    fun defaultFactoryProducesDirectBufferWithoutBackingArray() {
        for (size in sizes) {
            val buffer = BufferFactory.Default.allocate(size).unwrapFully() as BaseJvmBuffer
            val bb = buffer.byteBuffer
            assertTrue(
                bb.isDirect,
                "size=$size: Default factory should allocate a direct ByteBuffer on desktop JVM",
            )
            assertFalse(
                bb.hasArray(),
                "size=$size: desktop OpenJDK DirectByteBuffer.hasArray() should be false (hb is null)",
            )
        }
    }

    @Test
    fun managedFactoryProducesHeapBufferWithBackingArray() {
        for (size in sizes) {
            val buffer = BufferFactory.managed().allocate(size).unwrapFully() as BaseJvmBuffer
            val bb = buffer.byteBuffer
            assertFalse(
                bb.isDirect,
                "size=$size: managed() factory should allocate a HeapByteBuffer",
            )
            assertTrue(
                bb.hasArray(),
                "size=$size: HeapByteBuffer.hasArray() must be true",
            )
            assertEquals(
                size,
                bb.capacity(),
                "size=$size: HeapByteBuffer capacity should match requested size",
            )
        }
    }

    @Test
    fun deterministicFactoryProducesDirectBufferWithoutBackingArray() {
        if (!isDeterministicAllocateSupported) return
        for (size in sizes) {
            val buffer = BufferFactory.deterministic().allocate(size).unwrapFully() as BaseJvmBuffer
            val bb = buffer.byteBuffer
            assertTrue(
                bb.isDirect,
                "size=$size: deterministic() factory should allocate a direct ByteBuffer on desktop JVM",
            )
            assertFalse(
                bb.hasArray(),
                "size=$size: deterministic direct ByteBuffer.hasArray() should be false on desktop JVM",
            )
        }
    }
}
