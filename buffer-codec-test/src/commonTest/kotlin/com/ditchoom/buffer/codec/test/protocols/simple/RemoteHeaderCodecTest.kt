package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stage E slice 4 doctrine vector. Validates `@LengthFrom`'s
 * non-adjacent shape: round-trip across multiple body sizes,
 * decoder reads `payloadLength` UTF-8 bytes from the buffer,
 * encoder trusts the user's `payloadLength` and writes the body
 * without a prefix slot, `wireSize.Exact` based on
 * `value.payloadLength`, and `peekFrameSize` walks the fixed
 * header, peeks the sibling at its known offset, and totals
 * header bytes + body bytes.
 */
class RemoteHeaderCodecTest {
    @Test
    fun roundTripsNonEmptyBody() {
        val payload = "hi"
        roundTrip(
            RemoteHeader(
                payloadLength = payload.encodeToByteArray().size.toUShort(),
                flags = 0x00u,
                correlationId = 1u,
                payload = payload,
            ),
            expectedTotalBytes = 2 + 1 + 4 + payload.encodeToByteArray().size,
        )
    }

    @Test
    fun roundTripsMultiByteUtf8Body() {
        val payload = "héllo"
        val bodyBytes = payload.encodeToByteArray().size
        roundTrip(
            RemoteHeader(
                payloadLength = bodyBytes.toUShort(),
                flags = 0xFFu,
                correlationId = 0xDEADBEEFu,
                payload = payload,
            ),
            expectedTotalBytes = 2 + 1 + 4 + bodyBytes,
        )
    }

    @Test
    fun roundTripsEmptyBody() {
        roundTrip(
            RemoteHeader(
                payloadLength = 0u,
                flags = 0x00u,
                correlationId = 0u,
                payload = "",
            ),
            expectedTotalBytes = 2 + 1 + 4,
        )
    }

    @Test
    fun encodeWritesHeaderThenBodyWithoutOwnPrefix() {
        val buf =
            encode(
                RemoteHeader(
                    payloadLength = 2u,
                    flags = 0xA5u,
                    correlationId = 0x01020304u,
                    payload = "hi",
                ),
            )
        assertEquals(2 + 1 + 4 + 2, buf.position(), "encode wrote header + body bytes (no extra prefix)")
        buf.resetForRead()
        assertEquals(2.toShort(), buf.readShort(), "payloadLength is the leading UShort BE")
        assertEquals(0xA5.toByte(), buf.readByte(), "flags follows")
        assertEquals(0x01020304, buf.readInt(), "correlationId follows as UInt BE")
        assertEquals('h'.code.toByte(), buf.readByte())
        assertEquals('i'.code.toByte(), buf.readByte())
    }

    @Test
    fun decodeReadsPayloadLengthBytesIntoString() {
        val buf = BufferFactory.Default.allocate(16)
        buf.writeShort(3.toShort())
        buf.writeByte(0x00.toByte())
        buf.writeInt(0)
        buf.writeString("hey", Charset.UTF8)
        buf.resetForRead()
        val decoded = RemoteHeaderCodec.decode(buf, DecodeContext.Empty)
        assertEquals(3u.toUShort(), decoded.payloadLength)
        assertEquals("hey", decoded.payload)
    }

    @Test
    fun wireSizeIsExactBasedOnPayloadLength() {
        // Slice 4: wireSize.Exact = headerBytes + value.payloadLength.toInt().
        val msg = RemoteHeader(payloadLength = 5u, flags = 0u, correlationId = 0u, payload = "12345")
        assertEquals(WireSize.Exact(2 + 1 + 4 + 5), RemoteHeaderCodec.wireSize(msg, EncodeContext.Empty))
    }

    @Test
    fun peekFrameSizeNeedsMoreDataWhenEmpty() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, RemoteHeaderCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeWalksNeedsMoreDataToCompleteWhenAllBytesArrive() {
        val pool = BufferPool()
        val original =
            RemoteHeader(
                payloadLength = 2u,
                flags = 0x12u,
                correlationId = 0xCAFEBABEu,
                payload = "hi",
            )
        val encoded = encode(original)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, RemoteHeaderCodec.peekFrameSize(stream))

            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    RemoteHeaderCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), RemoteHeaderCodec.peekFrameSize(stream))

            val decoded =
                stream.readBufferScoped(totalBytes) {
                    RemoteHeaderCodec.decode(this, DecodeContext.Empty)
                }
            assertEquals(original, decoded)
            assertEquals(0, stream.available(), "stream should be drained")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun roundTrip(
        original: RemoteHeader,
        expectedTotalBytes: Int,
    ) {
        val buf = encode(original)
        assertEquals(expectedTotalBytes, buf.position(), "encode wrote $expectedTotalBytes bytes")
        buf.resetForRead()
        val decoded = RemoteHeaderCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    private fun encode(value: RemoteHeader) =
        BufferFactory.Default
            .allocate(256)
            .also { RemoteHeaderCodec.encode(it, value, EncodeContext.Empty) }
}
