package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Edge case tests for StreamProcessor.
 * Validates use-after-free fix (a992acf), byte order fix (013ecb1),
 * and general robustness.
 */
class StreamProcessorEdgeCaseTests {
    // ============================================================================
    // Read Zero Bytes
    // ============================================================================

    @Test
    fun readBufferZeroBytesReturnsEmptyBuffer() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = BufferFactory.managed().allocate(4)
        buffer.writeInt(42)
        buffer.resetForRead()
        processor.append(buffer)

        val result = processor.readBuffer(0)
        assertEquals(0, result.remaining())
        assertEquals(4, processor.available())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Read Across Chunk Boundary
    // ============================================================================

    @Test
    fun readBufferSpanningTwoChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Chunk 1: 4 bytes [0x01, 0x02, 0x03, 0x04]
        val chunk1 = BufferFactory.managed().allocate(4)
        chunk1.writeByte(0x01)
        chunk1.writeByte(0x02)
        chunk1.writeByte(0x03)
        chunk1.writeByte(0x04)
        chunk1.resetForRead()
        processor.append(chunk1)

        // Chunk 2: 4 bytes [0x05, 0x06, 0x07, 0x08]
        val chunk2 = BufferFactory.managed().allocate(4)
        chunk2.writeByte(0x05)
        chunk2.writeByte(0x06)
        chunk2.writeByte(0x07)
        chunk2.writeByte(0x08)
        chunk2.resetForRead()
        processor.append(chunk2)

        // Read 6 bytes spanning both chunks
        val result = processor.readBuffer(6)
        assertEquals(6, result.remaining())
        assertEquals(0x01.toByte(), result.readByte())
        assertEquals(0x02.toByte(), result.readByte())
        assertEquals(0x03.toByte(), result.readByte())
        assertEquals(0x04.toByte(), result.readByte())
        assertEquals(0x05.toByte(), result.readByte())
        assertEquals(0x06.toByte(), result.readByte())

        // 2 bytes should remain
        assertEquals(2, processor.available())
        assertEquals(0x07.toByte(), processor.readByte())
        assertEquals(0x08.toByte(), processor.readByte())

