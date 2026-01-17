package com.ditchoom.buffer

import sun.misc.Unsafe
import java.lang.reflect.Constructor
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test exploring fast DirectByteBuffer allocation using Unsafe.
 *
 * The goal is to get:
 * - Fast allocation (Unsafe.allocateMemory, no Cleaner overhead)
 * - Fast read/write (JVM-intrinsified ByteBuffer methods)
 * - Deterministic cleanup (manual Unsafe.freeMemory)
 */
class FastDirectByteBufferTest {
    companion object {
        private val unsafe: Unsafe =
            run {
                val field = Unsafe::class.java.getDeclaredField("theUnsafe")
                field.isAccessible = true
                field.get(null) as Unsafe
            }

        // DirectByteBuffer internal constructor varies by Java version
        private val directByteBufferConstructor: Constructor<*>?
        private val useIntCapacity: Boolean

        init {
            val clazz = Class.forName("java.nio.DirectByteBuffer")
            var constructor: Constructor<*>? = null
            var intCap = true

            // Try Java 8 signature: DirectByteBuffer(long addr, int cap)
            try {
                constructor = clazz.getDeclaredConstructor(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                constructor.isAccessible = true
                intCap = true
            } catch (e: NoSuchMethodException) {
                // Try Java 21 signature: DirectByteBuffer(long addr, long cap)
                try {
                    constructor = clazz.getDeclaredConstructor(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                    constructor.isAccessible = true
                    intCap = false
                } catch (e2: NoSuchMethodException) {
                    // Constructor not available on this JVM
                }
            }

            directByteBufferConstructor = constructor
            useIntCapacity = intCap
        }

        /**
         * Creates a DirectByteBuffer wrapping Unsafe-allocated memory.
         * The caller is responsible for freeing the memory.
         */
        fun createFastDirectByteBuffer(size: Int): Pair<ByteBuffer, Long>? {
            val constructor = directByteBufferConstructor ?: return null
            val address = unsafe.allocateMemory(size.toLong())
            // Zero-initialize (optional, for consistency with ByteBuffer.allocateDirect)
            unsafe.setMemory(address, size.toLong(), 0)
            val buffer =
                if (useIntCapacity) {
                    constructor.newInstance(address, size) as ByteBuffer
                } else {
                    constructor.newInstance(address, size.toLong()) as ByteBuffer
                }
            return buffer to address
        }

        fun freeDirectByteBuffer(address: Long) {
            unsafe.freeMemory(address)
        }
    }

    @Test
    fun `fast DirectByteBuffer creation works`() {
        val result = createFastDirectByteBuffer(1024) ?: return // Skip if not supported

        val (buffer, address) = result
        try {
            assertTrue(buffer.isDirect)
            assertEquals(1024, buffer.capacity())

            // Test write/read operations
            buffer.putInt(0x12345678)
            buffer.putLong(0x123456789ABCDEF0L)
            buffer.flip()

            assertEquals(0x12345678, buffer.getInt())
            assertEquals(0x123456789ABCDEF0L, buffer.getLong())
        } finally {
            freeDirectByteBuffer(address)
        }
    }

    @Test
    fun `allocation performance comparison`() {
        val iterations = 100_000
        val size = 1024

        // Check if fast DirectByteBuffer is supported
        val fastSupported =
            createFastDirectByteBuffer(16)?.let { (_, addr) ->
                freeDirectByteBuffer(addr)
                true
            } ?: false

        // Warm up
        repeat(1000) {
            ByteBuffer.allocateDirect(size)
            if (fastSupported) {
                createFastDirectByteBuffer(size)?.let { (_, addr) -> freeDirectByteBuffer(addr) }
            }
            val addr = unsafe.allocateMemory(size.toLong())
            unsafe.freeMemory(addr)
        }

        // Benchmark standard DirectByteBuffer allocation
        val standardStart = System.nanoTime()
        repeat(iterations) {
            ByteBuffer.allocateDirect(size)
        }
        val standardTime = System.nanoTime() - standardStart

        // Benchmark fast DirectByteBuffer allocation
        val fastTime =
            if (fastSupported) {
                val start = System.nanoTime()
                repeat(iterations) {
                    createFastDirectByteBuffer(size)?.let { (_, addr) -> freeDirectByteBuffer(addr) }
                }
                System.nanoTime() - start
            } else {
                0L
            }

        // Benchmark raw Unsafe allocation (current ScopedBuffer approach)
        val unsafeStart = System.nanoTime()
        repeat(iterations) {
            val addr = unsafe.allocateMemory(size.toLong())
            unsafe.freeMemory(addr)
        }
        val unsafeTime = System.nanoTime() - unsafeStart

        // Verify benchmarks completed (no assertions on timing, just completion)
        assertTrue(standardTime > 0)
        assertTrue(unsafeTime > 0)
        if (fastSupported) {
            assertTrue(fastTime > 0)
        }
    }

    @Test
    fun `read write performance comparison`() {
        val iterations = 1_000_000
        val size = 1024

        // Setup buffers
        val standardBuffer = ByteBuffer.allocateDirect(size)
        val fastResult = createFastDirectByteBuffer(size) ?: return // Skip if not supported

        val (fastBuffer, fastAddress) = fastResult
        val unsafeAddress = unsafe.allocateMemory(size.toLong())

        try {
            // Warm up
            repeat(10_000) {
                standardBuffer.clear()
                repeat(size / 4) { standardBuffer.putInt(it) }
                standardBuffer.flip()
                repeat(size / 4) { standardBuffer.getInt() }
            }

            // Benchmark standard DirectByteBuffer read/write
            val standardStart = System.nanoTime()
            repeat(iterations) {
                standardBuffer.clear()
                repeat(size / 4) { standardBuffer.putInt(it) }
                standardBuffer.flip()
                var sum = 0L
                repeat(size / 4) { sum += standardBuffer.getInt() }
            }
            val standardTime = System.nanoTime() - standardStart

            // Benchmark fast DirectByteBuffer read/write (should be same as standard)
            val fastStart = System.nanoTime()
            repeat(iterations) {
                fastBuffer.clear()
                repeat(size / 4) { fastBuffer.putInt(it) }
                fastBuffer.flip()
                var sum = 0L
                repeat(size / 4) { sum += fastBuffer.getInt() }
            }
            val fastTime = System.nanoTime() - fastStart

            // Benchmark raw Unsafe read/write
            val unsafeStart = System.nanoTime()
            repeat(iterations) {
                var offset = 0L
                repeat(size / 4) {
                    unsafe.putInt(unsafeAddress + offset, it)
                    offset += 4
                }
                offset = 0L
                var sum = 0L
                repeat(size / 4) {
                    sum += unsafe.getInt(unsafeAddress + offset)
                    offset += 4
                }
            }
            val unsafeTime = System.nanoTime() - unsafeStart

            // Verify benchmarks completed
            assertTrue(standardTime > 0)
            assertTrue(fastTime > 0)
            assertTrue(unsafeTime > 0)
        } finally {
            freeDirectByteBuffer(fastAddress)
            unsafe.freeMemory(unsafeAddress)
        }
    }

    @Test
    fun `byte order conversion performance`() {
        val iterations = 1_000_000
        val size = 1024
        val nativeIsLittleEndian = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN

        // Setup - Big Endian buffers (requires byte swapping on little-endian CPUs)
        val byteBufferBE = ByteBuffer.allocateDirect(size).order(java.nio.ByteOrder.BIG_ENDIAN)
        val byteBufferLE = ByteBuffer.allocateDirect(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val unsafeAddress = unsafe.allocateMemory(size.toLong())

        try {
            // Warm up
            repeat(10_000) {
                byteBufferBE.clear()
                repeat(size / 4) { byteBufferBE.putInt(it) }
            }

            // Benchmark ByteBuffer with BIG_ENDIAN (needs swapping on x86/ARM)
            val beBBStart = System.nanoTime()
            repeat(iterations) {
                byteBufferBE.clear()
                repeat(size / 4) { byteBufferBE.putInt(it) }
                byteBufferBE.flip()
                var sum = 0L
                repeat(size / 4) { sum += byteBufferBE.getInt() }
            }
            val beBBTime = System.nanoTime() - beBBStart

            // Benchmark ByteBuffer with LITTLE_ENDIAN (native on x86/ARM)
            val leBBStart = System.nanoTime()
            repeat(iterations) {
                byteBufferLE.clear()
                repeat(size / 4) { byteBufferLE.putInt(it) }
                byteBufferLE.flip()
                var sum = 0L
                repeat(size / 4) { sum += byteBufferLE.getInt() }
            }
            val leBBTime = System.nanoTime() - leBBStart

            // Benchmark Unsafe with manual byte swapping (simulating BIG_ENDIAN on LE CPU)
            val unsafeBEStart = System.nanoTime()
            repeat(iterations) {
                var offset = 0L
                repeat(size / 4) { i ->
                    val value = if (nativeIsLittleEndian) Integer.reverseBytes(i) else i
                    unsafe.putInt(unsafeAddress + offset, value)
                    offset += 4
                }
                offset = 0L
                var sum = 0L
                repeat(size / 4) {
                    val raw = unsafe.getInt(unsafeAddress + offset)
                    val value = if (nativeIsLittleEndian) Integer.reverseBytes(raw) else raw
                    sum += value
                    offset += 4
                }
            }
            val unsafeBETime = System.nanoTime() - unsafeBEStart

            // Benchmark Unsafe native endian (no swapping needed)
            val unsafeLEStart = System.nanoTime()
            repeat(iterations) {
                var offset = 0L
                repeat(size / 4) { i ->
                    unsafe.putInt(unsafeAddress + offset, i)
                    offset += 4
                }
                offset = 0L
                var sum = 0L
                repeat(size / 4) {
                    sum += unsafe.getInt(unsafeAddress + offset)
                    offset += 4
                }
            }
            val unsafeLETime = System.nanoTime() - unsafeLEStart

            // Verify benchmarks completed
            assertTrue(beBBTime > 0)
            assertTrue(leBBTime > 0)
            assertTrue(unsafeBETime > 0)
            assertTrue(unsafeLETime > 0)
        } finally {
            unsafe.freeMemory(unsafeAddress)
        }
    }
}
