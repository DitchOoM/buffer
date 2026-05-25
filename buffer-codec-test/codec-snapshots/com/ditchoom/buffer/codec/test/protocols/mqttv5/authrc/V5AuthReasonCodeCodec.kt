package com.ditchoom.buffer.codec.test.protocols.mqttv5.authrc

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

public object V5AuthReasonCodeCodec : Codec<V5AuthReasonCode> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5AuthReasonCode {
    val discriminatorPosition = buffer.position()
    val __discriminator = V5AuthReasonCodeRawCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5AuthReasonCodeSuccessCodec.decode(buffer, context)
      24 -> V5AuthReasonCodeContinueAuthenticationCodec.decode(buffer, context)
      25 -> V5AuthReasonCodeReAuthenticateCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "V5AuthReasonCode.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 24, 25}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5AuthReasonCode,
    context: EncodeContext,
  ) {
    when (value) {
      is V5AuthReasonCode.Success -> V5AuthReasonCodeSuccessCodec.encode(buffer, value, context)
      is V5AuthReasonCode.ContinueAuthentication -> V5AuthReasonCodeContinueAuthenticationCodec.encode(buffer, value, context)
      is V5AuthReasonCode.ReAuthenticate -> V5AuthReasonCodeReAuthenticateCodec.encode(buffer, value, context)
    }
  }

  override fun wireSize(`value`: V5AuthReasonCode, context: EncodeContext): WireSize = when (value) {
    is V5AuthReasonCode.Success -> V5AuthReasonCodeSuccessCodec.wireSize(value, context)
    is V5AuthReasonCode.ContinueAuthentication -> V5AuthReasonCodeContinueAuthenticationCodec.wireSize(value, context)
    is V5AuthReasonCode.ReAuthenticate -> V5AuthReasonCodeReAuthenticateCodec.wireSize(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = V5AuthReasonCodeRaw(__discRaw)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5AuthReasonCodeSuccessCodec.peekFrameSize(stream, baseOffset)
      24 -> V5AuthReasonCodeContinueAuthenticationCodec.peekFrameSize(stream, baseOffset)
      25 -> V5AuthReasonCodeReAuthenticateCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "V5AuthReasonCode.discriminator", bufferPosition = baseOffset, expected = "one of {0, 24, 25}", actual = """${__dispatchValue}""")
      }
    }
  }
}
