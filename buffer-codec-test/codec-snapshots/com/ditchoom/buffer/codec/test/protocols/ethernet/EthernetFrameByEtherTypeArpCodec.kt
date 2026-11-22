package com.ditchoom.buffer.codec.test.protocols.ethernet

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: EthernetFrameByEtherType.Arp, context: EncodeContext): WireByteCount = WireByteCount.exact(2L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: EthernetFrameByEtherType.Arp, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: EthernetFrameByEtherType.Arp, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 2L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
