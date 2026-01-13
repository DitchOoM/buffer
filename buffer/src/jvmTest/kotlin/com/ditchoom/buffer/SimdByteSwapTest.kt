package com.ditchoom.buffer

import sun.misc.Unsafe
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Explores SIMD optimizations for byte swapping operations.
 *
 * Key findings:
 * - Integer.reverseBytes() and Long.reverseBytes() are JVM intrinsics (single BSWAP instruction)
 * - Java Vector API (JDK 16+) can process multiple values in parallel
 * - VarHandle with byte order can provide portable byte swapping
 * - For bulk operations, SIMD can provide significant speedups
 */
class SimdByteSwapTest {
    companion object {
        private val unsafe: Unsafe =
            run {
                val field = Unsafe::class.java.getDeclaredField("theUnsafe")
                field.isAccessible = true
                field.get(null) as Unsafe
            }

        // VarHandle for reading/writing ints with explicit byte order
        private val intBEHandle: VarHandle =
            MethodHandles.byteArrayViewVarHandle(
                IntArray::class.java,
                ByteOrder.BIG_ENDIAN,
            )
        private val intLEHandle: VarHandle =
            MethodHandles.byteArrayViewVarHandle(
                IntArray::class.java,
                ByteOrder.LITTLE_ENDIAN,
            )
        private val longBEHandle: VarHandle =
            MethodHandles.byteArrayViewVarHandle(
                LongArray::class.java,
                ByteOrder.BIG_ENDIAN,
            )
        private val longLEHandle: VarHandle =
            MethodHandles.byteArrayViewVarHandle(
                LongArray::class.java,
                ByteOrder.LITTLE_ENDIAN,
            )
    }

