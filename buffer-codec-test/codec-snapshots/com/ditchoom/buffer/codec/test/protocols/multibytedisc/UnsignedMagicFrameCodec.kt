package com.ditchoom.buffer.codec.test.protocols.multibytedisc

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

public object UnsignedMagicFrameCodec : Codec<UnsignedMagicFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): UnsignedMagicFrame {
    val discriminatorPosition = buffer.position()
    val __discriminator = UnsignedMagicCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.tag
    return when (__dispatchValue) {
      17 -> UnsignedMagicFrameFirstCodec.decode(buffer, context)
      34 -> UnsignedMagicFrameSecondCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "UnsignedMagicFrame.discriminator", bufferPosition = discriminatorPosition, expected = "one of {17, 34}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: UnsignedMagicFrame,
    context: EncodeContext,
  ) {
    when (value) {
      is UnsignedMagicFrame.First -> UnsignedMagicFrameFirstCodec.encode(buffer, value, context)
      is UnsignedMagicFrame.Second -> UnsignedMagicFrameSecondCodec.encode(buffer, value, context)
    }
  }

  override fun wireSize(`value`: UnsignedMagicFrame, context: EncodeContext): WireSize = when (value) {
    is UnsignedMagicFrame.First -> UnsignedMagicFrameFirstCodec.wireSize(value, context)
    is UnsignedMagicFrame.Second -> UnsignedMagicFrameSecondCodec.wireSize(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 8) return PeekResult.NeedsMoreData
    val __discRawB0 = stream.peekByte(baseOffset + 0).toLong() and 0xFFL
    val __discRawB1 = stream.peekByte(baseOffset + 0 + 1).toLong() and 0xFFL
    val __discRawB2 = stream.peekByte(baseOffset + 0 + 2).toLong() and 0xFFL
    val __discRawB3 = stream.peekByte(baseOffset + 0 + 3).toLong() and 0xFFL
    val __discRawB4 = stream.peekByte(baseOffset + 0 + 4).toLong() and 0xFFL
    val __discRawB5 = stream.peekByte(baseOffset + 0 + 5).toLong() and 0xFFL
    val __discRawB6 = stream.peekByte(baseOffset + 0 + 6).toLong() and 0xFFL
    val __discRawB7 = stream.peekByte(baseOffset + 0 + 7).toLong() and 0xFFL
    val __discRaw = (__discRawB0 or (__discRawB1 shl 8) or (__discRawB2 shl 16) or (__discRawB3 shl 24) or (__discRawB4 shl 32) or (__discRawB5 shl 40) or (__discRawB6 shl 48) or (__discRawB7 shl 56)).toULong()
    val __discriminator = UnsignedMagic(__discRaw)
    val __dispatchValue = __discriminator.tag
    return when (__dispatchValue) {
      17 -> UnsignedMagicFrameFirstCodec.peekFrameSize(stream, baseOffset)
      34 -> UnsignedMagicFrameSecondCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "UnsignedMagicFrame.discriminator", bufferPosition = baseOffset, expected = "one of {17, 34}", actual = """${__dispatchValue}""")
      }
    }
  }
}
