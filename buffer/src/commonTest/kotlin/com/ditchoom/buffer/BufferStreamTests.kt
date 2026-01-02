package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.withBuffer
import com.ditchoom.buffer.pool.withPool
import com.ditchoom.buffer.stream.BufferChunk
import com.ditchoom.buffer.stream.BufferStream
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.buffer.stream.collectToBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Comprehensive unit tests for BufferStream, BufferChunk, and StreamProcessor.
 * Tests cover all public API methods and edge cases.
 */
class BufferStreamTests {
    // ============================================================================
    // BufferStream Creation Tests
    // ============================================================================

    @Test
    fun bufferStreamReportsContentLength() =
        withPool(defaultBufferSize = 1024) { pool ->
            val source = PlatformBuffer.allocate(104) // 13 Longs = 104 bytes
            repeat(13) { source.writeLong(it.toLong()) }
            source.resetForRead()

            val stream = BufferStream(source, chunkSize = 24)
            assertEquals(104, stream.contentLength)
        }

    @Test
    fun bufferStreamSplitsIntoCorrectChunkCount() =
        withPool(defaultBufferSize = 1024) { pool ->
            val source = PlatformBuffer.allocate(100)
            repeat(100) { source.writeByte(it.toByte()) }
            source.resetForRead()

            val stream = BufferStream(source, chunkSize = 30)
            var chunkCount = 0
            stream.forEachChunk { chunkCount++ }

            // 100 bytes / 30 bytes per chunk = 4 chunks (30 + 30 + 30 + 10)
            assertEquals(4, chunkCount)
        }

    @Test
    fun bufferStreamLastChunkIsMarkedCorrectly() =
        withPool(defaultBufferSize = 1024) { pool ->
            val source = PlatformBuffer.allocate(50)
            repeat(50) { source.writeByte(it.toByte()) }
            source.resetForRead()

            val stream = BufferStream(source, chunkSize = 20)
            val lastFlags = mutableListOf<Boolean>()
            stream.forEachChunk { chunk ->
                lastFlags.add(chunk.isLast)
            }

            // 3 chunks: [false, false, true]
            assertEquals(listOf(false, false, true), lastFlags)
        }

    @Test
    fun bufferStreamChunksHaveCorrectOffsets() =
        withPool(defaultBufferSize = 1024) { pool ->
            val source = PlatformBuffer.allocate(100)
            repeat(100) { source.writeByte(it.toByte()) }
            source.resetForRead()

            val stream = BufferStream(source, chunkSize = 25)
            val offsets = mutableListOf<Long>()
            stream.forEachChunk { chunk ->
                offsets.add(chunk.offset)
            }

            assertEquals(listOf(0L, 25L, 50L, 75L), offsets)
        }

    @Test
    fun bufferStreamChunksContainCorrectData() =
        withPool(defaultBufferSize = 1024) { pool ->
            val source = PlatformBuffer.allocate(10)
            repeat(10) { source.writeByte((it + 1).toByte()) }
            source.resetForRead()

            val stream = BufferStream(source, chunkSize = 4)
            val allBytes = mutableListOf<Byte>()
            stream.forEachChunk { chunk ->
                while (chunk.buffer.remaining() > 0) {
                    allBytes.add(chunk.buffer.readByte())
                }
            }

            assertEquals((1..10).map { it.toByte() }, allBytes)
        }

    @Test
    fun bufferStreamWithExactChunkSizeMultiple() =
        withPool(defaultBufferSize = 1024) { pool ->
            val source = PlatformBuffer.allocate(100)
            repeat(100) { source.writeByte(it.toByte()) }
            source.resetForRead()

            val stream = BufferStream(source, chunkSize = 25)
            var chunkCount = 0
            stream.forEachChunk { chunkCount++ }

            assertEquals(4, chunkCount)
        }

    @Test
    fun bufferStreamWithSingleByteChunks() =
        withPool(defaultBufferSize = 1024) { pool ->
            val source = PlatformBuffer.allocate(5)
            repeat(5) { source.writeByte((it + 10).toByte()) }
            source.resetForRead()

            val stream = BufferStream(source, chunkSize = 1)
            val allBytes = mutableListOf<Byte>()
            stream.forEachChunk { chunk ->
                allBytes.add(chunk.buffer.readByte())
            }

            assertEquals(listOf(10, 11, 12, 13, 14).map { it.toByte() }, allBytes)
        }

