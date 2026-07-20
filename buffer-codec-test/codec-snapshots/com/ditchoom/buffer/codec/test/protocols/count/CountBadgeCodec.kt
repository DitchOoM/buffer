package com.ditchoom.buffer.codec.test.protocols.count

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

public object CountBadgeCodec : Codec<CountBadge> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountBadge {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x00 -> CountBadgeNamedCodec.decode(buffer, context)
      0x01 -> CountBadgeAnonymousCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "CountBadge.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x00, 0x01}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountBadge,
    context: EncodeContext,
  ) {
    when (value) {
      is CountBadge.Named -> {
        buffer.writeUByte(0x00.toUByte())
        CountBadgeNamedCodec.encode(buffer, value, context)
      }
      is CountBadge.Anonymous -> {
        buffer.writeUByte(0x01.toUByte())
        CountBadgeAnonymousCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireSize(`value`: CountBadge, context: EncodeContext): WireSize = when (value) {
    is CountBadge.Named -> WireSize.BackPatch
    is CountBadge.Anonymous -> WireSize.Exact(1)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x00 -> {
        when (val inner = CountBadgeNamedCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x01 -> {
        when (val inner = CountBadgeAnonymousCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      else -> {
        throw DecodeException(fieldPath = "CountBadge.discriminator", bufferPosition = baseOffset, expected = "one of {0x00, 0x01}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
