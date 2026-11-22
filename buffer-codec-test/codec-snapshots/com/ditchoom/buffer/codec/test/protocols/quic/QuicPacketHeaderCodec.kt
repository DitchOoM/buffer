package com.ditchoom.buffer.codec.test.protocols.quic

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

public object QuicPacketHeaderCodec : Codec<QuicPacketHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): QuicPacketHeader {
    val discriminatorPosition = buffer.position()
    val __discriminator = QuicHeaderByteCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = if (__discriminator.isLongHeader) 1 else 0
    return when (__dispatchValue) {
      0 -> QuicPacketHeaderShortHeaderCodec.decode(buffer, context)
      1 -> QuicPacketHeaderLongHeaderCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "QuicPacketHeader.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 1}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: QuicPacketHeader,
    context: EncodeContext,
  ) {
    when (value) {
      is QuicPacketHeader.ShortHeader -> QuicPacketHeaderShortHeaderCodec.encode(buffer, value, context)
      is QuicPacketHeader.LongHeader -> QuicPacketHeaderLongHeaderCodec.encode(buffer, value, context)
    }
  }

  override fun wireBytes(`value`: QuicPacketHeader, context: EncodeContext): WireByteCount = when (value) {
    is QuicPacketHeader.ShortHeader -> QuicPacketHeaderShortHeaderCodec.wireBytes(value, context)
    is QuicPacketHeader.LongHeader -> QuicPacketHeaderLongHeaderCodec.wireBytes(value, context)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: QuicPacketHeader, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: QuicPacketHeader, context: EncodeContext): Int = when (value) {
    is QuicPacketHeader.ShortHeader -> QuicPacketHeaderShortHeaderCodec.sizeHint(value, context)
    is QuicPacketHeader.LongHeader -> QuicPacketHeaderLongHeaderCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = QuicHeaderByte(__discRaw)
    val __dispatchValue = if (__discriminator.isLongHeader) 1 else 0
    return when (__dispatchValue) {
      0 -> QuicPacketHeaderShortHeaderCodec.frameSize(stream, baseOffset)
      1 -> QuicPacketHeaderLongHeaderCodec.frameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "QuicPacketHeader.discriminator", bufferPosition = baseOffset, expected = "one of {0, 1}", actual = """${__dispatchValue}""")
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
