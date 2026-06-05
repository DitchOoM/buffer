package com.ditchoom.buffer.codec.test.protocols.http3

import com.ditchoom.buffer.PlatformBuffer
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

public object Http3FrameCodec : Codec<Http3Frame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http3Frame {
    val discriminatorPosition = buffer.position()
    val __discriminator = Http3FrameTypeCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.type
    return when (__dispatchValue) {
      0 -> Http3FrameDataCodec.decode(buffer, context)
      1 -> Http3FrameHeadersCodec.decode(buffer, context)
      4 -> Http3FrameSettingsCodec.decode(buffer, context)
      64 -> Http3FrameExtensionCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "Http3Frame.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 1, 4, 64}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http3Frame,
    context: EncodeContext,
  ) {
    when (value) {
      is Http3Frame.Data -> Http3FrameDataCodec.encode(buffer, value, context)
      is Http3Frame.Headers -> Http3FrameHeadersCodec.encode(buffer, value, context)
      is Http3Frame.Settings -> Http3FrameSettingsCodec.encode(buffer, value, context)
      is Http3Frame.Extension -> Http3FrameExtensionCodec.encode(buffer, value, context)
    }
  }

  override fun wireSize(`value`: Http3Frame, context: EncodeContext): WireSize = when (value) {
    is Http3Frame.Data -> Http3FrameDataCodec.wireSize(value, context)
    is Http3Frame.Headers -> Http3FrameHeadersCodec.wireSize(value, context)
    is Http3Frame.Settings -> Http3FrameSettingsCodec.wireSize(value, context)
    is Http3Frame.Extension -> Http3FrameExtensionCodec.wireSize(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    val __discFrame = Http3FrameTypeCodec.peekFrameSize(stream, baseOffset)
    if (__discFrame !is PeekResult.Complete) {
      return __discFrame
    }
    val __discView = stream.peekBuffer(baseOffset, __discFrame.bytes) ?: return PeekResult.NeedsMoreData
    val __dispatchValue = try {
      val __discriminator = Http3FrameTypeCodec.decode(__discView, DecodeContext.Empty)
      __discriminator.type
    } finally {
      (__discView as? PlatformBuffer)?.freeNativeMemory()
    }
    return when (__dispatchValue) {
      0 -> Http3FrameDataCodec.peekFrameSize(stream, baseOffset)
      1 -> Http3FrameHeadersCodec.peekFrameSize(stream, baseOffset)
      4 -> Http3FrameSettingsCodec.peekFrameSize(stream, baseOffset)
      64 -> Http3FrameExtensionCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "Http3Frame.discriminator", bufferPosition = baseOffset, expected = "one of {0, 1, 4, 64}", actual = """${__dispatchValue}""")
      }
    }
  }
}
