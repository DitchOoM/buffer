package com.ditchoom.buffer.codec.test.protocols.ethernet

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
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

public object EthernetFrameByEtherTypeCodec : Codec<EthernetFrameByEtherType> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): EthernetFrameByEtherType {
    val discriminatorPosition = buffer.position()
    val __discriminator = EtherTypeCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.type.toInt()
    return when (__dispatchValue) {
      2_048 -> EthernetFrameByEtherTypeIpv4Codec.decode(buffer, context)
      2_054 -> EthernetFrameByEtherTypeArpCodec.decode(buffer, context)
      33_024 -> EthernetFrameByEtherTypeVlanTagCodec.decode(buffer, context)
      34_525 -> EthernetFrameByEtherTypeIpv6Codec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "EthernetFrameByEtherType.discriminator", bufferPosition = discriminatorPosition, expected = "one of {2048, 2054, 33024, 34525}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: EthernetFrameByEtherType,
    context: EncodeContext,
  ) {
    when (value) {
      is EthernetFrameByEtherType.Ipv4 -> EthernetFrameByEtherTypeIpv4Codec.encode(buffer, value, context)
      is EthernetFrameByEtherType.Arp -> EthernetFrameByEtherTypeArpCodec.encode(buffer, value, context)
      is EthernetFrameByEtherType.VlanTag -> EthernetFrameByEtherTypeVlanTagCodec.encode(buffer, value, context)
      is EthernetFrameByEtherType.Ipv6 -> EthernetFrameByEtherTypeIpv6Codec.encode(buffer, value, context)
    }
  }

  override fun wireBytes(`value`: EthernetFrameByEtherType, context: EncodeContext): WireByteCount = when (value) {
    is EthernetFrameByEtherType.Ipv4 -> EthernetFrameByEtherTypeIpv4Codec.wireBytes(value, context)
    is EthernetFrameByEtherType.Arp -> EthernetFrameByEtherTypeArpCodec.wireBytes(value, context)
    is EthernetFrameByEtherType.VlanTag -> EthernetFrameByEtherTypeVlanTagCodec.wireBytes(value, context)
    is EthernetFrameByEtherType.Ipv6 -> EthernetFrameByEtherTypeIpv6Codec.wireBytes(value, context)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: EthernetFrameByEtherType, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: EthernetFrameByEtherType, context: EncodeContext): Int = when (value) {
    is EthernetFrameByEtherType.Ipv4 -> EthernetFrameByEtherTypeIpv4Codec.sizeHint(value, context)
    is EthernetFrameByEtherType.Arp -> EthernetFrameByEtherTypeArpCodec.sizeHint(value, context)
    is EthernetFrameByEtherType.VlanTag -> EthernetFrameByEtherTypeVlanTagCodec.sizeHint(value, context)
    is EthernetFrameByEtherType.Ipv6 -> EthernetFrameByEtherTypeIpv6Codec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 2) return FrameSize.NeedsMoreData
    val __discRawB0 = stream.peekByte(baseOffset + 0).toInt() and 0xFF
    val __discRawB1 = stream.peekByte(baseOffset + 0 + 1).toInt() and 0xFF
    val __discRaw = ((__discRawB0 shl 8) or __discRawB1).toUInt().toUShort()
    val __discriminator = EtherType(__discRaw)
    val __dispatchValue = __discriminator.type.toInt()
    return when (__dispatchValue) {
      2_048 -> EthernetFrameByEtherTypeIpv4Codec.frameSize(stream, baseOffset)
      2_054 -> EthernetFrameByEtherTypeArpCodec.frameSize(stream, baseOffset)
      33_024 -> EthernetFrameByEtherTypeVlanTagCodec.frameSize(stream, baseOffset)
      34_525 -> EthernetFrameByEtherTypeIpv6Codec.frameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "EthernetFrameByEtherType.discriminator", bufferPosition = baseOffset, expected = "one of {2048, 2054, 33024, 34525}", actual = """${__dispatchValue}""")
      }
    }
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
