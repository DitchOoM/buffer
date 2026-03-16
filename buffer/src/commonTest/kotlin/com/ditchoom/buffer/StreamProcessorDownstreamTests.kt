package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.withPool
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * StreamProcessor tests covering downstream usage patterns from MQTT, WebSocket, and Socket.
 * These validate that the exact read/write patterns used in protocol implementations work correctly.
 */
class StreamProcessorDownstreamTests {
    // ============================================================================
    // MQTT Variable Byte Integer (VBI) Pattern
    // ============================================================================

    /**
     * MQTT uses variable-byte-integer encoding for remaining length.
     * Each byte uses 7 bits for value and 1 bit (MSB) as continuation flag.
     * This mimics BufferedControlPacketReader.readVariableByteInteger().
     */
    @Test
    fun mqttVariableByteIntegerSingleByte() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = BufferFactory.managed().allocate(1)
        buffer.writeByte(0x7F) // 127, no continuation
        buffer.resetForRead()
        processor.append(buffer)

        val result = readVariableByteInteger(processor)
        assertEquals(127, result)

        processor.release()
        pool.clear()
    }

    @Test
    fun mqttVariableByteIntegerTwoBytes() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Encode 128: first byte = 0x80 (0 + continuation), second byte = 0x01
        val buffer = BufferFactory.managed().allocate(2)
        buffer.writeByte(0x80.toByte())
        buffer.writeByte(0x01)
        buffer.resetForRead()
        processor.append(buffer)

        val result = readVariableByteInteger(processor)
        assertEquals(128, result)

        processor.release()
        pool.clear()
    }

    @Test
    fun mqttVariableByteIntegerFourBytes() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Encode 268435455 (max MQTT VBI): 0xFF 0xFF 0xFF 0x7F
        val buffer = BufferFactory.managed().allocate(4)
        buffer.writeByte(0xFF.toByte())
        buffer.writeByte(0xFF.toByte())
        buffer.writeByte(0xFF.toByte())
        buffer.writeByte(0x7F)
        buffer.resetForRead()
        processor.append(buffer)

        val result = readVariableByteInteger(processor)
        assertEquals(268435455, result)

        processor.release()
        pool.clear()
    }

    @Test
    fun mqttVariableByteIntegerFragmented() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // VBI encoding of 16383: 0xFF 0x7F, split across two chunks
        val buf1 = BufferFactory.managed().allocate(1)
        buf1.writeByte(0xFF.toByte())
        buf1.resetForRead()
        processor.append(buf1)

        val buf2 = BufferFactory.managed().allocate(1)
        buf2.writeByte(0x7F)
        buf2.resetForRead()
        processor.append(buf2)

        val result = readVariableByteInteger(processor)
        assertEquals(16383, result)

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // MQTT Length-Prefixed Packet Pattern
    // ============================================================================

    @Test
    fun mqttLengthPrefixedPacket() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Simulate MQTT PUBLISH: byte1 (0x30) + VBI length (5) + payload
        val buffer = BufferFactory.managed().allocate(7)
        buffer.writeByte(0x30) // PUBLISH fixed header
        buffer.writeByte(0x05) // remaining length = 5 (VBI single byte)
        buffer.writeByte(0x00) // topic length MSB
        buffer.writeByte(0x01) // topic length LSB = 1
        buffer.writeByte(0x41) // topic 'A'
        buffer.writeByte(0x48) // payload byte 1 'H'
        buffer.writeByte(0x49) // payload byte 2 'I'
        buffer.resetForRead()
        processor.append(buffer)

        // Parse like MQTT parser
        val byte1 = processor.readUnsignedByte()
        assertEquals(0x30, byte1)

        val remainingLength = readVariableByteInteger(processor)
        assertEquals(5, remainingLength)

        val payloadBuffer = processor.readBuffer(remainingLength)
        assertEquals(5, payloadBuffer.remaining())

        // Parse topic from payload
        val topicLength = payloadBuffer.readShort().toInt() and 0xFFFF
        assertEquals(1, topicLength)
        val topic = payloadBuffer.readString(topicLength, Charset.UTF8)
        assertEquals("A", topic)

        // Remaining is the payload
        assertEquals(2, payloadBuffer.remaining())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Fragmented Append (Network Simulation)
    // ============================================================================

    @Test
    fun fragmentedAppendSmallChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Append 1024 bytes in 8-byte chunks
        val totalBytes = 1024
        val chunkSize = 8
        for (chunkStart in 0 until totalBytes step chunkSize) {
            val chunk = BufferFactory.managed().allocate(chunkSize)
            for (i in 0 until chunkSize) {
                chunk.writeByte(((chunkStart + i) % 256).toByte())
            }
            chunk.resetForRead()
            processor.append(chunk)
        }

        assertEquals(totalBytes, processor.available())

        // Verify all data is correct
        for (i in 0 until totalBytes) {
            assertEquals((i % 256).toByte(), processor.readByte(), "Mismatch at index $i")
        }

        assertEquals(0, processor.available())
        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Byte Order Consistency (StreamProcessor fix validation)
    // ============================================================================

    @Test
    fun byteOrderConsistencyFastPath() {
        // Write ints in BIG_ENDIAN, read from StreamProcessor (default BIG_ENDIAN) - single chunk
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = BufferFactory.Default.allocate(16, byteOrder = ByteOrder.BIG_ENDIAN)
        buffer.writeInt(0x11223344)
        buffer.writeInt(0x55667788)
        buffer.writeInt(0xAABBCCDD.toInt())
        buffer.writeInt(0x01020304)
        buffer.resetForRead()
        processor.append(buffer)

        // Fast path: all data in single chunk
        assertEquals(0x11223344, processor.readInt())
        assertEquals(0x55667788, processor.readInt())
        assertEquals(0xAABBCCDD.toInt(), processor.readInt())
        assertEquals(0x01020304, processor.readInt())

        processor.release()
        pool.clear()
    }

    @Test
    fun byteOrderConsistencySlowPath() {
        // Write ints in BIG_ENDIAN, read spanning chunks (slow path)
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Split 0x11223344 across two chunks: [0x11, 0x22] and [0x33, 0x44]
        val buf1 = BufferFactory.Default.allocate(2, byteOrder = ByteOrder.BIG_ENDIAN)
        buf1.writeByte(0x11)
        buf1.writeByte(0x22)
        buf1.resetForRead()
        processor.append(buf1)

        val buf2 = BufferFactory.Default.allocate(2, byteOrder = ByteOrder.BIG_ENDIAN)
        buf2.writeByte(0x33)
        buf2.writeByte(0x44)
        buf2.resetForRead()
        processor.append(buf2)

        assertEquals(0x11223344, processor.readInt())

        processor.release()
        pool.clear()
    }

    @Test
    fun byteOrderConsistencyShortSlowPath() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Split short across chunks
        val buf1 = BufferFactory.Default.allocate(1)
        buf1.writeByte(0xAB.toByte())
        buf1.resetForRead()
        processor.append(buf1)

        val buf2 = BufferFactory.Default.allocate(1)
        buf2.writeByte(0xCD.toByte())
        buf2.resetForRead()
        processor.append(buf2)

        assertEquals(0xABCD.toShort(), processor.readShort())

        processor.release()
        pool.clear()
    }

    @Test
    fun byteOrderConsistencyLongSlowPath() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Split long across 8 single-byte chunks
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        for (b in bytes) {
            val buf = BufferFactory.managed().allocate(1)
            buf.writeByte(b)
            buf.resetForRead()
            processor.append(buf)
        }

        assertEquals(0x0102030405060708L, processor.readLong())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Mixed Peek and Read (WebSocket Frame Header Pattern)
    // ============================================================================

    @Test
    fun mixedPeekAndRead() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Simulate WebSocket frame: 2-byte header + 4-byte mask + payload
        val buffer = BufferFactory.managed().allocate(10)
        buffer.writeByte(0x81.toByte()) // FIN + TEXT opcode
        buffer.writeByte(0x84.toByte()) // MASK + length=4
        buffer.writeInt(0x12345678) // masking key
        buffer.writeByte(0x41) // masked 'A' ^ mask[0]
        buffer.writeByte(0x42) // masked 'B' ^ mask[1]
        buffer.writeByte(0x43) // masked 'C' ^ mask[2]
        buffer.writeByte(0x44) // masked 'D' ^ mask[3]
        buffer.resetForRead()
        processor.append(buffer)

        // Peek at first two bytes (WebSocket reads header first)
        val byte0 = processor.peekByte(0)
        val byte1 = processor.peekByte(1)
        assertEquals(0x81.toByte(), byte0)
        assertEquals(0x84.toByte(), byte1)

        // Peek at int (mask key at offset 2)
        val peekedMask = processor.peekInt()
        // peekInt at default offset 0 should give first 4 bytes
        assertEquals(0x81.toByte(), (peekedMask shr 24).toByte())

        // Now consume: skip header, read mask, read payload
        processor.skip(2)
        val maskKey = processor.readInt()
        assertEquals(0x12345678, maskKey)
        val payload = processor.readBuffer(4)
        assertEquals(4, payload.remaining())

        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // StreamProcessor Release
    // ============================================================================

    @Test
    fun releaseFreesPooledBuffers() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 10)
        val processor = StreamProcessor.create(pool)

        // Acquire pooled buffers and append
        for (i in 0 until 5) {
            val buf = pool.acquire(64)
            buf.writeInt(i)
            buf.resetForRead()
            processor.append(buf)
        }

        val statsBefore = pool.stats()

        // Release should return buffers to pool
        processor.release()

        // Pool should still function after release
        val buf = pool.acquire(64)
        assertTrue(buf.capacity >= 64)
        pool.release(buf)
        pool.clear()
    }

    // ============================================================================
    // AutoFilling StreamProcessor (MQTT/WebSocket auto-read pattern)
    // ============================================================================

    @Test
    fun autoFillingStreamProcessorBasicPattern() =
        runTest {
            withPool { pool ->
                // Simulate MQTT packet arriving in fragments
                val fragments =
                    listOf(
                        byteArrayOf(0x30, 0x05), // PUBLISH header + VBI
                        byteArrayOf(0x00, 0x01, 0x41), // topic length + topic 'A'
                        byteArrayOf(0x48, 0x49), // payload "HI"
                    )
                val remaining = fragments.map { makeReadBuffer(it) }.toMutableList()

                val processor =
                    StreamProcessor
                        .builder(pool)
                        .buildSuspendingWithAutoFill { stream ->
                            if (remaining.isEmpty()) throw EndOfStreamException()
                            stream.append(remaining.removeAt(0))
                        }

                try {
                    val byte1 = processor.readUnsignedByte()
                    assertEquals(0x30, byte1)

                    val vbi = processor.readByte().toInt() and 0x7F
                    assertEquals(5, vbi)

                    val payload = processor.readBuffer(5)
                    assertEquals(5, payload.remaining())
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun autoFillingExhaustsGracefully() =
        runTest {
            withPool { pool ->
                val chunks = listOf(makeReadBuffer(byteArrayOf(0x42))).toMutableList()

                val processor =
                    StreamProcessor
                        .builder(pool)
                        .buildSuspendingWithAutoFill { stream ->
                            if (chunks.isEmpty()) throw EndOfStreamException()
                            stream.append(chunks.removeAt(0))
                        }

                try {
                    assertEquals(0x42.toByte(), processor.readByte())
                    assertFailsWith<EndOfStreamException> {
                        processor.readByte()
                    }
                } finally {
                    processor.release()
                }
            }
        }

    // ============================================================================
    // Helpers
    // ============================================================================

    private fun readVariableByteInteger(processor: StreamProcessor): Int {
        var multiplier = 1
        var value = 0
        var encodedByte: Int
        do {
            encodedByte = processor.readUnsignedByte()
            value += (encodedByte and 0x7F) * multiplier
            multiplier *= 128
        } while (encodedByte and 0x80 != 0)
        return value
    }

    private fun makeReadBuffer(data: ByteArray): ReadBuffer {
        val buf = BufferFactory.managed().allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()
        return buf
    }
}
