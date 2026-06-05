package com.ditchoom.buffer.codec.test.protocols.png

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.ownedBytesFrom
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The PNG fixture models `data` as a consumer-bounded `@RemainingBytes` field
 * (the outer reader bounds the buffer to the chunk extent before decode), so the
 * codec deliberately does NOT self-frame: `peekFrameSize` returns `NoFraming`.
 * That is the honest claim for this modeling — the codec must not pretend to know
 * the chunk size it was never told to read.
 *
 * This locks two things: (1) the peek stays `NoFraming` no matter how many bytes
 * are buffered (it never falsely upgrades to `Complete`), and (2) when the caller
 * bounds the buffer to the chunk extent, decode consumes exactly to that limit.
 *
 * (The genuinely self-delimiting PNG framing — `Complete(length + 12)` via
 * `@LengthFrom("length")` — is a separate reference-fixture upgrade, not this
 * consumer-bounded probe.)
 */
class PngChunkPeekTest {
    private fun data(size: Int): BinaryData {
        val buf = BufferFactory.Default.allocate(maxOf(size, 1), ByteOrder.BIG_ENDIAN)
        for (i in 0 until size) buf.writeByte((i and 0x7F).toByte())
        buf.resetForRead()
        return BinaryData(ownedBytesFrom(buf))
    }

    @Test
    fun peekStaysNoFramingRegardlessOfBufferedBytes() {
        val chunk = PngChunk(length = 4u, type = 0x49444154u, data = data(4), crc = 0xDEADBEEFu)
        val total = 4 + 4 + 4 + 4 // length + type + data + crc
        val pool = BufferPool()
        val source = BufferFactory.Default.allocate(total + 8, ByteOrder.BIG_ENDIAN)
        PngChunkCodec.encode(source, chunk, EncodeContext.Empty)
        source.resetForRead()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            // NoFraming at every prefix length, including fully buffered — the
            // codec never claims a frame size it was not given.
            for (i in 0 until total) {
                appendByte(stream, source.readByte())
                assertEquals(
                    PeekResult.NoFraming,
                    PngChunkCodec.peekFrameSize(stream),
                    "after ${i + 1}/$total bytes",
                )
            }
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun decodeConsumesToCallerBoundedLimit() {
        val chunk = PngChunk(length = 4u, type = 0x49444154u, data = data(4), crc = 0xDEADBEEFu)
        val total = 4 + 4 + 4 + 4
        val buf = BufferFactory.Default.allocate(total + 8, ByteOrder.BIG_ENDIAN)
        PngChunkCodec.encode(buf, chunk, EncodeContext.Empty)
        buf.resetForRead()
        buf.setLimit(total) // the caller bounds the buffer to the chunk extent
        PngChunkCodec.decode(buf, DecodeContext.Empty)
        assertEquals(total, buf.position(), "decode consumes exactly the caller-bounded chunk extent")
    }

    private fun appendByte(
        stream: StreamProcessor,
        byte: Byte,
    ) {
        val one: PlatformBuffer = BufferFactory.Default.allocate(1)
        one.writeByte(byte)
        one.resetForRead()
        stream.append(one)
    }
}
