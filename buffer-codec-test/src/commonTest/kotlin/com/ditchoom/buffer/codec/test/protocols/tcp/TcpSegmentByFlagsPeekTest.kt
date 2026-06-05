package com.ditchoom.buffer.codec.test.protocols.tcp

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
 * The fixture models exactly the 1-byte TCP control-bits dispatch slice (RFC 793
 * §3.1, byte 13 of the TCP header), so each variant's wire form is a single byte.
 * `peekFrameSize` framing that as `Complete(1)` is correct and honest — it frames
 * exactly the byte the fixture decodes. This locks `peek.bytes == decode
 * consumption`; with no buffered bytes the peek is `NeedsMoreData`.
 */
class TcpSegmentByFlagsPeekTest {
    @Test
    fun peekFramesSingleFlagsByteDispatch() {
        for (frame in listOf(
            TcpSegmentByFlags.Syn(),
            TcpSegmentByFlags.SynAck(),
            TcpSegmentByFlags.Ack(),
            TcpSegmentByFlags.FinAck(),
            TcpSegmentByFlags.Rst(),
        )) {
            val pool = BufferPool()
            val source = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
            TcpSegmentByFlagsCodec.encode(source, frame, EncodeContext.Empty)
            source.resetForRead()
            assertEquals(1, source.remaining(), "flags dispatch slice is 1 byte")
            val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
            try {
                assertEquals(PeekResult.NeedsMoreData, TcpSegmentByFlagsCodec.peekFrameSize(stream), "no bytes")
                appendByte(stream, source.readByte())
                assertEquals(PeekResult.Complete(1), TcpSegmentByFlagsCodec.peekFrameSize(stream), "fully buffered")

                val decodeBuffer = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
                TcpSegmentByFlagsCodec.encode(decodeBuffer, frame, EncodeContext.Empty)
                decodeBuffer.resetForRead()
                TcpSegmentByFlagsCodec.decode(decodeBuffer, DecodeContext.Empty)
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
