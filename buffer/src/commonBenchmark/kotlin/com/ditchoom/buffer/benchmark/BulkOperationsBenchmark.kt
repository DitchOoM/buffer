package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup

/**
 * Benchmarks for bulk buffer operations measuring SIMD optimization impact.
 *
 * Each operation has two variants on the SAME Direct buffer type:
 * - "Direct" = current implementation (SIMD C cinterop on native, optimized on JVM/JS)
 * - "Baseline" = old Kotlin-only implementation (Long-based reads via getLong/getInt)
 *
 * This gives a true apples-to-apples comparison on the same buffer type.
 * The baseline reimplements the old default ReadBuffer/WriteBuffer algorithms
 * that were used before the SIMD overrides were added.
 *
 * Run with: ./gradlew macosArm64BenchmarkBenchmark -Pbenchmark.filter=BulkOperations
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class BulkOperationsBenchmark {
    private val size64k = 64 * 1024

    private lateinit var directBuffer1: PlatformBuffer
    private lateinit var directBuffer2: PlatformBuffer
    private lateinit var heapBuffer1: PlatformBuffer
    private lateinit var heapBuffer2: PlatformBuffer
    private lateinit var intArray: IntArray
    private lateinit var shortArray: ShortArray

    @Setup
    fun setup() {
        directBuffer1 = PlatformBuffer.allocate(size64k, AllocationZone.Direct)
        directBuffer2 = PlatformBuffer.allocate(size64k, AllocationZone.Direct)
        heapBuffer1 = PlatformBuffer.allocate(size64k, AllocationZone.Heap)
        heapBuffer2 = PlatformBuffer.allocate(size64k, AllocationZone.Heap)

        // Fill buffers with test data
        for (i in 0 until size64k) {
            directBuffer1.writeByte(i.toByte())
            directBuffer2.writeByte(i.toByte())
            heapBuffer1.writeByte(i.toByte())
            heapBuffer2.writeByte(i.toByte())
        }
        directBuffer1.resetForRead()
        directBuffer2.resetForRead()
        heapBuffer1.resetForRead()
        heapBuffer2.resetForRead()

        intArray = IntArray(size64k / 4) { it }
        shortArray = ShortArray(size64k / 2) { it.toShort() }
    }

    // =========================================================================
    // XOR Mask
    // =========================================================================

    /**
     * Current: uses buffer.xorMask() (SIMD C on native, ByteBuffer on JVM, DataView on JS).
     */
    @Benchmark
    fun xorMask64kDirect(): Int {
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.xorMask(0x12345678)
        // XOR again to restore original data
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.xorMask(0x12345678)
        return directBuffer1.get(0).toInt()
    }

    /**
     * Baseline: old websocket approach using getLong/set (the approach before xorMask existed).
     * This is what the WebSocket library did before buffer.xorMask() was available.
     */
    @Benchmark
    fun xorMask64kBaseline(): Int {
        val mask = 0x12345678
        val maskLong = (mask.toLong() and 0xFFFFFFFFL) or ((mask.toLong() and 0xFFFFFFFFL) shl 32)
        val maskByte0 = (mask ushr 24).toByte()
        val maskByte1 = (mask ushr 16).toByte()
        val maskByte2 = (mask ushr 8).toByte()
        val maskByte3 = mask.toByte()

        // Apply XOR using Long reads/writes (old approach)
        var i = 0
        val longLimit = size64k - 7
        while (i < longLimit) {
            directBuffer1[i] = directBuffer1.getLong(i) xor maskLong
            i += 8
        }
        while (i < size64k) {
            val maskByte =
                when (i and 3) {
                    0 -> maskByte0
                    1 -> maskByte1
                    2 -> maskByte2
                    else -> maskByte3
                }
            directBuffer1[i] = (directBuffer1.get(i).toInt() xor maskByte.toInt()).toByte()
            i++
        }

        // Undo XOR to restore data (using same approach)
        i = 0
        while (i < longLimit) {
            directBuffer1[i] = directBuffer1.getLong(i) xor maskLong
            i += 8
        }
        while (i < size64k) {
            val maskByte =
                when (i and 3) {
                    0 -> maskByte0
                    1 -> maskByte1
                    2 -> maskByte2
                    else -> maskByte3
                }
            directBuffer1[i] = (directBuffer1.get(i).toInt() xor maskByte.toInt()).toByte()
            i++
        }

        return directBuffer1.get(0).toInt()
    }

    // =========================================================================
    // Content Equals
    // =========================================================================

    /**
     * Current: uses buffer.contentEquals() (SIMD on native, ByteBuffer.mismatch on JVM 11+).
     */
    @Benchmark
    fun contentEquals64kDirect(): Boolean {
        directBuffer1.position(0)
        directBuffer2.position(0)
        return directBuffer1.contentEquals(directBuffer2)
    }

    /**
     * Baseline: old Kotlin-only contentEquals using getLong() 8 bytes at a time.
     */
    @Benchmark
    fun contentEquals64kBaseline(): Boolean {
        val pos1 = 0
        val pos2 = 0
        val size = size64k

        // Long comparison (8 bytes at a time) - the old default implementation
        var i = 0
        val longLimit = size - 7
        while (i < longLimit) {
            if (directBuffer1.getLong(pos1 + i) != directBuffer2.getLong(pos2 + i)) {
                return false
            }
            i += 8
        }
        // Remaining bytes
        while (i < size) {
            if (directBuffer1.get(pos1 + i) != directBuffer2.get(pos2 + i)) {
                return false
            }
            i++
        }
        return true
    }

    // =========================================================================
    // Mismatch
    // =========================================================================

    /**
     * Current: uses buffer.mismatch() (SIMD on native, ByteBuffer.mismatch on JVM 11+).
     */
    @Benchmark
    fun mismatch64kDirect(): Int {
        directBuffer1.position(0)
        directBuffer2.position(0)
        return directBuffer1.mismatch(directBuffer2)
    }

    /**
     * Baseline: old Kotlin-only mismatch using getLong() 8 bytes at a time.
     */
    @Benchmark
    fun mismatch64kBaseline(): Int {
        val pos1 = 0
        val pos2 = 0
        val size = size64k

        var i = 0
        val longLimit = size - 7
        while (i < longLimit) {
            if (directBuffer1.getLong(pos1 + i) != directBuffer2.getLong(pos2 + i)) {
                // Find exact byte
                for (j in 0 until 8) {
                    if (directBuffer1.get(pos1 + i + j) != directBuffer2.get(pos2 + i + j)) {
                        return i + j
                    }
                }
            }
            i += 8
        }
        while (i < size) {
            if (directBuffer1.get(pos1 + i) != directBuffer2.get(pos2 + i)) {
                return i
            }
            i++
        }
        return -1
    }

    // =========================================================================
    // indexOf(Byte)
    // =========================================================================

    /**
     * Current: uses buffer.indexOf(byte) (memchr on native, array scan on JVM).
     */
    @Benchmark
    fun indexOfByte64kDirect(): Int {
        directBuffer1.position(0)
        return directBuffer1.indexOf(0xFF.toByte())
    }

    /**
     * Baseline: old Kotlin-only indexOf using XOR zero-detection with getLong().
     */
    @Benchmark
    fun indexOfByte64kBaseline(): Int {
        val byte = 0xFF.toByte()
        val pos = 0
        val size = size64k

        // Broadcast byte to Long for bulk detection
        val broadcastLong = (byte.toLong() and 0xFF) * 0x0101010101010101L

        var i = 0
        val longLimit = size - 7
        while (i < longLimit) {
            val xored = directBuffer1.getLong(pos + i) xor broadcastLong
            // Check if any byte is zero using the standard zero-byte detection trick
            if ((xored - 0x0101010101010101L) and xored.inv() and (0x8080808080808080UL.toLong()) != 0L) {
                // Found a match in this 8-byte chunk - find exact position
                for (j in 0 until 8) {
                    if (directBuffer1.get(pos + i + j) == byte) {
                        return i + j
                    }
                }
            }
            i += 8
        }
        // Remaining bytes
        while (i < size) {
            if (directBuffer1.get(pos + i) == byte) {
                return i
            }
            i++
        }
        return -1
    }

    // =========================================================================
    // indexOf(Int)
    // =========================================================================

    /**
     * Current: uses buffer.indexOf(int) (SIMD C on native, ByteBuffer on JVM).
     */
    @Benchmark
    fun indexOfInt64kDirect(): Int {
        directBuffer1.position(0)
        return directBuffer1.indexOf(0x7C7D7E7F)
    }

    /**
     * Current: aligned variant (SIMD on native).
     */
    @Benchmark
    fun indexOfInt64kDirectAligned(): Int {
        directBuffer1.position(0)
        return directBuffer1.indexOf(0x7C7D7E7F, aligned = true)
    }

    /**
     * Baseline: old Kotlin-only indexOf using getInt() at each byte position.
     */
    @Benchmark
    fun indexOfInt64kBaseline(): Int {
        val value = 0x7C7D7E7F
        val pos = 0
        val size = size64k
        val searchLimit = size - 3

        for (i in 0 until searchLimit) {
            if (directBuffer1.getInt(pos + i) == value) {
                return i
            }
        }
        return -1
    }

    // =========================================================================
    // indexOf(Long)
    // =========================================================================

    /**
     * Current: uses buffer.indexOf(long) (SIMD C on native, ByteBuffer on JVM).
     */
    @Benchmark
    fun indexOfLong64kDirect(): Int {
        directBuffer1.position(0)
        return directBuffer1.indexOf(0x78797A7B7C7D7E7FL)
    }

    /**
     * Current: aligned variant (SIMD on native).
     */
    @Benchmark
    fun indexOfLong64kDirectAligned(): Int {
        directBuffer1.position(0)
        return directBuffer1.indexOf(0x78797A7B7C7D7E7FL, aligned = true)
    }

    /**
     * Baseline: old Kotlin-only indexOf using getLong() at each byte position.
     */
    @Benchmark
    fun indexOfLong64kBaseline(): Int {
        val value = 0x78797A7B7C7D7E7FL
        val pos = 0
        val size = size64k
        val searchLimit = size - 7

        for (i in 0 until searchLimit) {
            if (directBuffer1.getLong(pos + i) == value) {
                return i
            }
        }
        return -1
    }

    // =========================================================================
    // Fill
    // =========================================================================

    /**
     * Current: uses buffer.fill() (memset-based on native, Arrays.fill on JVM heap).
     */
    @Benchmark
    fun fill64kDirect(): Int {
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.fill(0x42.toByte())
        return directBuffer1.get(0).toInt()
    }

    /**
     * Baseline: old Kotlin-only fill using writeLong() 8 bytes at a time.
     */
    @Benchmark
    fun fill64kBaseline(): Int {
        val value: Byte = 0x42
        val valueLong = (value.toLong() and 0xFF) * 0x0101010101010101L

        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)

        var i = 0
        val longLimit = size64k - 7
        while (i < longLimit) {
            directBuffer1[i] = valueLong
            i += 8
        }
        while (i < size64k) {
            directBuffer1[i] = value
            i++
        }
        return directBuffer1.get(0).toInt()
    }

    // =========================================================================
    // Write/Read Ints
    // =========================================================================

    @Benchmark
    fun writeInts64k(): Int {
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.writeInts(intArray)
        return directBuffer1.position()
    }

    @Benchmark
    fun readInts64k(): Int {
        directBuffer1.position(0)
        val result = directBuffer1.readInts(size64k / 4)
        return result[0]
    }

    // =========================================================================
    // Write/Read Shorts
    // =========================================================================

    @Benchmark
    fun writeShorts64k(): Int {
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.writeShorts(shortArray)
        return directBuffer1.position()
    }

    @Benchmark
    fun readShorts64k(): Int {
        directBuffer1.position(0)
        val result = directBuffer1.readShorts(size64k / 2)
        return result[0].toInt()
    }

    // =========================================================================
    // XOR Mask Copy
    // =========================================================================

    /**
     * Current: uses buffer.xorMaskCopy() on Direct buffers.
     */
    @Benchmark
    fun xorMaskCopy64kDirect(): Int {
        directBuffer2.position(0)
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.xorMaskCopy(directBuffer2, 0x12345678)
        return directBuffer1.get(0).toInt()
    }

    // =========================================================================
    // Buffer Copy
    // =========================================================================

    @Benchmark
    fun bufferCopy64k(): Int {
        directBuffer2.position(0)
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.write(directBuffer2)
        return directBuffer1.get(0).toInt()
    }

    // =========================================================================
    // Heap (ByteArrayBuffer) variants
    // =========================================================================

    @Benchmark
    fun xorMask64kHeap(): Int {
        heapBuffer1.position(0)
        heapBuffer1.setLimit(size64k)
        heapBuffer1.xorMask(0x12345678)
        heapBuffer1.position(0)
        heapBuffer1.setLimit(size64k)
        heapBuffer1.xorMask(0x12345678)
        return heapBuffer1.get(0).toInt()
    }

    @Benchmark
    fun xorMaskCopy64kHeap(): Int {
        heapBuffer2.position(0)
        heapBuffer1.position(0)
        heapBuffer1.setLimit(size64k)
        heapBuffer1.xorMaskCopy(heapBuffer2, 0x12345678)
        return heapBuffer1.get(0).toInt()
    }

    @Benchmark
    fun contentEquals64kHeap(): Boolean {
        heapBuffer1.position(0)
        heapBuffer2.position(0)
        return heapBuffer1.contentEquals(heapBuffer2)
    }

    @Benchmark
    fun indexOfByte64kHeap(): Int {
        heapBuffer1.position(0)
        return heapBuffer1.indexOf(0xFF.toByte())
    }
}
