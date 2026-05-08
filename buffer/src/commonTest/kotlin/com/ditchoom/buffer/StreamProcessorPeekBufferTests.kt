package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [StreamProcessor.peekBuffer] — the non-consuming view used by
 * `@UseCodec`-driven peek.
 */
class StreamProcessorPeekBufferTests {
    @Test
    fun peekBufferReturnsNullWhenEmpty() {
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            assertNull(processor.peekBuffer(offset = 0, maxBytes = 4))
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun peekBufferReturnsNullWhenOffsetPastAvailable() {
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val chunk = BufferFactory.managed().allocate(3)
            chunk.writeByte(1)
            chunk.writeByte(2)
            chunk.writeByte(3)
            chunk.resetForRead()
            processor.append(chunk)

            assertNull(processor.peekBuffer(offset = 3, maxBytes = 1))
            assertNull(processor.peekBuffer(offset = 100, maxBytes = 1))
            assertEquals(3, processor.available())
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun peekBufferSingleChunkFastPath() {
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val chunk = BufferFactory.managed().allocate(8)
            for (i in 1..8) chunk.writeByte(i.toByte())
            chunk.resetForRead()
            processor.append(chunk)

            val view = processor.peekBuffer(offset = 0, maxBytes = 4)
            assertNotNull(view)
            assertEquals(4, view.remaining())
            assertEquals(1.toByte(), view.readByte())
            assertEquals(2.toByte(), view.readByte())
            assertEquals(3.toByte(), view.readByte())
            assertEquals(4.toByte(), view.readByte())
            assertEquals(8, processor.available())
            assertEquals(1.toByte(), processor.peekByte(0))
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun peekBufferRespectsOffsetWithinSingleChunk() {
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val chunk = BufferFactory.managed().allocate(6)
            for (i in 1..6) chunk.writeByte(i.toByte())
            chunk.resetForRead()
            processor.append(chunk)

            val view = processor.peekBuffer(offset = 2, maxBytes = 3)
            assertNotNull(view)
            assertEquals(3, view.remaining())
            assertEquals(3.toByte(), view.readByte())
            assertEquals(4.toByte(), view.readByte())
            assertEquals(5.toByte(), view.readByte())
            assertEquals(6, processor.available())
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun peekBufferTruncatesToAvailableWhenMaxExceedsRemaining() {
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val chunk = BufferFactory.managed().allocate(3)
            chunk.writeByte(0xAA.toByte())
            chunk.writeByte(0xBB.toByte())
            chunk.writeByte(0xCC.toByte())
            chunk.resetForRead()
            processor.append(chunk)

            val view = processor.peekBuffer(offset = 1, maxBytes = 100)
            assertNotNull(view)
            assertEquals(2, view.remaining())
            assertEquals(0xBB.toByte(), view.readByte())
            assertEquals(0xCC.toByte(), view.readByte())
            assertEquals(3, processor.available())
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun peekBufferSpansChunksViaCopy() {
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val chunk1 = BufferFactory.managed().allocate(3)
            chunk1.writeByte(1)
            chunk1.writeByte(2)
            chunk1.writeByte(3)
            chunk1.resetForRead()
            processor.append(chunk1)

            val chunk2 = BufferFactory.managed().allocate(3)
            chunk2.writeByte(4)
            chunk2.writeByte(5)
            chunk2.writeByte(6)
            chunk2.resetForRead()
            processor.append(chunk2)

            val view = processor.peekBuffer(offset = 1, maxBytes = 4)
            assertNotNull(view)
            assertEquals(4, view.remaining())
            assertEquals(2.toByte(), view.readByte())
            assertEquals(3.toByte(), view.readByte())
            assertEquals(4.toByte(), view.readByte())
            assertEquals(5.toByte(), view.readByte())
            assertEquals(6, processor.available())
            assertEquals(1.toByte(), processor.readByte())
            assertEquals(2.toByte(), processor.readByte())
            assertEquals(3.toByte(), processor.readByte())
            assertEquals(4.toByte(), processor.readByte())
            assertEquals(5.toByte(), processor.readByte())
            assertEquals(6.toByte(), processor.readByte())
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun peekBufferDoesNotMutateChunkPositionOnFastPath() {
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val chunk = BufferFactory.managed().allocate(4)
            chunk.writeByte(10)
            chunk.writeByte(20)
            chunk.writeByte(30)
            chunk.writeByte(40)
            chunk.resetForRead()
            processor.append(chunk)

            repeat(3) {
                val view = processor.peekBuffer(offset = 0, maxBytes = 2)
                assertNotNull(view)
                assertEquals(10.toByte(), view.readByte())
                assertEquals(20.toByte(), view.readByte())
            }
            assertEquals(4, processor.available())
            assertEquals(10.toByte(), processor.readByte())
            assertEquals(20.toByte(), processor.readByte())
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun peekBufferDoesNotMutateChunkPositionOnSlowPath() {
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val chunk1 = BufferFactory.managed().allocate(2)
            chunk1.writeByte(10)
            chunk1.writeByte(20)
            chunk1.resetForRead()
            processor.append(chunk1)

            val chunk2 = BufferFactory.managed().allocate(2)
            chunk2.writeByte(30)
            chunk2.writeByte(40)
            chunk2.resetForRead()
            processor.append(chunk2)

            repeat(3) {
                val view = processor.peekBuffer(offset = 0, maxBytes = 4)
                assertNotNull(view)
                assertEquals(4, view.remaining())
                assertEquals(10.toByte(), view.readByte())
                assertEquals(20.toByte(), view.readByte())
                assertEquals(30.toByte(), view.readByte())
                assertEquals(40.toByte(), view.readByte())
            }
            assertEquals(4, processor.available())
            assertEquals(10.toByte(), processor.readByte())
            assertEquals(20.toByte(), processor.readByte())
            assertEquals(30.toByte(), processor.readByte())
            assertEquals(40.toByte(), processor.readByte())
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun peekBufferOffsetExactlyAtChunkBoundary() {
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val chunk1 = BufferFactory.managed().allocate(2)
            chunk1.writeByte(1)
            chunk1.writeByte(2)
            chunk1.resetForRead()
            processor.append(chunk1)

            val chunk2 = BufferFactory.managed().allocate(2)
            chunk2.writeByte(3)
            chunk2.writeByte(4)
            chunk2.resetForRead()
            processor.append(chunk2)

            val view = processor.peekBuffer(offset = 2, maxBytes = 2)
            assertNotNull(view)
            assertEquals(2, view.remaining())
            assertEquals(3.toByte(), view.readByte())
            assertEquals(4.toByte(), view.readByte())
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun peekBufferRequiresNonNegativeOffset() {
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val chunk = BufferFactory.managed().allocate(1)
            chunk.writeByte(1)
            chunk.resetForRead()
            processor.append(chunk)

            assertFailsWith<IllegalArgumentException> {
                processor.peekBuffer(offset = -1, maxBytes = 1)
            }
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun peekBufferRequiresPositiveMaxBytes() {
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val chunk = BufferFactory.managed().allocate(1)
            chunk.writeByte(1)
            chunk.resetForRead()
            processor.append(chunk)

            assertFailsWith<IllegalArgumentException> {
                processor.peekBuffer(offset = 0, maxBytes = 0)
            }
        } finally {
            processor.release()
            pool.clear()
        }
    }
}
