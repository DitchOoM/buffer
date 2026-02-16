package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.withPool
import com.ditchoom.buffer.stream.AutoFillingSuspendingStreamProcessor
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutoFillingSuspendingStreamProcessorTest {
    @Test
    fun autoFillsOnReadByte() =
        runTest {
            withPool { pool ->
                val chunks = makeChunks(byteArrayOf(0x42))
                val processor = buildAutoFilling(pool, chunks)
                try {
                    assertEquals(0, processor.available())
                    assertEquals(0x42.toByte(), processor.readByte())
                    assertEquals(0, processor.available())
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun autoFillsOnPeekByte() =
        runTest {
            withPool { pool ->
                val chunks = makeChunks(byteArrayOf(0x01, 0x02))
                val processor = buildAutoFilling(pool, chunks)
                try {
                    // Peek at offset 1 requires 2 bytes, triggers refill
                    assertEquals(0x02.toByte(), processor.peekByte(1))
                    // Data should still be available (peek doesn't consume)
                    assertEquals(2, processor.available())
                    assertEquals(0x01.toByte(), processor.readByte())
                    assertEquals(0x02.toByte(), processor.readByte())
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun autoFillsAcrossMultipleChunks() =
        runTest {
            withPool { pool ->
                // Data split across multiple chunks
                val chunks = makeChunks(byteArrayOf(0x00, 0x01), byteArrayOf(0x02, 0x03))
                val processor = buildAutoFilling(pool, chunks)
                try {
                    val value = processor.readInt()
                    assertEquals(0x00010203, value)
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun readBufferAutoFills() =
        runTest {
            withPool { pool ->
                val data = "Hello, AutoFill!".encodeToByteArray()
                val chunks = makeChunks(data)
                val processor = buildAutoFilling(pool, chunks)
                try {
                    val buf = processor.readBuffer(data.size)
                    assertEquals("Hello, AutoFill!", buf.readString(buf.remaining()))
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun skipAutoFills() =
        runTest {
            withPool { pool ->
                val chunks = makeChunks(byteArrayOf(0x00, 0x00, 0x00, 0x42))
                val processor = buildAutoFilling(pool, chunks)
                try {
                    processor.skip(3)
                    assertEquals(0x42.toByte(), processor.readByte())
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun throwsEndOfStreamWhenExhausted() =
        runTest {
            withPool { pool ->
                val chunks = makeChunks(byteArrayOf(0x01))
                val processor = buildAutoFilling(pool, chunks)
                try {
                    processor.readByte() // consumes the only byte
                    assertFailsWith<EndOfStreamException> {
                        processor.readByte() // no more data, refill throws
                    }
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun peekShortAutoFills() =
        runTest {
            withPool { pool ->
                val chunks = makeChunks(byteArrayOf(0x12, 0x34))
                val processor = buildAutoFilling(pool, chunks)
                try {
                    assertEquals(0x1234.toShort(), processor.peekShort())
                    assertEquals(0x1234.toShort(), processor.readShort())
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun peekIntAutoFills() =
        runTest {
            withPool { pool ->
                val chunks = makeChunks(byteArrayOf(0x12, 0x34), byteArrayOf(0x56, 0x78))
                val processor = buildAutoFilling(pool, chunks)
                try {
                    assertEquals(0x12345678, processor.peekInt())
                    assertEquals(0x12345678, processor.readInt())
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun peekLongAutoFills() =
        runTest {
            withPool { pool ->
                val data =
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
                val chunks = makeChunks(data.sliceArray(0..3), data.sliceArray(4..7))
                val processor = buildAutoFilling(pool, chunks)
                try {
                    val expected = 0x0102030405060708L
                    assertEquals(expected, processor.peekLong())
                    assertEquals(expected, processor.readLong())
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun peekMatchesAutoFills() =
        runTest {
            withPool { pool ->
                val chunks = makeChunks("Hello".encodeToByteArray())
                val processor = buildAutoFilling(pool, chunks)
                try {
                    val pattern = PlatformBuffer.allocate(5)
                    pattern.writeBytes("Hello".encodeToByteArray())
                    pattern.resetForRead()
                    assertTrue(processor.peekMatches(pattern))
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun multipleRefillsForLargeRead() =
        runTest {
            withPool { pool ->
                // 10 single-byte chunks to satisfy a 10-byte read
                val chunks = (0 until 10).map { byteArrayOf(it.toByte()) }
                val processor = buildAutoFilling(pool, chunks.map { makeBuffer(it) }.toMutableList())
                try {
                    val buf = processor.readBuffer(10)
                    assertEquals(10, buf.remaining())
                    for (i in 0 until 10) {
                        assertEquals(i.toByte(), buf.readByte())
                    }
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun appendDirectlyStillWorks() =
        runTest {
            withPool { pool ->
                val chunks = mutableListOf<ReadBuffer>()
                val processor = buildAutoFilling(pool, chunks)
                try {
                    // Directly append data (not via refill)
                    val buf = PlatformBuffer.allocate(4)
                    buf.writeInt(0xDEADBEEF.toInt())
                    buf.resetForRead()
                    processor.append(buf)

                    assertEquals(4, processor.available())
                    assertEquals(0xDEADBEEF.toInt(), processor.readInt())
                } finally {
                    processor.release()
                }
            }
        }

    // Helper: build auto-filling processor from byte array varargs
    private fun buildAutoFilling(
        pool: BufferPool,
        chunks: List<ReadBuffer>,
    ): AutoFillingSuspendingStreamProcessor {
        val mutableChunks = chunks.toMutableList()
        return StreamProcessor
            .builder(pool)
            .buildSuspendingWithAutoFill { stream ->
                if (mutableChunks.isEmpty()) throw EndOfStreamException()
                stream.append(mutableChunks.removeAt(0))
            }
    }

    private fun makeChunks(vararg arrays: ByteArray): List<ReadBuffer> = arrays.map { makeBuffer(it) }

    private fun makeBuffer(data: ByteArray): ReadBuffer {
        val buf = PlatformBuffer.allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()
        return buf
    }
}
