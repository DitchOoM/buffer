package com.ditchoom.buffer

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

private fun formatSpeedup(value: Double): String {
    val rounded = (value * 100).roundToInt() / 100.0
    return rounded.toString()
}

/**
 * Performance comparison tests between UnsafeBuffer and PlatformBuffer.
 *
 * These tests measure relative performance and print results.
 * Run with: ./gradlew allTests --tests "*UnsafeBufferPerformanceTest*"
 */
class UnsafeBufferPerformanceTest {
    private val iterations = 10_000
    private val bufferSize = 1024

    @Test
    fun compareIntWritePerformance() {
        val intCount = bufferSize / 4

        // Warm up
        repeat(100) {
            withUnsafeBuffer(bufferSize) { buf ->
                repeat(intCount) { buf.writeInt(it) }
            }
            val platformBuf = PlatformBuffer.allocate(bufferSize)
            repeat(intCount) { platformBuf.writeInt(it) }
        }

        // Measure UnsafeBuffer
        val unsafeTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(bufferSize) { buf ->
                        repeat(intCount) { i -> buf.writeInt(i) }
                    }
                }
            }

        // Measure PlatformBuffer
        val platformTime =
            measureTime {
                repeat(iterations) {
                    val buf = PlatformBuffer.allocate(bufferSize)
                    repeat(intCount) { i -> buf.writeInt(i) }
                }
            }

        val speedup = platformTime.inWholeNanoseconds.toDouble() / unsafeTime.inWholeNanoseconds
        println("=== Int Write Performance ($iterations iterations, $intCount ints each) ===")
        println("UnsafeBuffer:   $unsafeTime")
        println("PlatformBuffer: $platformTime")
        println("Speedup: ${formatSpeedup(speedup)}x")
    }

    @Test
    fun compareIntReadPerformance() {
        val intCount = bufferSize / 4

        // Warm up
        repeat(100) {
            withUnsafeBuffer(bufferSize) { buf ->
                repeat(intCount) { buf.writeInt(it) }
                buf.resetForRead()
                repeat(intCount) { buf.readInt() }
            }
        }

        // Measure UnsafeBuffer
        var unsafeSum = 0L
        val unsafeTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(bufferSize) { buf ->
                        repeat(intCount) { i -> buf.writeInt(i) }
                        buf.resetForRead()
                        repeat(intCount) { unsafeSum += buf.readInt() }
                    }
                }
            }

        // Measure PlatformBuffer
        var platformSum = 0L
        val platformTime =
            measureTime {
                repeat(iterations) {
                    val buf = PlatformBuffer.allocate(bufferSize)
                    repeat(intCount) { i -> buf.writeInt(i) }
                    buf.resetForRead()
                    repeat(intCount) { platformSum += buf.readInt() }
                }
            }

        assertEquals(unsafeSum, platformSum, "Sums should match")

        val speedup = platformTime.inWholeNanoseconds.toDouble() / unsafeTime.inWholeNanoseconds
        println("=== Int Read Performance ($iterations iterations, $intCount ints each) ===")
        println("UnsafeBuffer:   $unsafeTime")
        println("PlatformBuffer: $platformTime")
        println("Speedup: ${formatSpeedup(speedup)}x")
    }

    @Test
    fun compareLongWritePerformance() {
        val longCount = bufferSize / 8

        // Warm up
        repeat(100) {
            withUnsafeBuffer(bufferSize) { buf ->
                repeat(longCount) { buf.writeLong(it.toLong()) }
            }
        }

        // Measure UnsafeBuffer
        val unsafeTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(bufferSize) { buf ->
                        repeat(longCount) { i -> buf.writeLong(i.toLong()) }
                    }
                }
            }

        // Measure PlatformBuffer
        val platformTime =
            measureTime {
                repeat(iterations) {
                    val buf = PlatformBuffer.allocate(bufferSize)
                    repeat(longCount) { i -> buf.writeLong(i.toLong()) }
                }
            }

        val speedup = platformTime.inWholeNanoseconds.toDouble() / unsafeTime.inWholeNanoseconds
        println("=== Long Write Performance ($iterations iterations, $longCount longs each) ===")
        println("UnsafeBuffer:   $unsafeTime")
        println("PlatformBuffer: $platformTime")
        println("Speedup: ${formatSpeedup(speedup)}x")
    }

    @Test
    fun compareByteArrayWritePerformance() {
        val testData = ByteArray(bufferSize) { it.toByte() }

        // Warm up
        repeat(100) {
            withUnsafeBuffer(bufferSize) { buf ->
                buf.writeBytes(testData)
            }
        }

        // Measure UnsafeBuffer
        val unsafeTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(bufferSize) { buf ->
                        buf.writeBytes(testData)
                    }
                }
            }

        // Measure PlatformBuffer
        val platformTime =
            measureTime {
                repeat(iterations) {
                    val buf = PlatformBuffer.allocate(bufferSize)
                    buf.writeBytes(testData)
                }
            }

        val speedup = platformTime.inWholeNanoseconds.toDouble() / unsafeTime.inWholeNanoseconds
        println("=== ByteArray Write Performance ($iterations iterations, $bufferSize bytes each) ===")
        println("UnsafeBuffer:   $unsafeTime")
        println("PlatformBuffer: $platformTime")
        println("Speedup: ${formatSpeedup(speedup)}x")
    }

    @Test
    fun compareByteArrayReadPerformance() {
        val testData = ByteArray(bufferSize) { it.toByte() }

        // Warm up
        repeat(100) {
            withUnsafeBuffer(bufferSize) { buf ->
                buf.writeBytes(testData)
                buf.resetForRead()
                buf.readByteArray(bufferSize)
            }
        }

        // Measure UnsafeBuffer
        val unsafeTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(bufferSize) { buf ->
                        buf.writeBytes(testData)
                        buf.resetForRead()
                        buf.readByteArray(bufferSize)
                    }
                }
            }

        // Measure PlatformBuffer
        val platformTime =
            measureTime {
                repeat(iterations) {
                    val buf = PlatformBuffer.allocate(bufferSize)
                    buf.writeBytes(testData)
                    buf.resetForRead()
                    buf.readByteArray(bufferSize)
                }
            }

        val speedup = platformTime.inWholeNanoseconds.toDouble() / unsafeTime.inWholeNanoseconds
        println("=== ByteArray Read Performance ($iterations iterations, $bufferSize bytes each) ===")
        println("UnsafeBuffer:   $unsafeTime")
        println("PlatformBuffer: $platformTime")
        println("Speedup: ${formatSpeedup(speedup)}x")
    }

    @Test
    fun compareMixedOperationsPerformance() {
        // Warm up
        repeat(100) {
            withUnsafeBuffer(bufferSize) { buf ->
                buf.writeInt(1)
                buf.writeLong(2L)
                buf.writeShort(3)
                buf.writeByte(4)
                buf.writeDouble(5.0)
                buf.writeFloat(6.0f)
            }
        }

        // Measure UnsafeBuffer
        val unsafeTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(bufferSize) { buf ->
                        repeat(10) {
                            buf.writeInt(1)
                            buf.writeLong(2L)
                            buf.writeShort(3)
                            buf.writeByte(4)
                            buf.writeDouble(5.0)
                            buf.writeFloat(6.0f)
                        }
                        buf.resetForRead()
                        repeat(10) {
                            buf.readInt()
                            buf.readLong()
                            buf.readShort()
                            buf.readByte()
                            buf.readDouble()
                            buf.readFloat()
                        }
                    }
                }
            }

        // Measure PlatformBuffer
        val platformTime =
            measureTime {
                repeat(iterations) {
                    val buf = PlatformBuffer.allocate(bufferSize)
                    repeat(10) {
                        buf.writeInt(1)
                        buf.writeLong(2L)
                        buf.writeShort(3)
                        buf.writeByte(4)
                        buf.writeDouble(5.0)
                        buf.writeFloat(6.0f)
                    }
                    buf.resetForRead()
                    repeat(10) {
                        buf.readInt()
                        buf.readLong()
                        buf.readShort()
                        buf.readByte()
                        buf.readDouble()
                        buf.readFloat()
                    }
                }
            }

        val speedup = platformTime.inWholeNanoseconds.toDouble() / unsafeTime.inWholeNanoseconds
        println("=== Mixed Operations Performance ($iterations iterations) ===")
        println("UnsafeBuffer:   $unsafeTime")
        println("PlatformBuffer: $platformTime")
        println("Speedup: ${formatSpeedup(speedup)}x")
    }

    @Test
    fun verifyUnsafeBufferCorrectness() {
        // Verify UnsafeBuffer produces correct results
        withUnsafeBuffer(100, ByteOrder.BIG_ENDIAN) { buf ->
            buf.writeInt(0x01020304)
            buf.writeLong(0x0102030405060708L)
            buf.writeShort(0x0102.toShort())
            buf.writeByte(0x42)
            buf.writeFloat(123.456f)
            buf.writeDouble(789.012)

            buf.resetForRead()

            assertEquals(0x01020304, buf.readInt(), "Int mismatch")
            assertEquals(0x0102030405060708L, buf.readLong(), "Long mismatch")
            assertEquals(0x0102.toShort(), buf.readShort(), "Short mismatch")
            assertEquals(0x42.toByte(), buf.readByte(), "Byte mismatch")
            assertEquals(123.456f, buf.readFloat(), 0.001f, "Float mismatch")
            assertEquals(789.012, buf.readDouble(), 0.001, "Double mismatch")
        }

        // Test little endian
        withUnsafeBuffer(100, ByteOrder.LITTLE_ENDIAN) { buf ->
            buf.writeInt(0x01020304)
            buf.resetForRead()
            assertEquals(0x04.toByte(), buf.readByte(), "LE byte 0")
            assertEquals(0x03.toByte(), buf.readByte(), "LE byte 1")
            assertEquals(0x02.toByte(), buf.readByte(), "LE byte 2")
            assertEquals(0x01.toByte(), buf.readByte(), "LE byte 3")
        }

        println("=== UnsafeBuffer Correctness Verified ===")
    }

    private fun assertEquals(
        expected: Float,
        actual: Float,
        delta: Float,
        message: String,
    ) {
        val diff = kotlin.math.abs(expected - actual)
        if (diff > delta) {
            throw AssertionError("$message: expected $expected but was $actual (diff: $diff > delta: $delta)")
        }
    }

    private fun assertEquals(
        expected: Double,
        actual: Double,
        delta: Double,
        message: String,
    ) {
        val diff = kotlin.math.abs(expected - actual)
        if (diff > delta) {
            throw AssertionError("$message: expected $expected but was $actual (diff: $diff > delta: $delta)")
        }
    }
}