        processor.release()
        pool.clear()
    }

    @Test
    fun readBufferExactlyOneChunk() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val chunk = BufferFactory.managed().allocate(8)
        for (i in 0 until 8) chunk.writeByte((i + 1).toByte())
        chunk.resetForRead()
        processor.append(chunk)

        val result = processor.readBuffer(8)
        assertEquals(8, result.remaining())
        for (i in 1..8) {
            assertEquals(i.toByte(), result.readByte())
        }
        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Byte Order Preservation Through readBuffer
    // ============================================================================

    @Test
    fun readBufferPreservesBigEndianByteOrderAcrossChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Write two chunks with BIG_ENDIAN shorts
        val chunk1 = BufferFactory.Default.allocate(4, ByteOrder.BIG_ENDIAN)
        chunk1.writeShort(0x1234.toShort())
        chunk1.writeShort(0x5678.toShort())
        chunk1.resetForRead()
        processor.append(chunk1)

        val chunk2 = BufferFactory.Default.allocate(4, ByteOrder.BIG_ENDIAN)
        chunk2.writeShort(0x0ABC.toShort())
        chunk2.writeShort(0x0DEF.toShort())
        chunk2.resetForRead()
        processor.append(chunk2)

        // Read across chunk boundary — triggers merge/copy path
        val merged = merged(processor)
        assertEquals(ByteOrder.BIG_ENDIAN, merged.byteOrder, "Merged buffer must preserve BIG_ENDIAN byte order")
        assertEquals(0x1234.toShort(), merged.readShort(), "First short must be 0x1234 (big-endian)")
        assertEquals(0x5678.toShort(), merged.readShort(), "Second short must be 0x5678 (big-endian)")
        assertEquals(0x0ABC.toShort(), merged.readShort(), "Third short must be 0x0ABC (big-endian)")
        assertEquals(0x0DEF.toShort(), merged.readShort(), "Fourth short must be 0x0DEF (big-endian)")

        processor.release()
        pool.clear()
    }

    private fun merged(processor: StreamProcessor): ReadBuffer {
        // Force the multi-chunk merge path by reading all 8 bytes across 2 chunks
        return processor.readBuffer(8)
    }

    @Test
    fun poolAcquireUsesBigEndianByDefault() {
        val pool = BufferPool(defaultBufferSize = 16)
        val buf = pool.acquire(16)
        assertEquals(ByteOrder.BIG_ENDIAN, buf.byteOrder, "Pool buffers must default to BIG_ENDIAN")
        pool.release(buf)
        pool.clear()
    }

    // ============================================================================
    // Partially Consumed Buffer
    // ============================================================================

    @Test
    fun appendPartiallyConsumedBuffer() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Create a buffer, consume 2 bytes, then append the rest
        val buffer = BufferFactory.managed().allocate(6)
        for (i in 1..6) buffer.writeByte(i.toByte())
        buffer.resetForRead()
        buffer.readByte() // consume byte 1
        buffer.readByte() // consume byte 2

        processor.append(buffer)
        assertEquals(4, processor.available())
        assertEquals(3.toByte(), processor.readByte())
        assertEquals(4.toByte(), processor.readByte())
        assertEquals(5.toByte(), processor.readByte())
        assertEquals(6.toByte(), processor.readByte())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Byte Order Across Chunks
    // ============================================================================

    @Test
    fun intByteOrderPreservedAcrossChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Write int 0xAABBCCDD as BIG_ENDIAN across 4 single-byte chunks
        val bytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        for (b in bytes) {
            val buf = BufferFactory.managed().allocate(1)
            buf.writeByte(b)
            buf.resetForRead()
            processor.append(buf)
        }

        assertEquals(0xAABBCCDD.toInt(), processor.readInt())

        processor.release()
        pool.clear()
    }

    @Test
    fun shortByteOrderPreservedAcrossChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buf1 = BufferFactory.managed().allocate(1)
        buf1.writeByte(0x12)
        buf1.resetForRead()
        processor.append(buf1)

        val buf2 = BufferFactory.managed().allocate(1)
        buf2.writeByte(0x34)
        buf2.resetForRead()
        processor.append(buf2)

        assertEquals(0x1234.toShort(), processor.readShort())

        processor.release()
        pool.clear()
    }

    @Test
    fun longByteOrderPreservedAcrossMaxFragmentation() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // 8 single-byte chunks for one Long
        val bytes =
            byteArrayOf(
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06,
                0x07,
                0x08,
            )
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
    // Multiple Reads After Partial Chunk Consumption
    // ============================================================================

    @Test
    fun multipleReadsFromSingleChunk() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = BufferFactory.Default.allocate(12, byteOrder = ByteOrder.BIG_ENDIAN)
        buffer.writeInt(0x11111111)
        buffer.writeInt(0x22222222)
        buffer.writeInt(0x33333333)
        buffer.resetForRead()
        processor.append(buffer)

        assertEquals(0x11111111, processor.readInt())
        assertEquals(0x22222222, processor.readInt())
        assertEquals(0x33333333, processor.readInt())
        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // ReadBuffer After Append to Same Processor
    // ============================================================================

    @Test
    fun readBufferThenAppendMoreThenReadAgain() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Append first data
        val buf1 = BufferFactory.managed().allocate(4)
        buf1.writeInt(0x01020304)
        buf1.resetForRead()
        processor.append(buf1)

        // Read it
        val result1 = processor.readBuffer(4)
        assertEquals(4, result1.remaining())
        assertEquals(0, processor.available())

        // Append more data
        val buf2 = BufferFactory.managed().allocate(4)
        buf2.writeInt(0x05060708)
        buf2.resetForRead()
        processor.append(buf2)

        // Read again
        val result2 = processor.readBuffer(4)
        assertEquals(4, result2.remaining())
        assertEquals(0x05060708, result2.readInt())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Skip Across Chunk Boundary
    // ============================================================================

    @Test
    fun skipAcrossChunkBoundary() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buf1 = BufferFactory.managed().allocate(2)
        buf1.writeByte(0x01)
        buf1.writeByte(0x02)
        buf1.resetForRead()
        processor.append(buf1)

        val buf2 = BufferFactory.managed().allocate(2)
        buf2.writeByte(0x03)
        buf2.writeByte(0x04)
        buf2.resetForRead()
        processor.append(buf2)

        processor.skip(3)
        assertEquals(1, processor.available())
        assertEquals(0x04.toByte(), processor.readByte())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Peek Does Not Consume
    // ============================================================================

    @Test
    fun peekByteDoesNotAdvancePosition() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buf = BufferFactory.managed().allocate(4)
        buf.writeByte(0xAA.toByte())
        buf.writeByte(0xBB.toByte())
        buf.writeByte(0xCC.toByte())
        buf.writeByte(0xDD.toByte())
        buf.resetForRead()
        processor.append(buf)

        // Peek multiple times
        assertEquals(0xAA.toByte(), processor.peekByte(0))
        assertEquals(0xBB.toByte(), processor.peekByte(1))
        assertEquals(0xCC.toByte(), processor.peekByte(2))
        assertEquals(0xDD.toByte(), processor.peekByte(3))

        // Available should be unchanged
        assertEquals(4, processor.available())

        // Now consume and verify same data
        assertEquals(0xAA.toByte(), processor.readByte())
        assertEquals(0xBB.toByte(), processor.readByte())
        assertEquals(2, processor.available())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Large Cross-Chunk Read
    // ============================================================================

    @Test
    fun readBufferAcrossManyChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Append 100 bytes across 10 chunks of 10 bytes each
        for (chunkIdx in 0 until 10) {
            val chunk = BufferFactory.managed().allocate(10)
            for (i in 0 until 10) {
                chunk.writeByte(((chunkIdx * 10 + i) % 256).toByte())
            }
            chunk.resetForRead()
            processor.append(chunk)
        }

        assertEquals(100, processor.available())

        // Read all 100 bytes as a single readBuffer
        val result = processor.readBuffer(100)
        assertEquals(100, result.remaining())
        for (i in 0 until 100) {
            assertEquals((i % 256).toByte(), result.readByte(), "Mismatch at index $i")
        }

        assertEquals(0, processor.available())
        processor.release()
        pool.clear()
    }
}
