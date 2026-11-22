package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

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
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object DispatchVarintUnionCodec : Codec<DispatchVarintUnion> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): DispatchVarintUnion {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x01 -> DispatchVarintUnionSingleCodec.decode(buffer, context)
      0x02 -> DispatchVarintUnionMixedCodec.decode(buffer, context)
      0x03 -> DispatchVarintUnionMarkerCodec.decode(buffer, context)
      0x04 -> DispatchVarintUnionPlainCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "DispatchVarintUnion.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x01, 0x02, 0x03, 0x04}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: DispatchVarintUnion,
    context: EncodeContext,
  ) {
    when (value) {
      is DispatchVarintUnion.Single -> {
        buffer.writeUByte(0x01.toUByte())
        DispatchVarintUnionSingleCodec.encode(buffer, value, context)
      }
      is DispatchVarintUnion.Mixed -> {
        buffer.writeUByte(0x02.toUByte())
        DispatchVarintUnionMixedCodec.encode(buffer, value, context)
      }
      is DispatchVarintUnion.Marker -> {
        buffer.writeUByte(0x03.toUByte())
        DispatchVarintUnionMarkerCodec.encode(buffer, value, context)
      }
      is DispatchVarintUnion.Plain -> {
        buffer.writeUByte(0x04.toUByte())
        DispatchVarintUnionPlainCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireBytes(`value`: DispatchVarintUnion, context: EncodeContext): WireByteCount = when (value) {
    is DispatchVarintUnion.Single -> DispatchVarintUnionSingleCodec.wireBytes(value, context).fold(exact = { WireByteCount.exact(1 + it) }, backPatch = { WireByteCount.BackPatch })
    is DispatchVarintUnion.Mixed -> DispatchVarintUnionMixedCodec.wireBytes(value, context).fold(exact = { WireByteCount.exact(1 + it) }, backPatch = { WireByteCount.BackPatch })
    is DispatchVarintUnion.Marker -> WireByteCount.exact(1L)
    is DispatchVarintUnion.Plain -> DispatchVarintUnionPlainCodec.wireBytes(value, context).fold(exact = { WireByteCount.exact(1 + it) }, backPatch = { WireByteCount.BackPatch })
  }

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: DispatchVarintUnion, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: DispatchVarintUnion, context: EncodeContext): Int = 1 + when (value) {
    is DispatchVarintUnion.Single -> DispatchVarintUnionSingleCodec.sizeHint(value, context)
    is DispatchVarintUnion.Mixed -> DispatchVarintUnionMixedCodec.sizeHint(value, context)
    is DispatchVarintUnion.Marker -> DispatchVarintUnionMarkerCodec.sizeHint(value, context)
    is DispatchVarintUnion.Plain -> DispatchVarintUnionPlainCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x01 -> DispatchVarintUnionSingleCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x02 -> DispatchVarintUnionMixedCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x03 -> DispatchVarintUnionMarkerCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x04 -> DispatchVarintUnionPlainCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      else -> {
        throw DecodeException(fieldPath = "DispatchVarintUnion.discriminator", bufferPosition = baseOffset, expected = "one of {0x01, 0x02, 0x03, 0x04}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
