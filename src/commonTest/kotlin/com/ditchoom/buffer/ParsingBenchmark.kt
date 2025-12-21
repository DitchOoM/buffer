package com.ditchoom.buffer

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/**
 * Cross-platform benchmarks for real-world parsing scenarios.
 *
 * Tests operations needed for:
 * - HTTP header parsing (byte-by-byte scan for CRLF, string extraction)
 * - WebSocket frame parsing (multi-byte reads, masking)
 * - MQTT packet parsing (variable-length integers, string prefixes)
 * - Binary data parsing (bulk reads, multi-byte primitives)
 */
@State(Scope.Benchmark)
open class ParsingBenchmark {
    private lateinit var httpHeaderData: PlatformBuffer
    private lateinit var websocketFrameData: PlatformBuffer
    private lateinit var mqttPacketData: PlatformBuffer
    private lateinit var binaryImageData: PlatformBuffer

    @Setup
    fun setup() {
        // Simulate HTTP headers (byte-by-byte CRLF scanning)
        val httpHeaders = """
            HTTP/1.1 200 OK
            Content-Type: application/json
            Content-Length: 1024
            Connection: keep-alive
            Cache-Control: no-cache
            X-Custom-Header: some-value-here

        """.trimIndent().replace("\n", "\r\n").encodeToByteArray()
        httpHeaderData = PlatformBuffer.allocate(httpHeaders.size)
        httpHeaderData.writeBytes(httpHeaders)
        httpHeaderData.resetForRead()

        // Simulate WebSocket frame (multi-byte header + masked payload)
        val wsFrame = ByteArray(1024)
        wsFrame[0] = 0x81.toByte() // FIN + text opcode
        wsFrame[1] = 0xFE.toByte() // Masked + 126 length indicator
        wsFrame[2] = 0x03.toByte() // Length high byte (1000 - 6 = 994)
        wsFrame[3] = 0xE2.toByte() // Length low byte
        // Mask key
        wsFrame[4] = 0x12
        wsFrame[5] = 0x34
        wsFrame[6] = 0x56
        wsFrame[7] = 0x78
        // Fill payload
        for (i in 8 until 1024) {
            wsFrame[i] = ((i % 256) xor (wsFrame[4 + (i - 8) % 4].toInt())).toByte()
        }
        websocketFrameData = PlatformBuffer.allocate(wsFrame.size)
        websocketFrameData.writeBytes(wsFrame)
        websocketFrameData.resetForRead()

        // Simulate MQTT PUBLISH packet
        val mqttPacket = ByteArray(512)
        mqttPacket[0] = 0x30.toByte() // PUBLISH
        mqttPacket[1] = 0x82.toByte() // Remaining length byte 1 (variable int)
        mqttPacket[2] = 0x03.toByte() // Remaining length byte 2
        // Topic length (2 bytes) + topic + payload
        mqttPacket[3] = 0x00
        mqttPacket[4] = 0x0A // Topic length = 10
        for (i in 5 until 15) mqttPacket[i] = ('a'.code + (i - 5)).toByte()
        for (i in 15 until 512) mqttPacket[i] = (i % 256).toByte()
        mqttPacketData = PlatformBuffer.allocate(mqttPacket.size)
        mqttPacketData.writeBytes(mqttPacket)
        mqttPacketData.resetForRead()

        // Simulate binary image header (JPEG-like structure)
        val imageData = ByteArray(4096)
        // JPEG SOI marker
        imageData[0] = 0xFF.toByte()
        imageData[1] = 0xD8.toByte()
        // APP0 marker
        imageData[2] = 0xFF.toByte()
        imageData[3] = 0xE0.toByte()
        // Length (big endian)
        imageData[4] = 0x00
        imageData[5] = 0x10
        // JFIF identifier
        imageData[6] = 'J'.code.toByte()
        imageData[7] = 'F'.code.toByte()
        imageData[8] = 'I'.code.toByte()
        imageData[9] = 'F'.code.toByte()
        imageData[10] = 0x00
        // Fill rest with simulated image data
        for (i in 11 until 4096) imageData[i] = (i % 256).toByte()
        binaryImageData = PlatformBuffer.allocate(imageData.size)
        binaryImageData.writeBytes(imageData)
        binaryImageData.resetForRead()
    }

    // ========== HTTP Parsing Benchmarks ==========

    @Benchmark
    fun httpScanForCrLf(): Int {
        httpHeaderData.position(0)
        var lineCount = 0
        var prev: Byte = 0
        while (httpHeaderData.hasRemaining()) {
            val b = httpHeaderData.readByte()
            if (prev == 0x0D.toByte() && b == 0x0A.toByte()) {
                lineCount++
            }
            prev = b
        }
        return lineCount
    }

