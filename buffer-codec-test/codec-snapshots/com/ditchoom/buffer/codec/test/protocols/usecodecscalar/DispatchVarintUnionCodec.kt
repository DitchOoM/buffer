package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

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

  override fun wireSize(`value`: DispatchVarintUnion, context: EncodeContext): WireSize = when (value) {
    is DispatchVarintUnion.Single -> {
      when (val inner = DispatchVarintUnionSingleCodec.wireSize(value, context)) {
        is WireSize.Exact -> WireSize.Exact(1 + inner.bytes)
        WireSize.BackPatch -> WireSize.BackPatch
      }
    }
    is DispatchVarintUnion.Mixed -> {
      when (val inner = DispatchVarintUnionMixedCodec.wireSize(value, context)) {
        is WireSize.Exact -> WireSize.Exact(1 + inner.bytes)
        WireSize.BackPatch -> WireSize.BackPatch
      }
    }
    is DispatchVarintUnion.Marker -> WireSize.Exact(1)
    is DispatchVarintUnion.Plain -> WireSize.BackPatch
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x01 -> {
        when (val inner = DispatchVarintUnionSingleCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x02 -> {
        when (val inner = DispatchVarintUnionMixedCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x03 -> {
        when (val inner = DispatchVarintUnionMarkerCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x04 -> {
        when (val inner = DispatchVarintUnionPlainCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      else -> {
        throw DecodeException(fieldPath = "DispatchVarintUnion.discriminator", bufferPosition = baseOffset, expected = "one of {0x01, 0x02, 0x03, 0x04}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
