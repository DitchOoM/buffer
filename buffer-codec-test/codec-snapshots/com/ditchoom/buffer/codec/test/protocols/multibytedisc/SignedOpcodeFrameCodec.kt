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

public object SignedOpcodeFrameCodec : Codec<SignedOpcodeFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedOpcodeFrame {
    val discriminatorPosition = buffer.position()
    val __discriminator = SignedOpcodeCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.code
    return when (__dispatchValue) {
      -2 -> SignedOpcodeFrameNegativeCodec.decode(buffer, context)
      1 -> SignedOpcodeFramePositiveCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "SignedOpcodeFrame.discriminator", bufferPosition = discriminatorPosition, expected = "one of {-2, 1}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedOpcodeFrame,
    context: EncodeContext,
  ) {
    when (value) {
      is SignedOpcodeFrame.Negative -> SignedOpcodeFrameNegativeCodec.encode(buffer, value, context)
      is SignedOpcodeFrame.Positive -> SignedOpcodeFramePositiveCodec.encode(buffer, value, context)
    }
  }

  override fun wireSize(`value`: SignedOpcodeFrame, context: EncodeContext): WireSize = when (value) {
    is SignedOpcodeFrame.Negative -> SignedOpcodeFrameNegativeCodec.wireSize(value, context)
    is SignedOpcodeFrame.Positive -> SignedOpcodeFramePositiveCodec.wireSize(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 2) return PeekResult.NeedsMoreData
    val __discRawB0 = stream.peekByte(baseOffset + 0).toInt() and 0xFF
    val __discRawB1 = stream.peekByte(baseOffset + 0 + 1).toInt() and 0xFF
    val __discRaw = ((__discRawB0 shl 8) or __discRawB1).toShort()
    val __discriminator = SignedOpcode(__discRaw)
    val __dispatchValue = __discriminator.code
    return when (__dispatchValue) {
      -2 -> SignedOpcodeFrameNegativeCodec.peekFrameSize(stream, baseOffset)
      1 -> SignedOpcodeFramePositiveCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "SignedOpcodeFrame.discriminator", bufferPosition = baseOffset, expected = "one of {-2, 1}", actual = """${__dispatchValue}""")
      }
    }
  }
}