    @Benchmark
    fun httpReadHeaders(): Int {
        httpHeaderData.position(0)
        var bytesRead = 0
        // Simulate reading header lines
        while (httpHeaderData.hasRemaining()) {
            val b = httpHeaderData.readByte()
            bytesRead++
        }
        return bytesRead
    }

    // ========== WebSocket Parsing Benchmarks ==========

    @Benchmark
    fun websocketParseFrame(): Int {
        websocketFrameData.position(0)
        val byte1 = websocketFrameData.readByte().toInt() and 0xFF
        val byte2 = websocketFrameData.readByte().toInt() and 0xFF

        val fin = (byte1 and 0x80) != 0
        val opcode = byte1 and 0x0F
        val masked = (byte2 and 0x80) != 0
        var payloadLen = byte2 and 0x7F

        if (payloadLen == 126) {
            payloadLen = websocketFrameData.readShort().toInt() and 0xFFFF
        } else if (payloadLen == 127) {
            payloadLen = websocketFrameData.readLong().toInt()
        }

        return payloadLen
    }

    @Benchmark
    fun websocketUnmaskPayload(): Int {
        websocketFrameData.position(0)
        // Skip header
        websocketFrameData.readByte()
        websocketFrameData.readByte()
        val payloadLen = websocketFrameData.readShort().toInt() and 0xFFFF

        // Read mask
        val mask = ByteArray(4)
        mask[0] = websocketFrameData.readByte()
        mask[1] = websocketFrameData.readByte()
        mask[2] = websocketFrameData.readByte()
        mask[3] = websocketFrameData.readByte()

        // Unmask payload
        var sum = 0
        for (i in 0 until minOf(payloadLen, websocketFrameData.remaining())) {
            val masked = websocketFrameData.readByte()
            val unmasked = (masked.toInt() xor mask[i % 4].toInt()).toByte()
            sum += unmasked
        }
        return sum
    }

    // ========== MQTT Parsing Benchmarks ==========

    @Benchmark
    fun mqttReadVariableInt(): Int {
        mqttPacketData.position(1) // Skip packet type
        var value = 0
        var multiplier = 1
        var byte: Int
        do {
            byte = mqttPacketData.readByte().toInt() and 0xFF
            value += (byte and 0x7F) * multiplier
            multiplier *= 128
        } while ((byte and 0x80) != 0)
        return value
    }

    @Benchmark
    fun mqttReadPrefixedString(): String {
        mqttPacketData.position(3) // Skip to topic length
        val length = mqttPacketData.readShort().toInt() and 0xFFFF
        return mqttPacketData.readString(length, Charset.UTF8)
    }

    // ========== Binary Data Parsing Benchmarks ==========

    @Benchmark
    fun binaryReadMultiByteInts(): Long {
        binaryImageData.position(0)
        var sum = 0L
        // Read as many ints as possible
        while (binaryImageData.remaining() >= 4) {
            sum += binaryImageData.readInt()
        }
        return sum
    }

    @Benchmark
    fun binaryReadMultiByteLongs(): Long {
        binaryImageData.position(0)
        var sum = 0L
        while (binaryImageData.remaining() >= 8) {
            sum += binaryImageData.readLong()
        }
        return sum
    }

    @Benchmark
    fun binaryScanForMarker(): Int {
        binaryImageData.position(0)
        var markerCount = 0
        var prev: Byte = 0
        while (binaryImageData.hasRemaining()) {
            val b = binaryImageData.readByte()
            if (prev == 0xFF.toByte() && b != 0x00.toByte() && b != 0xFF.toByte()) {
                markerCount++
            }
            prev = b
        }
        return markerCount
    }

    @Benchmark
    fun binaryBulkRead(): Int {
        binaryImageData.position(0)
        binaryImageData.setLimit(binaryImageData.capacity)
        val chunk = binaryImageData.readByteArray(1024)
        return chunk.size
    }

    // ========== Allocation Benchmarks ==========

    @Benchmark
    fun allocateSmallBuffer(): Int {
        val buffer = PlatformBuffer.allocate(64)
        return buffer.capacity
    }

    @Benchmark
    fun allocateMediumBuffer(): Int {
        val buffer = PlatformBuffer.allocate(4096)
        return buffer.capacity
    }

    @Benchmark
    fun allocateLargeBuffer(): Int {
        val buffer = PlatformBuffer.allocate(65536)
        return buffer.capacity
    }

    // ========== Slice Benchmarks ==========

    @Benchmark
    fun sliceBuffer(): Int {
        binaryImageData.position(100)
        binaryImageData.setLimit(1000)
        val slice = binaryImageData.slice()
        binaryImageData.setLimit(binaryImageData.capacity)
        return slice.remaining()
    }
}
