package com.ditchoom.buffer.codec.test.protocols.ethernet

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object EthernetFrameByEtherTypeIpv6Codec : Codec<EthernetFrameByEtherType.Ipv6> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): EthernetFrameByEtherType.Ipv6 {
    val etherType = EtherType(buffer.readUShort())
    return EthernetFrameByEtherType.Ipv6(etherType = etherType)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: EthernetFrameByEtherType.Ipv6,
    context: EncodeContext,
  ) {
    buffer.writeUShort(value.etherType.raw)
  }

  override fun wireSize(`value`: EthernetFrameByEtherType.Ipv6, context: EncodeContext): WireSize = WireSize.Exact(2)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 2) PeekResult.Complete(2) else PeekResult.NeedsMoreData
}