    @Test
    fun `verify VarHandle byte swapping works correctly`() {
        val bytes = ByteArray(8)
        val testInt = 0x12345678
        val testLong = 0x123456789ABCDEF0L

        // Write as big-endian
        intBEHandle.set(bytes, 0, testInt)
        assertEquals(0x12, bytes[0].toInt() and 0xFF)
        assertEquals(0x34, bytes[1].toInt() and 0xFF)
        assertEquals(0x56, bytes[2].toInt() and 0xFF)
        assertEquals(0x78, bytes[3].toInt() and 0xFF)

        // Read back
        assertEquals(testInt, intBEHandle.get(bytes, 0) as Int)

        // Write as little-endian
        intLEHandle.set(bytes, 0, testInt)
        assertEquals(0x78, bytes[0].toInt() and 0xFF)
        assertEquals(0x56, bytes[1].toInt() and 0xFF)
        assertEquals(0x34, bytes[2].toInt() and 0xFF)
        assertEquals(0x12, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun `benchmark byte swap methods`() {
        val iterations = 10_000_000
        val size = 1024
        val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.BIG_ENDIAN)
        val byteArray = ByteArray(size)
        val address = unsafe.allocateMemory(size.toLong())

        // Warm up
        repeat(100_000) {
            val v = 0x12345678
            Integer.reverseBytes(v)
        }

        // 1. Individual Integer.reverseBytes (JVM intrinsic → BSWAP)
        val intrinsicStart = System.nanoTime()
        var sum1 = 0L
        repeat(iterations) { i ->
            val v = i
            sum1 += Integer.reverseBytes(v)
        }
        val intrinsicTime = System.nanoTime() - intrinsicStart

        // 2. VarHandle with byte order (portable, auto byte-swaps)
        val varHandleStart = System.nanoTime()
        var sum2 = 0L
        repeat(iterations) { i ->
            intBEHandle.set(byteArray, 0, i)
            sum2 += intBEHandle.get(byteArray, 0) as Int
        }
        val varHandleTime = System.nanoTime() - varHandleStart

        // 3. Unsafe with manual byte swap
        val unsafeSwapStart = System.nanoTime()
        var sum3 = 0L
        repeat(iterations) { i ->
            unsafe.putInt(address, Integer.reverseBytes(i))
            sum3 += Integer.reverseBytes(unsafe.getInt(address))
        }
        val unsafeSwapTime = System.nanoTime() - unsafeSwapStart

        // 4. Unsafe native (no byte swap) - baseline
        val unsafeNativeStart = System.nanoTime()
        var sum4 = 0L
        repeat(iterations) { i ->
            unsafe.putInt(address, i)
            sum4 += unsafe.getInt(address)
        }
        val unsafeNativeTime = System.nanoTime() - unsafeNativeStart

        // 5. ByteBuffer with BIG_ENDIAN
        val byteBufferStart = System.nanoTime()
        var sum5 = 0L
        repeat(iterations) { i ->
            buffer.putInt(0, i)
            sum5 += buffer.getInt(0)
        }
        val byteBufferTime = System.nanoTime() - byteBufferStart

        println("=== Individual Byte Swap Benchmark ($iterations iterations) ===")
        println("Integer.reverseBytes (intrinsic): ${intrinsicTime / 1_000_000}ms (${iterations * 1_000_000_000L / intrinsicTime} ops/s)")
        println("VarHandle with BE order:          ${varHandleTime / 1_000_000}ms (${iterations * 1_000_000_000L / varHandleTime} ops/s)")
        println("Unsafe + reverseBytes:            ${unsafeSwapTime / 1_000_000}ms (${iterations * 1_000_000_000L / unsafeSwapTime} ops/s)")
        println(
            "Unsafe native (no swap):          ${unsafeNativeTime / 1_000_000}ms (${iterations * 1_000_000_000L / unsafeNativeTime} ops/s)",
        )
        println("ByteBuffer BIG_ENDIAN:            ${byteBufferTime / 1_000_000}ms (${iterations * 1_000_000_000L / byteBufferTime} ops/s)")
        println()
        println("Intrinsic overhead: ${intrinsicTime.toDouble() / unsafeNativeTime}x (pure swap cost)")
        println("VarHandle vs Unsafe+swap: ${varHandleTime.toDouble() / unsafeSwapTime}x")

        // Cleanup
        unsafe.freeMemory(address)

        // Prevent dead code elimination
        assert(sum1 != 0L || sum2 != 0L || sum3 != 0L || sum4 != 0L || sum5 != 0L || true)
    }

    @Test
    fun `benchmark bulk byte swap operations`() {
        val iterations = 100_000
        val count = 256 // ints per iteration
        val size = count * 4
        val byteArray = ByteArray(size)
        val intArray = IntArray(count)
        val address = unsafe.allocateMemory(size.toLong())

        try {
            // Warm up
            repeat(1000) {
                for (i in 0 until count) {
                    intArray[i] = Integer.reverseBytes(i)
                }
            }

            // 1. Scalar reverseBytes in a loop
            val scalarStart = System.nanoTime()
            repeat(iterations) {
                for (i in 0 until count) {
                    val v = intArray[i]
                    intArray[i] = Integer.reverseBytes(v)
                }
            }
            val scalarTime = System.nanoTime() - scalarStart

            // 2. VarHandle bulk read/write with byte order
            val varHandleStart = System.nanoTime()
            repeat(iterations) {
                for (i in 0 until count) {
                    val offset = i * 4
                    intBEHandle.set(byteArray, offset, i)
                }
                for (i in 0 until count) {
                    val offset = i * 4
                    intArray[i] = intBEHandle.get(byteArray, offset) as Int
                }
            }
            val varHandleTime = System.nanoTime() - varHandleStart

            // 3. Unsafe bulk with byte swap
            val unsafeStart = System.nanoTime()
            repeat(iterations) {
                for (i in 0 until count) {
                    unsafe.putInt(address + i * 4L, Integer.reverseBytes(i))
                }
                for (i in 0 until count) {
                    intArray[i] = Integer.reverseBytes(unsafe.getInt(address + i * 4L))
                }
            }
            val unsafeTime = System.nanoTime() - unsafeStart

            // 4. Unrolled Unsafe (4x) with byte swap - helps with instruction-level parallelism
            val unrolledStart = System.nanoTime()
            repeat(iterations) {
                var i = 0
                while (i < count - 3) {
                    val addr = address + i * 4L
                    unsafe.putInt(addr, Integer.reverseBytes(i))
                    unsafe.putInt(addr + 4, Integer.reverseBytes(i + 1))
                    unsafe.putInt(addr + 8, Integer.reverseBytes(i + 2))
                    unsafe.putInt(addr + 12, Integer.reverseBytes(i + 3))
                    i += 4
                }
                while (i < count) {
                    unsafe.putInt(address + i * 4L, Integer.reverseBytes(i))
                    i++
                }

                i = 0
                while (i < count - 3) {
                    val addr = address + i * 4L
                    intArray[i] = Integer.reverseBytes(unsafe.getInt(addr))
                    intArray[i + 1] = Integer.reverseBytes(unsafe.getInt(addr + 4))
                    intArray[i + 2] = Integer.reverseBytes(unsafe.getInt(addr + 8))
                    intArray[i + 3] = Integer.reverseBytes(unsafe.getInt(addr + 12))
                    i += 4
                }
                while (i < count) {
                    intArray[i] = Integer.reverseBytes(unsafe.getInt(address + i * 4L))
                    i++
                }
            }
            val unrolledTime = System.nanoTime() - unrolledStart

            val totalOps = iterations.toLong() * count
            println("=== Bulk Byte Swap Benchmark ($iterations iterations, $count ints each) ===")
            println("Scalar reverseBytes loop:  ${scalarTime / 1_000_000}ms (${totalOps * 1_000_000_000L / scalarTime} ops/s)")
            println("VarHandle bulk:            ${varHandleTime / 1_000_000}ms (${totalOps * 1_000_000_000L / varHandleTime} ops/s)")
            println("Unsafe bulk + swap:        ${unsafeTime / 1_000_000}ms (${totalOps * 1_000_000_000L / unsafeTime} ops/s)")
            println("Unsafe unrolled 4x + swap: ${unrolledTime / 1_000_000}ms (${totalOps * 1_000_000_000L / unrolledTime} ops/s)")
            println()
            println("Unrolled vs scalar: ${scalarTime.toDouble() / unrolledTime}x speedup")
            println("VarHandle vs Unsafe: ${varHandleTime.toDouble() / unsafeTime}x")
        } finally {
            unsafe.freeMemory(address)
        }
    }

    @Test
    fun `benchmark Long byte swap`() {
        val iterations = 10_000_000
        val address = unsafe.allocateMemory(8)

        try {
            // Warm up
            repeat(100_000) {
                java.lang.Long.reverseBytes(it.toLong())
            }

            // 1. Long.reverseBytes intrinsic
            val intrinsicStart = System.nanoTime()
            var sum1 = 0L
            repeat(iterations) { i ->
                sum1 += java.lang.Long.reverseBytes(i.toLong())
            }
            val intrinsicTime = System.nanoTime() - intrinsicStart

            // 2. Unsafe with Long byte swap
            val unsafeSwapStart = System.nanoTime()
            var sum2 = 0L
            repeat(iterations) { i ->
                unsafe.putLong(address, java.lang.Long.reverseBytes(i.toLong()))
                sum2 += java.lang.Long.reverseBytes(unsafe.getLong(address))
            }
            val unsafeSwapTime = System.nanoTime() - unsafeSwapStart

            // 3. Unsafe native (no swap)
            val unsafeNativeStart = System.nanoTime()
            var sum3 = 0L
            repeat(iterations) { i ->
                unsafe.putLong(address, i.toLong())
                sum3 += unsafe.getLong(address)
            }
            val unsafeNativeTime = System.nanoTime() - unsafeNativeStart

            // 4. Manual byte swap via shifts (for comparison)
            val manualStart = System.nanoTime()
            var sum4 = 0L
            repeat(iterations) { i ->
                val v = i.toLong()
                val swapped =
                    ((v and 0xFFL) shl 56) or
                        ((v and 0xFF00L) shl 40) or
                        ((v and 0xFF0000L) shl 24) or
                        ((v and 0xFF000000L) shl 8) or
                        ((v ushr 8) and 0xFF000000L) or
                        ((v ushr 24) and 0xFF0000L) or
                        ((v ushr 40) and 0xFF00L) or
                        ((v ushr 56) and 0xFFL)
                sum4 += swapped
            }
            val manualTime = System.nanoTime() - manualStart

            println("=== Long Byte Swap Benchmark ($iterations iterations) ===")
            println("Long.reverseBytes (intrinsic): ${intrinsicTime / 1_000_000}ms (${iterations * 1_000_000_000L / intrinsicTime} ops/s)")
            println(
                "Unsafe + reverseBytes:         ${unsafeSwapTime / 1_000_000}ms (${iterations * 1_000_000_000L / unsafeSwapTime} ops/s)",
            )
            println(
                "Unsafe native (no swap):       ${unsafeNativeTime / 1_000_000}ms (${iterations * 1_000_000_000L / unsafeNativeTime} ops/s)",
            )
            println("Manual shift-based swap:       ${manualTime / 1_000_000}ms (${iterations * 1_000_000_000L / manualTime} ops/s)")
            println()
            println("Intrinsic vs manual: ${manualTime.toDouble() / intrinsicTime}x faster")
            println("Swap overhead vs native: ${unsafeSwapTime.toDouble() / unsafeNativeTime}x")

            assert(sum1 != 0L || sum2 != 0L || sum3 != 0L || sum4 != 0L || true)
        } finally {
            unsafe.freeMemory(address)
        }
    }

    @Test
    fun `explore SIMD-like patterns for bulk swap`() {
        // This test explores patterns that JIT can potentially vectorize
        val iterations = 50_000
        val count = 1024 // ints per iteration
        val intArray = IntArray(count)
        val resultArray = IntArray(count)

        // Initialize
        for (i in 0 until count) {
            intArray[i] = i * 0x01010101
        }

        // Warm up
        repeat(1000) {
            for (i in 0 until count) {
                resultArray[i] = Integer.reverseBytes(intArray[i])
            }
        }

        // 1. Simple loop (JIT may auto-vectorize)
        val simpleStart = System.nanoTime()
        repeat(iterations) {
            for (i in 0 until count) {
                resultArray[i] = Integer.reverseBytes(intArray[i])
            }
        }
        val simpleTime = System.nanoTime() - simpleStart

        // 2. Unrolled 8x (helps instruction pipelining)
        val unrolled8Start = System.nanoTime()
        repeat(iterations) {
            var i = 0
            while (i < count - 7) {
                resultArray[i] = Integer.reverseBytes(intArray[i])
                resultArray[i + 1] = Integer.reverseBytes(intArray[i + 1])
                resultArray[i + 2] = Integer.reverseBytes(intArray[i + 2])
                resultArray[i + 3] = Integer.reverseBytes(intArray[i + 3])
                resultArray[i + 4] = Integer.reverseBytes(intArray[i + 4])
                resultArray[i + 5] = Integer.reverseBytes(intArray[i + 5])
                resultArray[i + 6] = Integer.reverseBytes(intArray[i + 6])
                resultArray[i + 7] = Integer.reverseBytes(intArray[i + 7])
                i += 8
            }
            while (i < count) {
                resultArray[i] = Integer.reverseBytes(intArray[i])
                i++
            }
        }
        val unrolled8Time = System.nanoTime() - unrolled8Start

        // 3. Process as longs (swap pairs of ints together)
        val longArray = LongArray(count / 2)
        val resultLongArray = LongArray(count / 2)
        for (i in 0 until count / 2) {
            longArray[i] = (intArray[i * 2].toLong() shl 32) or (intArray[i * 2 + 1].toLong() and 0xFFFFFFFFL)
        }

        val longPairStart = System.nanoTime()
        repeat(iterations) {
            for (i in 0 until count / 2) {
                val v = longArray[i]
                // Swap bytes within each int, then swap the int positions
                val hi = Integer.reverseBytes((v ushr 32).toInt()).toLong() and 0xFFFFFFFFL
                val lo = Integer.reverseBytes(v.toInt()).toLong() and 0xFFFFFFFFL
                resultLongArray[i] = (lo shl 32) or hi
            }
        }
        val longPairTime = System.nanoTime() - longPairStart

        val totalOps = iterations.toLong() * count
        println("=== SIMD-like Bulk Swap Patterns ($iterations iterations, $count ints) ===")
        println("Simple loop:      ${simpleTime / 1_000_000}ms (${totalOps * 1_000_000_000L / simpleTime} ops/s)")
        println("Unrolled 8x:      ${unrolled8Time / 1_000_000}ms (${totalOps * 1_000_000_000L / unrolled8Time} ops/s)")
        println("Long pairs:       ${longPairTime / 1_000_000}ms (${totalOps * 1_000_000_000L / longPairTime} ops/s)")
        println()
        println("Unrolled vs simple: ${simpleTime.toDouble() / unrolled8Time}x")
        println("Long pairs vs simple: ${simpleTime.toDouble() / longPairTime}x")
    }
}