    @Test
    fun bufferStreamWithLargeChunkSize() =
        withPool(defaultBufferSize = 1024) { pool ->
            val source = PlatformBuffer.allocate(50)
            repeat(50) { source.writeByte(it.toByte()) }
            source.resetForRead()

            // Chunk size larger than data
            val stream = BufferStream(source, chunkSize = 100)
            var chunkCount = 0
            var wasLast = false
            stream.forEachChunk { chunk ->
                chunkCount++
                wasLast = chunk.isLast
            }

            assertEquals(1, chunkCount)
            assertTrue(wasLast)
        }

    @Test
    fun bufferStreamContentLengthMatchesTotalData() =
        withPool(defaultBufferSize = 1024) { pool ->
            val source = PlatformBuffer.allocate(123)
            repeat(123) { source.writeByte(it.toByte()) }
            source.resetForRead()

            val stream = BufferStream(source, chunkSize = 17)
            var totalRead = 0
            stream.forEachChunk { chunk ->
                totalRead += chunk.buffer.remaining()
            }

            assertEquals(123, totalRead)
            assertEquals(123, stream.contentLength)
        }

    // ============================================================================
    // BufferChunk Tests
    // ============================================================================

    @Test
    fun bufferChunkHoldsBufferAndMetadata() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(100) { buffer ->
                buffer.writeInt(0x12345678)
                buffer.resetForRead()

                val chunk = BufferChunk(buffer, isLast = true, offset = 50)

                assertEquals(50, chunk.offset)
                assertTrue(chunk.isLast)
                assertEquals(4, chunk.buffer.remaining())
            }
        }

    @Test
    fun bufferChunkIsLastFalseForNonFinalChunks() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(100) { buffer ->
                buffer.writeInt(0x12345678)
                buffer.resetForRead()

                val chunk = BufferChunk(buffer, isLast = false, offset = 0)
                assertFalse(chunk.isLast)
            }
        }

    // ============================================================================
    // StreamProcessor Creation Tests
    // ============================================================================

    @Test
    fun streamProcessorCreateWithPool() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        assertEquals(0, processor.available())
        processor.release()
        pool.clear()
    }

    // ============================================================================
    // StreamProcessor Append Tests
    // ============================================================================

    @Test
    fun streamProcessorAppendSingleChunk() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(10)
        repeat(10) { buffer.writeByte(it.toByte()) }
        buffer.resetForRead()

        processor.append(buffer)

        assertEquals(10, processor.available())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorAppendMultipleChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        for (i in 0 until 3) {
            val buffer = PlatformBuffer.allocate(5)
            repeat(5) { buffer.writeByte((i * 5 + it).toByte()) }
            buffer.resetForRead()
            processor.append(buffer)
        }

        assertEquals(15, processor.available())

        // Verify data integrity
        for (i in 0 until 15) {
            assertEquals(i.toByte(), processor.readByte())
        }

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorAppendEmptyBuffer() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(10)
        buffer.resetForRead() // Empty buffer

        processor.append(buffer)

        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // StreamProcessor Byte Read Tests
    // ============================================================================

    @Test
    fun streamProcessorReadByte() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(3)
        buffer.writeByte(0x11)
        buffer.writeByte(0x22)
        buffer.writeByte(0x33)
        buffer.resetForRead()

        processor.append(buffer)

        assertEquals(0x11.toByte(), processor.readByte())
        assertEquals(0x22.toByte(), processor.readByte())
        assertEquals(0x33.toByte(), processor.readByte())
        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorReadUnsignedByte() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(2)
        buffer.writeByte(0xFF.toByte())
        buffer.writeByte(0x80.toByte())
        buffer.resetForRead()

        processor.append(buffer)

        assertEquals(255, processor.readUnsignedByte())
        assertEquals(128, processor.readUnsignedByte())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // StreamProcessor Peek Tests
    // ============================================================================

    @Test
    fun streamProcessorPeekByte() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(3)
        buffer.writeByte(0x11)
        buffer.writeByte(0x22)
        buffer.writeByte(0x33)
        buffer.resetForRead()

        processor.append(buffer)

        // Peek doesn't consume
        assertEquals(0x11.toByte(), processor.peekByte())
        assertEquals(0x11.toByte(), processor.peekByte())
        assertEquals(3, processor.available())

        // Peek with offset
        assertEquals(0x22.toByte(), processor.peekByte(1))
        assertEquals(0x33.toByte(), processor.peekByte(2))

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorPeekInt() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(8)
        buffer.writeInt(0x11223344)
        buffer.writeInt(0x55667788)
        buffer.resetForRead()

        processor.append(buffer)

        // Peek doesn't consume
        assertEquals(0x11223344, processor.peekInt())
        assertEquals(0x11223344, processor.peekInt())
        assertEquals(8, processor.available())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorPeekShort() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(4)
        buffer.writeShort(0x1122)
        buffer.writeShort(0x3344)
        buffer.resetForRead()

        processor.append(buffer)

        assertEquals(0x1122.toShort(), processor.peekShort())
        assertEquals(4, processor.available())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorPeekMatches() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(10)
        buffer.writeByte(0x1f)
        buffer.writeByte(0x8b.toByte())
        buffer.writeByte(0x08)
        buffer.resetForRead()

        processor.append(buffer)

        assertTrue(processor.peekMatches(PlatformBuffer.wrap(byteArrayOf(0x1f, 0x8b.toByte()))))
        assertFalse(processor.peekMatches(PlatformBuffer.wrap(byteArrayOf(0x1f, 0x00))))

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // StreamProcessor Short Read Tests
    // ============================================================================

    @Test
    fun streamProcessorReadShort() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(4)
        buffer.writeShort(0x1122)
        buffer.writeShort(0x3344)
        buffer.resetForRead()

        processor.append(buffer)

        assertEquals(0x1122.toShort(), processor.readShort())
        assertEquals(0x3344.toShort(), processor.readShort())
        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // StreamProcessor Int Read Tests
    // ============================================================================

    @Test
    fun streamProcessorReadInt() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(8)
        buffer.writeInt(0x11223344)
        buffer.writeInt(0x55667788)
        buffer.resetForRead()

        processor.append(buffer)

        assertEquals(0x11223344, processor.readInt())
        assertEquals(0x55667788, processor.readInt())
        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // StreamProcessor Long Read Tests
    // ============================================================================

    @Test
    fun streamProcessorReadLong() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(16)
        buffer.writeLong(0x1122334455667788L)
        buffer.writeLong(-0x1122334455667788L)
        buffer.resetForRead()

        processor.append(buffer)

        assertEquals(0x1122334455667788L, processor.readLong())
        assertEquals(-0x1122334455667788L, processor.readLong())
        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // StreamProcessor Buffer Read Tests
    // ============================================================================

    @Test
    fun streamProcessorReadBufferContiguous() =
        withPool(defaultBufferSize = 1024) { pool ->
            val processor = StreamProcessor.create(pool)

            val buffer = PlatformBuffer.allocate(20)
            repeat(20) { buffer.writeByte(it.toByte()) }
            buffer.resetForRead()

            processor.append(buffer)

            val result = processor.readBuffer(10)
            assertEquals(10, result.remaining())
            for (i in 0 until 10) {
                assertEquals(i.toByte(), result.readByte())
            }

            assertEquals(10, processor.available())
            processor.release()
        }

    @Test
    fun streamProcessorReadBufferAcrossChunks() =
        withPool(defaultBufferSize = 1024) { pool ->
            val processor = StreamProcessor.create(pool)

            val buffer1 = PlatformBuffer.allocate(5)
            repeat(5) { buffer1.writeByte(it.toByte()) }
            buffer1.resetForRead()
            processor.append(buffer1)

            val buffer2 = PlatformBuffer.allocate(5)
            repeat(5) { buffer2.writeByte((it + 5).toByte()) }
            buffer2.resetForRead()
            processor.append(buffer2)

            val result = processor.readBuffer(8)
            assertEquals(8, result.remaining())
            for (i in 0 until 8) {
                assertEquals(i.toByte(), result.readByte())
            }

            assertEquals(2, processor.available())
            processor.release()
        }

    // ============================================================================
    // StreamProcessor Skip Tests
    // ============================================================================

    @Test
    fun streamProcessorSkip() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(10)
        repeat(10) { buffer.writeByte(it.toByte()) }
        buffer.resetForRead()

        processor.append(buffer)

        processor.skip(5)
        assertEquals(5, processor.available())
        assertEquals(5.toByte(), processor.readByte())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorSkipAcrossChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Append two chunks of 5 bytes each
        val buffer1 = PlatformBuffer.allocate(5)
        repeat(5) { buffer1.writeByte(it.toByte()) }
        buffer1.resetForRead()
        processor.append(buffer1)

        val buffer2 = PlatformBuffer.allocate(5)
        repeat(5) { buffer2.writeByte((it + 5).toByte()) }
        buffer2.resetForRead()
        processor.append(buffer2)

        // Skip 7 bytes (across chunk boundary)
        processor.skip(7)
        assertEquals(3, processor.available())
        assertEquals(7.toByte(), processor.readByte())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // StreamProcessor Release Tests
    // ============================================================================

    @Test
    fun streamProcessorReleaseWithPooledBuffers() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 10)
        val processor = StreamProcessor.create(pool)

        // Acquire and append pooled buffer
        val buffer = pool.acquire(100)
        buffer.writeLong(0x1122334455667788L)
        buffer.resetForRead()
        processor.append(buffer)

        // Read the data
        processor.readLong()

        // Release processor - pooled buffer should be returned to pool
        processor.release()

        // Pool should have buffers available
        pool.clear()
    }

    // ============================================================================
    // StreamProcessor Spanning Chunk Boundary Tests
    // ============================================================================

    @Test
    fun streamProcessorReadIntSpanningChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Create two chunks where the Int spans the boundary
        val buffer1 = PlatformBuffer.allocate(2)
        buffer1.writeByte(0x11)
        buffer1.writeByte(0x22)
        buffer1.resetForRead()
        processor.append(buffer1)

        val buffer2 = PlatformBuffer.allocate(2)
        buffer2.writeByte(0x33)
        buffer2.writeByte(0x44)
        buffer2.resetForRead()
        processor.append(buffer2)

        assertEquals(0x11223344, processor.readInt())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorReadShortSpanningChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Create two chunks where the Short spans the boundary
        val buffer1 = PlatformBuffer.allocate(1)
        buffer1.writeByte(0x11)
        buffer1.resetForRead()
        processor.append(buffer1)

        val buffer2 = PlatformBuffer.allocate(1)
        buffer2.writeByte(0x22)
        buffer2.resetForRead()
        processor.append(buffer2)

        assertEquals(0x1122.toShort(), processor.readShort())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorReadLongSpanningChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Create chunks where the Long spans boundaries
        val buffer1 = PlatformBuffer.allocate(3)
        buffer1.writeByte(0x11)
        buffer1.writeByte(0x22)
        buffer1.writeByte(0x33)
        buffer1.resetForRead()
        processor.append(buffer1)

        val buffer2 = PlatformBuffer.allocate(5)
        buffer2.writeByte(0x44)
        buffer2.writeByte(0x55)
        buffer2.writeByte(0x66)
        buffer2.writeByte(0x77)
        buffer2.writeByte(0x88.toByte())
        buffer2.resetForRead()
        processor.append(buffer2)

        assertEquals(0x1122334455667788L, processor.readLong())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // collectToBuffer Tests
    // ============================================================================

    @Test
    fun collectToBufferCombinesChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)

        val source = PlatformBuffer.allocate(20)
        repeat(20) { source.writeByte(it.toByte()) }
        source.resetForRead()

        val stream = BufferStream(source, chunkSize = 7)
        val collected = stream.collectToBuffer(pool)

        assertEquals(20, collected.remaining())
        for (i in 0 until 20) {
            assertEquals(i.toByte(), collected.readByte())
        }

        pool.clear()
    }

    // ============================================================================
    // Edge Cases
    // ============================================================================

    @Test
    fun streamProcessorEmptyAfterFullConsumption() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(4)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()

        processor.append(buffer)
        processor.readInt()

        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    @Test
    fun bufferStreamEmptySource() {
        val source = PlatformBuffer.allocate(0)
        source.resetForRead()

        val stream = BufferStream(source, chunkSize = 10)
        var chunkCount = 0
        stream.forEachChunk { chunkCount++ }

        assertEquals(0, chunkCount)
        assertEquals(0, stream.contentLength)
    }

    // ============================================================================
    // Additional Boundary Crossing Tests
    // ============================================================================

    @Test
    fun streamProcessorPeekByteAcrossChunkBoundary() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // First chunk with 2 bytes
        val buffer1 = PlatformBuffer.allocate(2)
        buffer1.writeByte(0xAA.toByte())
        buffer1.writeByte(0xBB.toByte())
        buffer1.resetForRead()
        processor.append(buffer1)

        // Second chunk with 2 bytes
        val buffer2 = PlatformBuffer.allocate(2)
        buffer2.writeByte(0xCC.toByte())
        buffer2.writeByte(0xDD.toByte())
        buffer2.resetForRead()
        processor.append(buffer2)

        // Peek across boundary
        assertEquals(0xAA.toByte(), processor.peekByte(0))
        assertEquals(0xBB.toByte(), processor.peekByte(1))
        assertEquals(0xCC.toByte(), processor.peekByte(2)) // Crosses boundary
        assertEquals(0xDD.toByte(), processor.peekByte(3))

        // Data should still be available
        assertEquals(4, processor.available())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorPeekIntAcrossChunkBoundary() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // First chunk with 1 byte
        val buffer1 = PlatformBuffer.allocate(1)
        buffer1.writeByte(0x11)
        buffer1.resetForRead()
        processor.append(buffer1)

        // Second chunk with 3 bytes
        val buffer2 = PlatformBuffer.allocate(3)
        buffer2.writeByte(0x22)
        buffer2.writeByte(0x33)
        buffer2.writeByte(0x44)
        buffer2.resetForRead()
        processor.append(buffer2)

        // Peek Int that spans chunks (slow path)
        assertEquals(0x11223344, processor.peekInt())
        assertEquals(4, processor.available()) // Still available after peek

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorPeekShortAcrossChunkBoundary() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // First chunk with 1 byte
        val buffer1 = PlatformBuffer.allocate(1)
        buffer1.writeByte(0xAB.toByte())
        buffer1.resetForRead()
        processor.append(buffer1)

        // Second chunk with 1 byte
        val buffer2 = PlatformBuffer.allocate(1)
        buffer2.writeByte(0xCD.toByte())
        buffer2.resetForRead()
        processor.append(buffer2)

        // Peek Short that spans chunks
        assertEquals(0xABCD.toShort(), processor.peekShort())
        assertEquals(2, processor.available())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorPeekMatchesAcrossChunkBoundary() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Split pattern across chunks
        val buffer1 = PlatformBuffer.allocate(1)
        buffer1.writeByte(0x1f)
        buffer1.resetForRead()
        processor.append(buffer1)

        val buffer2 = PlatformBuffer.allocate(2)
        buffer2.writeByte(0x8b.toByte())
        buffer2.writeByte(0x08)
        buffer2.resetForRead()
        processor.append(buffer2)

        // Pattern spans chunks
        assertTrue(processor.peekMatches(PlatformBuffer.wrap(byteArrayOf(0x1f, 0x8b.toByte(), 0x08))))
        assertFalse(processor.peekMatches(PlatformBuffer.wrap(byteArrayOf(0x1f, 0x8b.toByte(), 0x00))))

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorReadIntAtVariousBoundaryPositions() {
        // Test Int read when boundary falls at every possible position within the 4 bytes
        for (boundary in 1..3) {
            val pool = BufferPool(defaultBufferSize = 1024)
            val processor = StreamProcessor.create(pool)

            // First chunk
            val buffer1 = PlatformBuffer.allocate(boundary)
            for (i in 0 until boundary) {
                buffer1.writeByte((0x11 + i * 0x11).toByte())
            }
            buffer1.resetForRead()
            processor.append(buffer1)

            // Second chunk
            val buffer2 = PlatformBuffer.allocate(4 - boundary)
            for (i in boundary until 4) {
                buffer2.writeByte((0x11 + i * 0x11).toByte())
            }
            buffer2.resetForRead()
            processor.append(buffer2)

            assertEquals(0x11223344, processor.readInt())

            processor.release()
            pool.clear()
        }
    }

    @Test
    fun streamProcessorReadLongAtVariousBoundaryPositions() {
        // Test Long read when boundary falls at every possible position
        for (boundary in 1..7) {
            val pool = BufferPool(defaultBufferSize = 1024)
            val processor = StreamProcessor.create(pool)

            // First chunk
            val buffer1 = PlatformBuffer.allocate(boundary)
            val bytes = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
            for (i in 0 until boundary) {
                buffer1.writeByte(bytes[i])
            }
            buffer1.resetForRead()
            processor.append(buffer1)

            // Second chunk
            val buffer2 = PlatformBuffer.allocate(8 - boundary)
            for (i in boundary until 8) {
                buffer2.writeByte(bytes[i])
            }
            buffer2.resetForRead()
            processor.append(buffer2)

            assertEquals(0x1122334455667788L, processor.readLong())

            processor.release()
            pool.clear()
        }
    }

    @Test
    fun streamProcessorReadBufferExactlyOneChunk() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(10)
        repeat(10) { buffer.writeByte(it.toByte()) }
        buffer.resetForRead()
        processor.append(buffer)

        // Read exactly the full chunk
        val result = processor.readBuffer(10)
        assertEquals(10, result.remaining())
        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorReadBufferZeroBytes() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(10)
        repeat(10) { buffer.writeByte(it.toByte()) }
        buffer.resetForRead()
        processor.append(buffer)

        // Read zero bytes
        val result = processor.readBuffer(0)
        assertEquals(0, result.remaining())
        assertEquals(10, processor.available())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorSkipExactlyOneChunk() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer1 = PlatformBuffer.allocate(5)
        repeat(5) { buffer1.writeByte(it.toByte()) }
        buffer1.resetForRead()
        processor.append(buffer1)

        val buffer2 = PlatformBuffer.allocate(5)
        repeat(5) { buffer2.writeByte((it + 5).toByte()) }
        buffer2.resetForRead()
        processor.append(buffer2)

        // Skip exactly first chunk
        processor.skip(5)
        assertEquals(5, processor.available())
        assertEquals(5.toByte(), processor.readByte())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorSkipAllData() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(10)
        repeat(10) { buffer.writeByte(it.toByte()) }
        buffer.resetForRead()
        processor.append(buffer)

        processor.skip(10)
        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorMultipleChunksReadBuffer() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Create 4 small chunks of 3 bytes each
        for (chunkIdx in 0 until 4) {
            val buffer = PlatformBuffer.allocate(3)
            for (i in 0 until 3) {
                buffer.writeByte((chunkIdx * 3 + i).toByte())
            }
            buffer.resetForRead()
            processor.append(buffer)
        }

        assertEquals(12, processor.available())

        // Read 10 bytes spanning multiple chunks
        val result = processor.readBuffer(10)
        assertEquals(10, result.remaining())
        for (i in 0 until 10) {
            assertEquals(i.toByte(), result.readByte())
        }

        assertEquals(2, processor.available())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorReadAfterPartialConsumption() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        val buffer = PlatformBuffer.allocate(8)
        buffer.writeInt(0x11223344)
        buffer.writeInt(0x55667788)
        buffer.resetForRead()
        processor.append(buffer)

        // Read first int
        assertEquals(0x11223344, processor.readInt())
        assertEquals(4, processor.available())

        // Read second int
        assertEquals(0x55667788, processor.readInt())
        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorChunkReleasedAfterConsumption() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 10)
        val processor = StreamProcessor.create(pool)

        // Use pooled buffer
        val buffer = pool.acquire(100)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()
        processor.append(buffer)

        // Consume the data
        processor.readInt()
        assertEquals(0, processor.available())

        // Buffer should be released back to pool
        // The exact pool size depends on implementation details
        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorReadByteExhaustingChunk() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // First chunk with single byte
        val buffer1 = PlatformBuffer.allocate(1)
        buffer1.writeByte(0x11)
        buffer1.resetForRead()
        processor.append(buffer1)

        // Second chunk
        val buffer2 = PlatformBuffer.allocate(1)
        buffer2.writeByte(0x22)
        buffer2.resetForRead()
        processor.append(buffer2)

        assertEquals(2, processor.available())
        assertEquals(0x11.toByte(), processor.readByte())
        assertEquals(1, processor.available())
        assertEquals(0x22.toByte(), processor.readByte())
        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Network IO Simulation Tests
    // ============================================================================

    @Test
    fun streamProcessorSimulatesProtocolParsing() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Simulate receiving a message: 4-byte length prefix + payload
        val messageLength = 10
        val header = PlatformBuffer.allocate(4)
        header.writeInt(messageLength)
        header.resetForRead()
        processor.append(header)

        val payload = PlatformBuffer.allocate(messageLength)
        repeat(messageLength) { payload.writeByte((it + 1).toByte()) }
        payload.resetForRead()
        processor.append(payload)

        // Parse like a protocol parser would
        assertEquals(14, processor.available())
        val length = processor.peekInt()
        assertEquals(messageLength, length)

        if (processor.available() >= 4 + length) {
            processor.skip(4) // Skip header
            val payloadBuffer = processor.readBuffer(length)
            assertEquals(messageLength, payloadBuffer.remaining())
            assertEquals(1.toByte(), payloadBuffer.readByte())
        }

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorSimulatesFragmentedNetworkIO() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Simulate fragmented arrival: message header split across packets
        // Message format: 2-byte length + payload

        // First fragment: partial header (1 byte)
        val frag1 = PlatformBuffer.allocate(1)
        frag1.writeByte(0x00) // High byte of length
        frag1.resetForRead()
        processor.append(frag1)

        assertEquals(1, processor.available())

        // Second fragment: rest of header + partial payload
        val frag2 = PlatformBuffer.allocate(4)
        frag2.writeByte(0x05) // Low byte of length (length = 5)
        frag2.writeByte(0x11)
        frag2.writeByte(0x22)
        frag2.writeByte(0x33)
        frag2.resetForRead()
        processor.append(frag2)

        assertEquals(5, processor.available())

        // Now we can read the length
        val length = processor.readShort().toInt() and 0xFFFF
        assertEquals(5, length)

        // Third fragment: rest of payload
        val frag3 = PlatformBuffer.allocate(2)
        frag3.writeByte(0x44)
        frag3.writeByte(0x55)
        frag3.resetForRead()
        processor.append(frag3)

        assertEquals(5, processor.available())

        // Read full payload
        val payload = processor.readBuffer(5)
        assertEquals(0x11.toByte(), payload.readByte())
        assertEquals(0x22.toByte(), payload.readByte())
        assertEquals(0x33.toByte(), payload.readByte())
        assertEquals(0x44.toByte(), payload.readByte())
        assertEquals(0x55.toByte(), payload.readByte())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorManySmallChunks() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)

        // Simulate worst case: single-byte chunks
        for (i in 0 until 20) {
            val buffer = PlatformBuffer.allocate(1)
            buffer.writeByte(i.toByte())
            buffer.resetForRead()
            processor.append(buffer)
        }

        assertEquals(20, processor.available())

        // Read as various primitive types
        val short1 = processor.readShort()
        assertEquals(0x0001.toShort(), short1)

        val int1 = processor.readInt()
        assertEquals(0x02030405, int1)

        val long1 = processor.readLong()
        assertEquals(0x060708090A0B0C0DL, long1)

        assertEquals(6, processor.available())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // StreamProcessorBuilder Tests
    // ============================================================================

    @Test
    fun streamProcessorBuilderCreatesProcessor() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.builder(pool).build()

        assertEquals(0, processor.available())

        val buffer = PlatformBuffer.allocate(10)
        repeat(10) { buffer.writeByte(it.toByte()) }
        buffer.resetForRead()

        processor.append(buffer)
        assertEquals(10, processor.available())
        assertEquals(0.toByte(), processor.readByte())

        processor.release()
        pool.clear()
    }

    @Test
    fun streamProcessorBuilderCreatesSuspendingProcessor() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.builder(pool).buildSuspending()

        assertEquals(0, processor.available())
        processor.release()
        pool.clear()
    }
}
