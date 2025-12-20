@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Compares WasmNativeUnsafeBuffer (Pointer-based) vs DefaultUnsafeBuffer (ByteArray-based)
 * to determine optimal strategy for WASM bulk operations.
 */
class WasmBufferComparisonTest {
    private val iterations = 5_000
    private val bufferSize = 1024

    @Test
    fun compareByteArrayWriteStrategies() {
        val testData = ByteArray(bufferSize) { it.toByte() }

        // Warm up
        repeat(50) {
            withUnsafeBuffer(bufferSize) { it.writeBytes(testData) }
            DefaultUnsafeBuffer.withBuffer(bufferSize) { it.writeBytes(testData) }
        }

        // WasmNativeUnsafeBuffer (Pointer-based with Long chunking)
        val pointerTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(bufferSize) { buf ->
                        buf.writeBytes(testData)
                    }
                }
            }

        // DefaultUnsafeBuffer (ByteArray-based with copyInto)
        val byteArrayTime =
            measureTime {
                repeat(iterations) {
                    DefaultUnsafeBuffer.withBuffer(bufferSize) { buf ->
                        buf.writeBytes(testData)
                    }
                }
            }

        val speedup = pointerTime.inWholeNanoseconds.toDouble() / byteArrayTime.inWholeNanoseconds
        println("=== WASM ByteArray Write Comparison ($iterations iters, $bufferSize bytes) ===")
        println("Pointer-based (WasmNativeUnsafeBuffer): $pointerTime")
        println("ByteArray-based (DefaultUnsafeBuffer):  $byteArrayTime")
        println("ByteArray speedup: ${formatSpeedup(speedup)}x")
    }

    @Test
    fun compareByteArrayReadStrategies() {
        val testData = ByteArray(bufferSize) { it.toByte() }

        // Warm up
        repeat(50) {
            withUnsafeBuffer(bufferSize) { buf ->
                buf.writeBytes(testData)
                buf.resetForRead()
                buf.readByteArray(bufferSize)
            }
            DefaultUnsafeBuffer.withBuffer(bufferSize) { buf ->
                buf.writeBytes(testData)
                buf.resetForRead()
                buf.readByteArray(bufferSize)
            }
        }

        // WasmNativeUnsafeBuffer
        val pointerTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(bufferSize) { buf ->
                        buf.writeBytes(testData)
                        buf.resetForRead()
                        buf.readByteArray(bufferSize)
                    }
                }
            }

        // DefaultUnsafeBuffer
        val byteArrayTime =
            measureTime {
                repeat(iterations) {
                    DefaultUnsafeBuffer.withBuffer(bufferSize) { buf ->
                        buf.writeBytes(testData)
                        buf.resetForRead()
                        buf.readByteArray(bufferSize)
                    }
                }
            }

        val speedup = pointerTime.inWholeNanoseconds.toDouble() / byteArrayTime.inWholeNanoseconds
        println("=== WASM ByteArray Read Comparison ($iterations iters, $bufferSize bytes) ===")
        println("Pointer-based (WasmNativeUnsafeBuffer): $pointerTime")
        println("ByteArray-based (DefaultUnsafeBuffer):  $byteArrayTime")
        println("ByteArray speedup: ${formatSpeedup(speedup)}x")
    }

    @Test
    fun compareIntWriteStrategies() {
        val intCount = bufferSize / 4

        // Warm up
        repeat(50) {
            withUnsafeBuffer(bufferSize) { buf ->
                repeat(intCount) { buf.writeInt(it) }
            }
            DefaultUnsafeBuffer.withBuffer(bufferSize) { buf ->
                repeat(intCount) { buf.writeInt(it) }
            }
        }

        // WasmNativeUnsafeBuffer
        val pointerTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(bufferSize) { buf ->
                        repeat(intCount) { i -> buf.writeInt(i) }
                    }
                }
            }

        // DefaultUnsafeBuffer
        val byteArrayTime =
            measureTime {
                repeat(iterations) {
                    DefaultUnsafeBuffer.withBuffer(bufferSize) { buf ->
                        repeat(intCount) { i -> buf.writeInt(i) }
                    }
                }
            }

        val speedup = pointerTime.inWholeNanoseconds.toDouble() / byteArrayTime.inWholeNanoseconds
        println("=== WASM Int Write Comparison ($iterations iters, $intCount ints) ===")
        println("Pointer-based (WasmNativeUnsafeBuffer): $pointerTime")
        println("ByteArray-based (DefaultUnsafeBuffer):  $byteArrayTime")
        println("Pointer speedup: ${formatSpeedup(1.0 / speedup)}x")
    }

    @Test
    fun compareMixedOperations() {
        // Warm up
        repeat(50) {
            withUnsafeBuffer(bufferSize) { buf ->
                buf.writeInt(1)
                buf.writeLong(2L)
                buf.writeShort(3)
                buf.writeByte(4)
            }
        }

        // WasmNativeUnsafeBuffer
        val pointerTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(bufferSize) { buf ->
                        repeat(10) {
                            buf.writeInt(1)
                            buf.writeLong(2L)
                            buf.writeShort(3)
                            buf.writeByte(4)
                        }
                        buf.resetForRead()
                        repeat(10) {
                            buf.readInt()
                            buf.readLong()
                            buf.readShort()
                            buf.readByte()
                        }
                    }
                }
            }

        // DefaultUnsafeBuffer
        val byteArrayTime =
            measureTime {
                repeat(iterations) {
                    DefaultUnsafeBuffer.withBuffer(bufferSize) { buf ->
                        repeat(10) {
                            buf.writeInt(1)
                            buf.writeLong(2L)
                            buf.writeShort(3)
                            buf.writeByte(4)
                        }
                        buf.resetForRead()
                        repeat(10) {
                            buf.readInt()
                            buf.readLong()
                            buf.readShort()
                            buf.readByte()
                        }
                    }
                }
            }

        val speedup = pointerTime.inWholeNanoseconds.toDouble() / byteArrayTime.inWholeNanoseconds
        println("=== WASM Mixed Ops Comparison ($iterations iters) ===")
        println("Pointer-based (WasmNativeUnsafeBuffer): $pointerTime")
        println("ByteArray-based (DefaultUnsafeBuffer):  $byteArrayTime")
        println("Pointer speedup: ${formatSpeedup(1.0 / speedup)}x")
    }

    private fun formatSpeedup(value: Double): String {
        val rounded = (value * 100).toInt() / 100.0
        return rounded.toString()
    }
}
