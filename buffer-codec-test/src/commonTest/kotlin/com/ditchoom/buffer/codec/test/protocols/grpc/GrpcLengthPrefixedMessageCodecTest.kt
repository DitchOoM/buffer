package com.ditchoom.buffer.codec.test.protocols.grpc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.byteSize
import com.ditchoom.buffer.codec.handleEquals
import com.ditchoom.buffer.codec.ownedBytesFrom
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gRPC `Length-Prefixed-Message` framing: `Compressed-Flag(1) + Message-Length(4
 * BE) + Message`. Self-delimiting via the 4-byte length, so `peekFrameSize`
 * frames the whole envelope as `Complete(1 + 4 + length)`.
 *
 * Pins round-trip plus the `peek.bytes == decode consumption` invariant across
 * message sizes (including a length needing the upper prefix bytes), byte-at-a-
 * time: `NeedsMoreData` until the full envelope is buffered, then `Complete(total)`.
 */
class GrpcLengthPrefixedMessageCodecTest {
    private fun message(size: Int): BinaryData {
        // byteSize() == buffer capacity, so allocate exactly `size` (0 allowed —
        // an empty gRPC message is valid).
        val buf = BufferFactory.Default.allocate(size, ByteOrder.BIG_ENDIAN)
        for (i in 0 until size) buf.writeByte((i and 0x7F).toByte())
        buf.resetForRead()
        return BinaryData(ownedBytesFrom(buf))
    }

    private fun frame(
        compressed: Boolean,
        size: Int,
    ): GrpcLengthPrefixedMessage =
        GrpcLengthPrefixedMessage(
            compressedFlag = if (compressed) 1u else 0u,
            message = message(size),
        )

    @Test
    fun roundTripsAcrossFlagAndMessageSizes() {
        for (size in listOf(0, 5, 300)) {
            for (compressed in listOf(false, true)) {
                val original = frame(compressed, size)
                val buf = BufferFactory.Default.allocate(size + 16, ByteOrder.BIG_ENDIAN)
                GrpcLengthPrefixedMessageCodec.encode(buf, original, EncodeContext.Empty)
                assertEquals(1 + 4 + size, buf.position(), "envelope size (compressed=$compressed, size=$size)")
                buf.resetForRead()
                val decoded = GrpcLengthPrefixedMessageCodec.decode(buf, DecodeContext.Empty)
                assertEquals(original.compressedFlag, decoded.compressedFlag, "flag")
                assertEquals(size, decoded.message.data.byteSize(), "message length")
                assertTrue(original.message.data.handleEquals(decoded.message.data), "message bytes")
            }
        }
    }

    @Test
    fun peekFramesEnvelopeByLengthPrefix() {
        // size 5 → length fits one byte; size 300 → length spills into upper prefix bytes.
        for (size in listOf(0, 5, 300)) {
            assertDripPeek(frame(compressed = false, size = size), expectedTotal = 1 + 4 + size)
        }
    }

    private fun assertDripPeek(
        frame: GrpcLengthPrefixedMessage,
        expectedTotal: Int,
    ) {
        val pool = BufferPool()
        val source = BufferFactory.Default.allocate(expectedTotal + 16, ByteOrder.BIG_ENDIAN)
        GrpcLengthPrefixedMessageCodec.encode(source, frame, EncodeContext.Empty)
        source.resetForRead()
        val total = source.remaining()
        assertEquals(expectedTotal, total, "encoded total")
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until total - 1) {
                appendByte(stream, source.readByte())
                assertEquals(
                    PeekResult.NeedsMoreData,
                    GrpcLengthPrefixedMessageCodec.peekFrameSize(stream),
                    "after ${i + 1}/$total bytes",
                )
            }
            appendByte(stream, source.readByte())
            assertEquals(
                PeekResult.Complete(total),
                GrpcLengthPrefixedMessageCodec.peekFrameSize(stream),
                "fully buffered",
            )
            val decodeBuffer = BufferFactory.Default.allocate(total + 16, ByteOrder.BIG_ENDIAN)
            GrpcLengthPrefixedMessageCodec.encode(decodeBuffer, frame, EncodeContext.Empty)
            decodeBuffer.resetForRead()
            GrpcLengthPrefixedMessageCodec.decode(decodeBuffer, DecodeContext.Empty)
            assertEquals(total, decodeBuffer.position(), "decode consumed exactly the peeked frame size")
        } finally {
            stream.release()
            pool.clear()
        }
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
