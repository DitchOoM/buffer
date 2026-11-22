package com.ditchoom.buffer.codec.test.protocols.count

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

  override fun wireBytes(`value`: CountBadge, context: EncodeContext): WireByteCount = when (value) {
    is CountBadge.Named -> WireByteCount.BackPatch
    is CountBadge.Anonymous -> WireByteCount.exact(1L)
  }

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: CountBadge, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: CountBadge, context: EncodeContext): Int = 1 + when (value) {
    is CountBadge.Named -> CountBadgeNamedCodec.sizeHint(value, context)
    is CountBadge.Anonymous -> CountBadgeAnonymousCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x00 -> CountBadgeNamedCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x01 -> CountBadgeAnonymousCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      else -> {
        throw DecodeException(fieldPath = "CountBadge.discriminator", bufferPosition = baseOffset, expected = "one of {0x00, 0x01}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
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
