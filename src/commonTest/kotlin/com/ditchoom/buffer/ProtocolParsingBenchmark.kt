package com.ditchoom.buffer

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

/**
 * Real-world benchmarks simulating binary protocol parsing.
 *
 * These tests compare UnsafeBuffer vs PlatformBuffer for realistic
 * protocol parsing scenarios like MQTT packet headers and TLV structures.
 *
 * Run with: ./gradlew allTests --tests "*ProtocolParsingBenchmark*"
 */
class ProtocolParsingBenchmark {
    // Lower iteration count for JS/WASM which are slower
    private val iterations = 1_000

    /**
     * Simulates parsing MQTT-style variable-length integer encoding.
     * This is a common pattern in binary protocols where integers are
     * encoded using 1-4 bytes with a continuation bit.
     */
    @Test
    fun compareVariableLengthIntParsing() {
        // Create test data with various variable-length integers
        val testData =
            ByteArray(1024) { i ->
                when (i % 4) {
                    0 -> 0x7F.toByte() // Single byte value (0-127)
                    1 -> 0x80.toByte() // Continuation byte
                    2 -> 0x01.toByte() // Second byte of multi-byte value
                    else -> 0x00.toByte()
                }
            }

        // Warm up
        repeat(50) {
            withUnsafeBuffer(1024) { buf ->
                buf.writeBytes(testData)
                buf.resetForRead()
                parseVariableLengthInts(buf, 100)
            }
            val platformBuf = PlatformBuffer.allocate(1024)
            platformBuf.writeBytes(testData)
            platformBuf.resetForRead()
            parseVariableLengthInts(platformBuf, 100)
        }

        // Measure UnsafeBuffer
        var unsafeSum = 0L
        val unsafeTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(1024) { buf ->
                        buf.writeBytes(testData)
                        buf.resetForRead()
                        unsafeSum += parseVariableLengthInts(buf, 100)
                    }
                }
            }

        // Measure PlatformBuffer
        var platformSum = 0L
        val platformTime =
            measureTime {
                repeat(iterations) {
                    val buf = PlatformBuffer.allocate(1024)
                    buf.writeBytes(testData)
                    buf.resetForRead()
                    platformSum += parseVariableLengthInts(buf, 100)
                }
            }

        assertEquals(unsafeSum, platformSum, "Parsed values should match")

        val speedup = platformTime.inWholeNanoseconds.toDouble() / unsafeTime.inWholeNanoseconds
        println("=== Variable-Length Integer Parsing ($iterations iterations) ===")
        println("UnsafeBuffer:   $unsafeTime")
        println("PlatformBuffer: $platformTime")
        println("Speedup: ${formatSpeedup(speedup)}x")
    }

    /**
     * Simulates parsing TLV (Type-Length-Value) structures common in
     * binary protocols like ASN.1, Protocol Buffers wire format, etc.
     */
    @Test
    fun compareTlvParsing() {
        // Create TLV test data: type(1) + length(2) + value(variable)
        val testData = ByteArray(1024)
        var offset = 0
        var tlvCount = 0
        while (offset + 10 < 1024) {
            testData[offset++] = (tlvCount % 256).toByte() // Type
            val valueLen = (tlvCount % 7) + 1 // Length 1-7
            testData[offset++] = 0.toByte() // Length high byte
            testData[offset++] = valueLen.toByte() // Length low byte
            repeat(valueLen) {
                testData[offset++] = it.toByte() // Value bytes
            }
            tlvCount++
        }

        // Warm up
        repeat(50) {
            withUnsafeBuffer(1024) { buf ->
                buf.writeBytes(testData)
                buf.resetForRead()
                parseTlvRecords(buf)
            }
        }

        // Measure UnsafeBuffer
        var unsafeTlvCount = 0
        val unsafeTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(1024) { buf ->
                        buf.writeBytes(testData)
                        buf.resetForRead()
                        unsafeTlvCount += parseTlvRecords(buf)
                    }
                }
            }

        // Measure PlatformBuffer
        var platformTlvCount = 0
        val platformTime =
            measureTime {
                repeat(iterations) {
                    val buf = PlatformBuffer.allocate(1024)
                    buf.writeBytes(testData)
                    buf.resetForRead()
                    platformTlvCount += parseTlvRecords(buf)
                }
            }

        assertEquals(unsafeTlvCount, platformTlvCount, "TLV counts should match")

        val speedup = platformTime.inWholeNanoseconds.toDouble() / unsafeTime.inWholeNanoseconds
        println("=== TLV Parsing ($iterations iterations) ===")
        println("UnsafeBuffer:   $unsafeTime")
        println("PlatformBuffer: $platformTime")
        println("Speedup: ${formatSpeedup(speedup)}x")
    }

    /**
     * Simulates parsing network packet headers with mixed field sizes.
     * Similar to parsing IP/TCP/UDP headers.
     */
    @Test
    fun comparePacketHeaderParsing() {
        val packetSize = 64 // Typical header size
        val packetsPerIteration = 100

        // Warm up
        repeat(50) {
            withUnsafeBuffer(packetSize * packetsPerIteration) { buf ->
                repeat(packetsPerIteration) {
                    writePacketHeader(buf, it)
                }
                buf.resetForRead()
                repeat(packetsPerIteration) {
                    parsePacketHeader(buf)
                }
            }
        }

        // Measure UnsafeBuffer
        var unsafeChecksum = 0L
        val unsafeTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(packetSize * packetsPerIteration) { buf ->
                        repeat(packetsPerIteration) { i ->
                            writePacketHeader(buf, i)
                        }
                        buf.resetForRead()
                        repeat(packetsPerIteration) {
                            unsafeChecksum += parsePacketHeader(buf)
                        }
                    }
                }
            }

        // Measure PlatformBuffer
        var platformChecksum = 0L
        val platformTime =
            measureTime {
                repeat(iterations) {
                    val buf = PlatformBuffer.allocate(packetSize * packetsPerIteration)
                    repeat(packetsPerIteration) { i ->
                        writePacketHeader(buf, i)
                    }
                    buf.resetForRead()
                    repeat(packetsPerIteration) {
                        platformChecksum += parsePacketHeader(buf)
                    }
                }
            }

        assertEquals(unsafeChecksum, platformChecksum, "Checksums should match")

        val speedup = platformTime.inWholeNanoseconds.toDouble() / unsafeTime.inWholeNanoseconds
        println("=== Packet Header Parsing ($iterations iterations, $packetsPerIteration packets each) ===")
        println("UnsafeBuffer:   $unsafeTime")
        println("PlatformBuffer: $platformTime")
        println("Speedup: ${formatSpeedup(speedup)}x")
    }

    /**
     * Simulates parsing a sequence of fixed-size records with mixed types.
     * Common in database file formats and log files.
     */
    @Test
    fun compareFixedRecordParsing() {
        val recordSize = 32 // timestamp(8) + id(4) + flags(2) + data(18)
        val recordsPerIteration = 100

        // Warm up
        repeat(50) {
            withUnsafeBuffer(recordSize * recordsPerIteration) { buf ->
                repeat(recordsPerIteration) { i ->
                    writeRecord(buf, i.toLong())
                }
                buf.resetForRead()
                repeat(recordsPerIteration) {
                    parseRecord(buf)
                }
            }
        }

        // Measure UnsafeBuffer
        var unsafeTotal = 0L
        val unsafeTime =
            measureTime {
                repeat(iterations) {
                    withUnsafeBuffer(recordSize * recordsPerIteration) { buf ->
                        repeat(recordsPerIteration) { i ->
                            writeRecord(buf, i.toLong())
                        }
                        buf.resetForRead()
                        repeat(recordsPerIteration) {
                            unsafeTotal += parseRecord(buf)
                        }
                    }
                }
            }

        // Measure PlatformBuffer
        var platformTotal = 0L
        val platformTime =
            measureTime {
                repeat(iterations) {
                    val buf = PlatformBuffer.allocate(recordSize * recordsPerIteration)
                    repeat(recordsPerIteration) { i ->
                        writeRecord(buf, i.toLong())
                    }
                    buf.resetForRead()
                    repeat(recordsPerIteration) {
                        platformTotal += parseRecord(buf)
                    }
                }
            }

        assertEquals(unsafeTotal, platformTotal, "Totals should match")

        val speedup = platformTime.inWholeNanoseconds.toDouble() / unsafeTime.inWholeNanoseconds
        println("=== Fixed Record Parsing ($iterations iterations, $recordsPerIteration records each) ===")
        println("UnsafeBuffer:   $unsafeTime")
        println("PlatformBuffer: $platformTime")
        println("Speedup: ${formatSpeedup(speedup)}x")
    }

    // Helper functions

    private fun parseVariableLengthInts(
        buf: ReadBuffer,
        count: Int,
    ): Long {
        var sum = 0L
        repeat(count) {
            var value = 0
            var shift = 0
            var byte: Int
            do {
                if (buf.remaining() == 0) return sum
                byte = buf.readByte().toInt() and 0xFF
                value = value or ((byte and 0x7F) shl shift)
                shift += 7
            } while ((byte and 0x80) != 0 && shift < 28)
            sum += value
        }
        return sum
    }

    private fun parseTlvRecords(buf: ReadBuffer): Int {
        var count = 0
        while (buf.remaining() >= 3) {
            val type = buf.readByte()
            val length = buf.readShort().toInt() and 0xFFFF
            if (buf.remaining() < length) break
            // Skip value bytes
            buf.position(buf.position() + length)
            count++
        }
        return count
    }

    private fun writePacketHeader(
        buf: WriteBuffer,
        seqNum: Int,
    ) {
        buf.writeByte(0x45) // Version + IHL
        buf.writeByte(0x00) // DSCP + ECN
        buf.writeShort(64) // Total length
        buf.writeShort(seqNum.toShort()) // Identification
        buf.writeShort(0x4000.toShort()) // Flags + Fragment offset
        buf.writeByte(64) // TTL
        buf.writeByte(6) // Protocol (TCP)
        buf.writeShort(0) // Header checksum (placeholder)
        buf.writeInt(0x0A000001) // Source IP
        buf.writeInt(0x0A000002) // Dest IP
        // Padding to 64 bytes
        repeat(44) { buf.writeByte(0) }
    }

    private fun parsePacketHeader(buf: ReadBuffer): Long {
        val version = buf.readByte()
        val dscp = buf.readByte()
        val length = buf.readShort()
        val id = buf.readShort()
        val flags = buf.readShort()
        val ttl = buf.readByte()
        val protocol = buf.readByte()
        val checksum = buf.readShort()
        val srcIp = buf.readInt()
        val dstIp = buf.readInt()
        // Skip padding
        buf.position(buf.position() + 44)
        return (srcIp.toLong() xor dstIp.toLong()) + id + length
    }

    private fun writeRecord(
        buf: WriteBuffer,
        timestamp: Long,
    ) {
        buf.writeLong(timestamp) // Timestamp
        buf.writeInt(timestamp.toInt()) // ID
        buf.writeShort(0x00FF.toShort()) // Flags
        // 18 bytes of data
        repeat(18) { buf.writeByte(it.toByte()) }
    }

    private fun parseRecord(buf: ReadBuffer): Long {
        val timestamp = buf.readLong()
        val id = buf.readInt()
        val flags = buf.readShort()
        // Read 18 bytes of data
        var dataSum = 0L
        repeat(18) {
            dataSum += buf.readByte()
        }
        return timestamp + id + flags + dataSum
    }

    private fun formatSpeedup(value: Double): String {
        val rounded = (value * 100).roundToInt() / 100.0
        return rounded.toString()
    }
}
