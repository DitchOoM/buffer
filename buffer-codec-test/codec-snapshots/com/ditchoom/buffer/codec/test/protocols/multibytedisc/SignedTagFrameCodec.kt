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

public object SignedTagFrameCodec : Codec<SignedTagFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedTagFrame {
    val discriminatorPosition = buffer.position()
    val __discriminator = SignedTagCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.tag
    return when (__dispatchValue) {
      -1 -> SignedTagFrameAlphaCodec.decode(buffer, context)
      7 -> SignedTagFrameBetaCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "SignedTagFrame.discriminator", bufferPosition = discriminatorPosition, expected = "one of {-1, 7}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedTagFrame,
    context: EncodeContext,
  ) {
    when (value) {
      is SignedTagFrame.Alpha -> SignedTagFrameAlphaCodec.encode(buffer, value, context)
      is SignedTagFrame.Beta -> SignedTagFrameBetaCodec.encode(buffer, value, context)
    }
  }

  override fun wireSize(`value`: SignedTagFrame, context: EncodeContext): WireSize = when (value) {
    is SignedTagFrame.Alpha -> SignedTagFrameAlphaCodec.wireSize(value, context)
    is SignedTagFrame.Beta -> SignedTagFrameBetaCodec.wireSize(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 4) return PeekResult.NeedsMoreData
    val __discRawB0 = stream.peekByte(baseOffset + 0).toInt() and 0xFF
    val __discRawB1 = stream.peekByte(baseOffset + 0 + 1).toInt() and 0xFF
    val __discRawB2 = stream.peekByte(baseOffset + 0 + 2).toInt() and 0xFF
    val __discRawB3 = stream.peekByte(baseOffset + 0 + 3).toInt() and 0xFF
    val __discRaw = (__discRawB0 or (__discRawB1 shl 8) or (__discRawB2 shl 16) or (__discRawB3 shl 24))
    val __discriminator = SignedTag(__discRaw)
    val __dispatchValue = __discriminator.tag
    return when (__dispatchValue) {
      -1 -> SignedTagFrameAlphaCodec.peekFrameSize(stream, baseOffset)
      7 -> SignedTagFrameBetaCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "SignedTagFrame.discriminator", bufferPosition = baseOffset, expected = "one of {-1, 7}", actual = """${__dispatchValue}""")
      }
    }
  }
}
