package com.ditchoom.buffer.codec.test.protocols.http3

import com.ditchoom.buffer.PlatformBuffer
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
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: Http3Frame, context: EncodeContext): WireByteCount = when (value) {
    is Http3Frame.Data -> Http3FrameDataCodec.wireBytes(value, context)
    is Http3Frame.Headers -> Http3FrameHeadersCodec.wireBytes(value, context)
    is Http3Frame.Settings -> Http3FrameSettingsCodec.wireBytes(value, context)
    is Http3Frame.Extension -> Http3FrameExtensionCodec.wireBytes(value, context)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: Http3Frame, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: Http3Frame, context: EncodeContext): Int = when (value) {
    is Http3Frame.Data -> Http3FrameDataCodec.sizeHint(value, context)
    is Http3Frame.Headers -> Http3FrameHeadersCodec.sizeHint(value, context)
    is Http3Frame.Settings -> Http3FrameSettingsCodec.sizeHint(value, context)
    is Http3Frame.Extension -> Http3FrameExtensionCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    val __discWidth = Http3FrameTypeCodec.frameSize(stream, baseOffset + 0).fold(declared = { __h, __b -> __h + __b }, needsMoreData = { return FrameSize.NeedsMoreData }, unframed = { return FrameSize.Unframed })
    if (__discWidth > stream.available() - baseOffset - 0) return FrameSize.NeedsMoreData
    val __dispatchValue = QuicVarintCodec.peekInteger(stream, baseOffset).fold(
      value = { __raw -> Http3FrameType(__raw.toULong()).type },
      needsMoreData = { return FrameSize.NeedsMoreData },
      notRepresentable = {
        val __discView = stream.peekBuffer(baseOffset, __discWidth.toInt()) ?: return FrameSize.NeedsMoreData
        try {
          Http3FrameTypeCodec.decode(__discView, DecodeContext.Empty).type
        } finally {
          (__discView as? PlatformBuffer)?.freeNativeMemory()
        }
      },
    )
    return when (__dispatchValue) {
      0 -> Http3FrameDataCodec.frameSize(stream, baseOffset)
      1 -> Http3FrameHeadersCodec.frameSize(stream, baseOffset)
      4 -> Http3FrameSettingsCodec.frameSize(stream, baseOffset)
      64 -> Http3FrameExtensionCodec.frameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "Http3Frame.discriminator", bufferPosition = baseOffset, expected = "one of {0, 1, 4, 64}", actual = """${__dispatchValue}""")
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
