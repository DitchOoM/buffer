package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public class Http2FrameCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : Codec<Http2Frame<P>> {
  private val dataCodec: Http2FrameDataCodec<P> = Http2FrameDataCodec(payloadCodec)

  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http2Frame<P> {
    val discriminatorPosition = buffer.position()
    val __discriminator = Http2LengthAndTypeCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.type
    return when (__dispatchValue) {
      0 -> dataCodec.decode(buffer, context)
      4 -> Http2FrameSettingsCodec.decode(buffer, context)
      6 -> Http2FramePingCodec.decode(buffer, context)
      8 -> Http2FrameWindowUpdateCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "Http2Frame.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 4, 6, 8}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http2Frame<P>,
    context: EncodeContext,
  ) {
    @Suppress("UNCHECKED_CAST")
    when (value) {
      is Http2Frame.Data<*> -> dataCodec.encode(buffer, value as Http2Frame.Data<P>, context)
      is Http2Frame.Settings -> Http2FrameSettingsCodec.encode(buffer, value, context)
      is Http2Frame.Ping -> Http2FramePingCodec.encode(buffer, value, context)
      is Http2Frame.WindowUpdate -> Http2FrameWindowUpdateCodec.encode(buffer, value, context)
    }
  }

  override fun wireSize(`value`: Http2Frame<P>, context: EncodeContext): WireSize {
    @Suppress("UNCHECKED_CAST")
    return when (value) {
      is Http2Frame.Data<*> -> dataCodec.wireSize(value as Http2Frame.Data<P>, context)
      is Http2Frame.Settings -> Http2FrameSettingsCodec.wireSize(value, context)
      is Http2Frame.Ping -> Http2FramePingCodec.wireSize(value, context)
      is Http2Frame.WindowUpdate -> Http2FrameWindowUpdateCodec.wireSize(value, context)
    }
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 4) return PeekResult.NeedsMoreData
    val __discRawB0 = stream.peekByte(baseOffset + 0).toInt() and 0xFF
    val __discRawB1 = stream.peekByte(baseOffset + 0 + 1).toInt() and 0xFF
    val __discRawB2 = stream.peekByte(baseOffset + 0 + 2).toInt() and 0xFF
    val __discRawB3 = stream.peekByte(baseOffset + 0 + 3).toInt() and 0xFF
    val __discRaw = ((__discRawB0 shl 24) or (__discRawB1 shl 16) or (__discRawB2 shl 8) or __discRawB3).toUInt()
    val __discriminator = Http2LengthAndType(__discRaw)
    val __dispatchValue = __discriminator.type
    return when (__dispatchValue) {
      0 -> dataCodec.peekFrameSize(stream, baseOffset)
      4 -> Http2FrameSettingsCodec.peekFrameSize(stream, baseOffset)
      6 -> Http2FramePingCodec.peekFrameSize(stream, baseOffset)
      8 -> Http2FrameWindowUpdateCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "Http2Frame.discriminator", bufferPosition = baseOffset, expected = "one of {0, 4, 6, 8}", actual = """${__dispatchValue}""")
      }
    }
  }

  public companion object {
    public fun <P : Payload> decodeAggregating(
      buffer: ReadBuffer,
      context: DecodeContext,
      onData: (Http2FrameDataCodec.Partial<P>) -> Http2Frame.Data<P> = { _ -> throw DecodeException(fieldPath = "Http2Frame.Data.handler", bufferPosition = -1, expected = "consumer-supplied Data handler", actual = "no handler supplied") },
    ): Http2Frame<P> {
      val discriminatorPosition = buffer.position()
      val __discriminator = Http2LengthAndTypeCodec.decode(buffer, context)
      buffer.position(discriminatorPosition)
      val __dispatchValue = __discriminator.type
      return when (__dispatchValue) {
        0 -> onData(Http2FrameDataCodec.partial<P>(buffer, context))
        4 -> Http2FrameSettingsCodec.decode(buffer, context)
        6 -> Http2FramePingCodec.decode(buffer, context)
        8 -> Http2FrameWindowUpdateCodec.decode(buffer, context)
        else -> {
          throw DecodeException(fieldPath = "Http2Frame.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 4, 6, 8}", actual = """${__dispatchValue}""")
        }
      }
    }
  }
}
