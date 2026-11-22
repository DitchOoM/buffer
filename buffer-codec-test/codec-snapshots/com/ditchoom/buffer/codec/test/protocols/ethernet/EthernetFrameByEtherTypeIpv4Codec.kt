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
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object EthernetFrameByEtherTypeIpv4Codec : Codec<EthernetFrameByEtherType.Ipv4> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): EthernetFrameByEtherType.Ipv4 {
    val etherType = EtherType(buffer.readUShort())
    return EthernetFrameByEtherType.Ipv4(etherType = etherType)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: EthernetFrameByEtherType.Ipv4,
    context: EncodeContext,
  ) {
    buffer.writeUShort(value.etherType.raw)
  }

  override fun wireBytes(`value`: EthernetFrameByEtherType.Ipv4, context: EncodeContext): WireByteCount = WireByteCount.exact(2L)

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: EthernetFrameByEtherType.Ipv4, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: EthernetFrameByEtherType.Ipv4, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(2L)

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
