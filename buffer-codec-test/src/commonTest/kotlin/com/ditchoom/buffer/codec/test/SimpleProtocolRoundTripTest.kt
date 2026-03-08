package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.SimpleHeader
import com.ditchoom.buffer.codec.test.protocols.SimpleHeaderCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleProtocolRoundTripTest {
    @Test
    fun `round trip simple header`() {
        val original =
            SimpleHeader(
                type = 0x42u,
                length = 1024u,
                flags = 0xDEADBEEFu,
            )
        val buffer = BufferFactory.Default.allocate(64)
        SimpleHeaderCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = SimpleHeaderCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun `round trip with zero values`() {
        val original = SimpleHeader(0u, 0u, 0u)
        val buffer = BufferFactory.Default.allocate(64)
        SimpleHeaderCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = SimpleHeaderCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun `round trip with max values`() {
        val original = SimpleHeader(UByte.MAX_VALUE, UShort.MAX_VALUE, UInt.MAX_VALUE)
        val buffer = BufferFactory.Default.allocate(64)
        SimpleHeaderCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = SimpleHeaderCodec.decode(buffer)
        assertEquals(original, decoded)
    }
}
