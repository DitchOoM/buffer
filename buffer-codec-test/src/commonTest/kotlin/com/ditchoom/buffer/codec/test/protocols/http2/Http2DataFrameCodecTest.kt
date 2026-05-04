package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Stage H slice 10b doctrine vector — second protocol exercising
 * the generic-bounded payload emit path. Confirms the generic codec
 * shape works across protocols (HTTP/2 frame, not MQTT) and across
 * payload types within HTTP/2.
 *
 * The `Http2BinaryPayload` and `Http2OpaquePayload` fixtures are
 * minimal `Payload` types defined inside the test file so the
 * generic codec is the focus rather than payload-codec ergonomics.
 */
class Http2DataFrameCodecTest {
    @Test
    fun roundTripsWithBinaryPayload() {
        val codec = Http2DataFrameCodec(Http2BinaryPayloadCodec)
        val original =
            Http2DataFrame<Http2BinaryPayload>(
                header = Http2LengthAndType.of(length = 4, type = 0),
                flags = 0u,
                streamId = Http2StreamId(7u),
                payload = Http2BinaryPayload(byteArrayOf(0x01, 0x02, 0x03, 0x04)),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, original, EncodeContext.Empty)
        val written = buf.position()
        // 4 (header) + 1 (flags) + 4 (streamId) + 4 (payload) = 13
        assertEquals(13, written)
        buf.resetForRead()
        // Caller bounds the buffer to the frame's byte count — slice
        // 10d's outer dispatcher will derive this from header.length.
        buf.setLimit(written)
        assertEquals(original, codec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun roundTripsWithOpaquePayload() {
        val codec = Http2DataFrameCodec(Http2OpaquePayloadCodec)
        val original =
            Http2DataFrame<Http2OpaquePayload>(
                header = Http2LengthAndType.of(length = 8, type = 0),
                flags = 0x01u, // END_STREAM bit, codec-irrelevant
                streamId = Http2StreamId(0xCAFEu),
                payload = Http2OpaquePayload(0x0123_4567_89AB_CDEFu),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, original, EncodeContext.Empty)
        val written = buf.position()
        buf.resetForRead()
        buf.setLimit(written)
        assertEquals(original, codec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun encodeProducesByteExactWire() {
        val codec = Http2DataFrameCodec(Http2BinaryPayloadCodec)
        val msg =
            Http2DataFrame<Http2BinaryPayload>(
                header = Http2LengthAndType.of(length = 3, type = 0),
                flags = 0u,
                streamId = Http2StreamId(1u),
                payload = Http2BinaryPayload(byteArrayOf(0x11, 0x22, 0x33)),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, msg, EncodeContext.Empty)
        buf.resetForRead()
        val actual = buf.readByteArray(buf.remaining())
        val expected =
            byteArrayOf(
                // header: length=3 (24 bits BE) | type=0
                0x00, 0x00, 0x03, 0x00,
                // flags
                0x00,
                // streamId (31-bit BE; high R bit = 0)
                0x00, 0x00, 0x00, 0x01,
                // payload bytes
                0x11, 0x22, 0x33,
            )
        assertContentEquals(expected, actual)
    }

    @Test
    fun wireSizeIsBackPatch() {
        val codec = Http2DataFrameCodec(Http2BinaryPayloadCodec)
        val msg =
            Http2DataFrame<Http2BinaryPayload>(
                header = Http2LengthAndType.of(length = 0, type = 0),
                flags = 0u,
                streamId = Http2StreamId(0u),
                payload = Http2BinaryPayload(ByteArray(0)),
            )
        assertEquals(WireSize.BackPatch, codec.wireSize(msg, EncodeContext.Empty))
    }
}

/** Minimal binary `Payload` for the slice 10b HTTP/2 vector. */
private data class Http2BinaryPayload(
    val data: ByteArray,
) : Payload {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Http2BinaryPayload) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}

private object Http2BinaryPayloadCodec : Codec<Http2BinaryPayload> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Http2BinaryPayload = Http2BinaryPayload(buffer.readByteArray(buffer.remaining()))

    override fun encode(
        buffer: WriteBuffer,
        value: Http2BinaryPayload,
        context: EncodeContext,
    ) {
        buffer.writeBytes(value.data)
    }

    override fun wireSize(
        value: Http2BinaryPayload,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.data.size)
}

/**
 * Second `Payload` shape for the slice 10b HTTP/2 vector — a
 * fixed-size opaque ULong, like the PING frame body. Distinct from
 * `Http2BinaryPayload` so the test confirms generic codec
 * instantiation across payload types within the same protocol.
 */
private data class Http2OpaquePayload(
    val value: ULong,
) : Payload

private object Http2OpaquePayloadCodec : Codec<Http2OpaquePayload> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Http2OpaquePayload = Http2OpaquePayload(buffer.readULong())

    override fun encode(
        buffer: WriteBuffer,
        value: Http2OpaquePayload,
        context: EncodeContext,
    ) {
        buffer.writeULong(value.value)
    }

    override fun wireSize(
        value: Http2OpaquePayload,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(8)
}
