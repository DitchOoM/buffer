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

public object EthernetFrameByEtherTypeArpCodec : Codec<EthernetFrameByEtherType.Arp> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): EthernetFrameByEtherType.Arp {
    val etherType = EtherType(buffer.readUShort())
    return EthernetFrameByEtherType.Arp(etherType = etherType)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: EthernetFrameByEtherType.Arp,
    context: EncodeContext,
  ) {
    buffer.writeUShort(value.etherType.raw)
  }

  override fun wireSize(`value`: EthernetFrameByEtherType.Arp, context: EncodeContext): WireSize = WireSize.Exact(2)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 2) PeekResult.Complete(2) else PeekResult.NeedsMoreData
}
