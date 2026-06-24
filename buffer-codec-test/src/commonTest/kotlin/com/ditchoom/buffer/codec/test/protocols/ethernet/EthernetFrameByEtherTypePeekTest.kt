package com.ditchoom.buffer.codec.test.protocols.ethernet

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
 * The fixture models exactly the 2-byte EtherType dispatch slice (IEEE 802.3,
 * offset 12-13 of an Ethernet II frame), so each variant's wire form is 2 bytes.
 * `peekFrameSize` framing that 2-byte message as `Complete(2)` is correct and
 * honest — it frames exactly the bytes the fixture decodes (it does not claim to
 * frame a whole, externally-delimited Ethernet frame). This locks
 * `peek.bytes == decode consumption`.
 */
class EthernetFrameByEtherTypePeekTest {
    @Test
    fun peekFramesTwoByteEtherTypeDispatch() {
        for (frame in listOf(
            EthernetFrameByEtherType.Ipv4(),
            EthernetFrameByEtherType.Arp(),
            EthernetFrameByEtherType.Ipv6(),
            EthernetFrameByEtherType.VlanTag(),
        )) {
            val pool = BufferPool()
            val source = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
            EthernetFrameByEtherTypeCodec.encode(source, frame, EncodeContext.Empty)
            source.resetForRead()
            val total = source.remaining()
            assertEquals(2, total, "EtherType dispatch slice is 2 bytes")
            val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
            try {
                appendByte(stream, source.readByte())
                assertEquals(PeekResult.NeedsMoreData, EthernetFrameByEtherTypeCodec.peekFrameSize(stream), "1/2 bytes")
                appendByte(stream, source.readByte())
                assertEquals(
                    PeekResult.Complete(2),
                    EthernetFrameByEtherTypeCodec.peekFrameSize(stream),
                    "fully buffered",
                )

                val decodeBuffer = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
                EthernetFrameByEtherTypeCodec.encode(decodeBuffer, frame, EncodeContext.Empty)
                decodeBuffer.resetForRead()
                EthernetFrameByEtherTypeCodec.decode(decodeBuffer, DecodeContext.Empty)
                assertEquals(2, decodeBuffer.position(), "decode consumed exactly the peeked frame size")
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
