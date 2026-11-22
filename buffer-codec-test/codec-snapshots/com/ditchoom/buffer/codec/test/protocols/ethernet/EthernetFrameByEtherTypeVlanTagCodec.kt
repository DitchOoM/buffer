package com.ditchoom.buffer.codec.test.protocols.ethernet

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object EthernetFrameByEtherTypeVlanTagCodec : Codec<EthernetFrameByEtherType.VlanTag> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): EthernetFrameByEtherType.VlanTag {
    val etherType = EtherType(buffer.readUShort())
    return EthernetFrameByEtherType.VlanTag(etherType = etherType)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: EthernetFrameByEtherType.VlanTag,
    context: EncodeContext,
  ) {
    buffer.writeUShort(value.etherType.raw)
  }

  override fun wireBytes(`value`: EthernetFrameByEtherType.VlanTag, context: EncodeContext): WireByteCount = WireByteCount.exact(2L)

  override fun sizeHint(`value`: EthernetFrameByEtherType.VlanTag, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 2L)
}
