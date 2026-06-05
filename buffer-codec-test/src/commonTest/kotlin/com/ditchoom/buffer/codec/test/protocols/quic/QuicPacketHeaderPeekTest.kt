package com.ditchoom.buffer.codec.test.protocols.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fixture models exactly the 1-byte QUIC first byte (RFC 9000 §17.2/§17.3),
 * dispatching on the header-form bit. Each variant's wire form is a single byte,
 * so `peekFrameSize` framing it as `Complete(1)` is correct and honest. The
 * sibling [QuicVarintCodec] peek is tested separately; this locks the packet-
 * header dispatch peek's `peek.bytes == decode consumption`.
 */
class QuicPacketHeaderPeekTest {
    @Test
    fun peekFramesSingleFirstByteDispatch() {
        for (frame in listOf(
            QuicPacketHeader.ShortHeader(),
            QuicPacketHeader.LongHeader(),
        )) {
            val pool = BufferPool()
            val source = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
            QuicPacketHeaderCodec.encode(source, frame, EncodeContext.Empty)
            source.resetForRead()
            assertEquals(1, source.remaining(), "first-byte dispatch slice is 1 byte")
            val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
            try {
                assertEquals(PeekResult.NeedsMoreData, QuicPacketHeaderCodec.peekFrameSize(stream), "no bytes")
                appendByte(stream, source.readByte())
                assertEquals(PeekResult.Complete(1), QuicPacketHeaderCodec.peekFrameSize(stream), "fully buffered")

                val decodeBuffer = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
                QuicPacketHeaderCodec.encode(decodeBuffer, frame, EncodeContext.Empty)
                decodeBuffer.resetForRead()
                QuicPacketHeaderCodec.decode(decodeBuffer, DecodeContext.Empty)
                assertEquals(1, decodeBuffer.position(), "decode consumed exactly the peeked frame size")
            } finally {
                stream.release()
                pool.clear()
            }
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
