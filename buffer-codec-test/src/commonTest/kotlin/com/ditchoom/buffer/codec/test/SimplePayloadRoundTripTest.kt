package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.SimplePayloadMessage
import com.ditchoom.buffer.codec.test.protocols.SimplePayloadMessageCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class SimplePayloadRoundTripTest {
    @Test
    fun `round trip simple payload with string`() {
        val original = SimplePayloadMessage<String>(id = 42u, data = "hello")
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        SimplePayloadMessageCodec.encode(
            buffer,
            original,
            encodeData = { buf, s -> buf.writeString(s) },
        )
        buffer.resetForRead()
        val decoded =
            SimplePayloadMessageCodec.decode<String>(
                buffer,
                decodeData = { pr -> pr.readString(pr.remaining()) },
            )
        assertEquals(original.id, decoded.id)
        assertEquals(original.data, decoded.data)
    }

    @Test
    fun `round trip simple payload with int`() {
        val original = SimplePayloadMessage<Int>(id = 100u, data = 12345)
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        SimplePayloadMessageCodec.encode(
            buffer,
            original,
            encodeData = { buf, v -> buf.writeInt(v) },
        )
        buffer.resetForRead()
        val decoded =
            SimplePayloadMessageCodec.decode<Int>(
                buffer,
                decodeData = { pr -> pr.readInt() },
            )
        assertEquals(original.id, decoded.id)
        assertEquals(original.data, decoded.data)
    }
}
