package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramePeek
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

  override fun sizeHint(`value`: CountBadge, context: EncodeContext): Int = 1 + when (value) {
    is CountBadge.Named -> CountBadgeNamedCodec.sizeHint(value, context)
    is CountBadge.Anonymous -> CountBadgeAnonymousCodec.sizeHint(value, context)
  }

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek {
    if (stream.available() - baseOffset < 1) return FramePeek.needsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x00 -> CountBadgeNamedCodec.peekFrame(stream, baseOffset + 1).withPrefix(1)
      0x01 -> CountBadgeAnonymousCodec.peekFrame(stream, baseOffset + 1).withPrefix(1)
      else -> {
        throw DecodeException(fieldPath = "CountBadge.discriminator", bufferPosition = baseOffset, expected = "one of {0x00, 0x01}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
