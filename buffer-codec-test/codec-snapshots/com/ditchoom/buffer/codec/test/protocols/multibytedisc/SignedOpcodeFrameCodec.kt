package com.ditchoom.buffer.codec.test.protocols.multibytedisc

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

  override fun wireBytes(`value`: SignedOpcodeFrame, context: EncodeContext): WireByteCount = when (value) {
    is SignedOpcodeFrame.Negative -> SignedOpcodeFrameNegativeCodec.wireBytes(value, context)
    is SignedOpcodeFrame.Positive -> SignedOpcodeFramePositiveCodec.wireBytes(value, context)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: SignedOpcodeFrame, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: SignedOpcodeFrame, context: EncodeContext): Int = when (value) {
    is SignedOpcodeFrame.Negative -> SignedOpcodeFrameNegativeCodec.sizeHint(value, context)
    is SignedOpcodeFrame.Positive -> SignedOpcodeFramePositiveCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 2) return FrameSize.NeedsMoreData
    val __discRawB0 = stream.peekByte(baseOffset + 0).toInt() and 0xFF
    val __discRawB1 = stream.peekByte(baseOffset + 0 + 1).toInt() and 0xFF
    val __discRaw = ((__discRawB0 shl 8) or __discRawB1).toShort()
    val __discriminator = SignedOpcode(__discRaw)
    val __dispatchValue = __discriminator.code
    return when (__dispatchValue) {
      -2 -> SignedOpcodeFrameNegativeCodec.frameSize(stream, baseOffset)
      1 -> SignedOpcodeFramePositiveCodec.frameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "SignedOpcodeFrame.discriminator", bufferPosition = baseOffset, expected = "one of {-2, 1}", actual = """${__dispatchValue}""")
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
