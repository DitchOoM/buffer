package com.ditchoom.buffer.codec.test.protocols.quic

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

  override fun wireSize(`value`: QuicPacketHeader, context: EncodeContext): WireSize = when (value) {
    is QuicPacketHeader.ShortHeader -> QuicPacketHeaderShortHeaderCodec.wireSize(value, context)
    is QuicPacketHeader.LongHeader -> QuicPacketHeaderLongHeaderCodec.wireSize(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = QuicHeaderByte(__discRaw)
    val __dispatchValue = if (__discriminator.isLongHeader) 1 else 0
    return when (__dispatchValue) {
      0 -> QuicPacketHeaderShortHeaderCodec.peekFrameSize(stream, baseOffset)
      1 -> QuicPacketHeaderLongHeaderCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "QuicPacketHeader.discriminator", bufferPosition = baseOffset, expected = "one of {0, 1}", actual = """${__dispatchValue}""")
      }
    }
  }
}
