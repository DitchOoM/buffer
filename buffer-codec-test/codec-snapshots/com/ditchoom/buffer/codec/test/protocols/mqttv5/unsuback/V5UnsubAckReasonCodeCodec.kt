package com.ditchoom.buffer.codec.test.protocols.mqttv5.unsuback

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

public object V5UnsubAckReasonCodeCodec : Codec<V5UnsubAckReasonCode> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5UnsubAckReasonCode {
    val discriminatorPosition = buffer.position()
    val __discriminator = V5UnsubAckReasonCodeRawCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5UnsubAckReasonCodeSuccessCodec.decode(buffer, context)
      17 -> V5UnsubAckReasonCodeNoSubscriptionExistedCodec.decode(buffer, context)
      128 -> V5UnsubAckReasonCodeUnspecifiedErrorCodec.decode(buffer, context)
      131 -> V5UnsubAckReasonCodeImplementationSpecificErrorCodec.decode(buffer, context)
      135 -> V5UnsubAckReasonCodeNotAuthorizedCodec.decode(buffer, context)
      143 -> V5UnsubAckReasonCodeTopicFilterInvalidCodec.decode(buffer, context)
      145 -> V5UnsubAckReasonCodePacketIdentifierInUseCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "V5UnsubAckReasonCode.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 17, 128, 131, 135, 143, 145}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5UnsubAckReasonCode,
    context: EncodeContext,
  ) {
    when (value) {
      is V5UnsubAckReasonCode.Success -> V5UnsubAckReasonCodeSuccessCodec.encode(buffer, value, context)
      is V5UnsubAckReasonCode.NoSubscriptionExisted -> V5UnsubAckReasonCodeNoSubscriptionExistedCodec.encode(buffer, value, context)
      is V5UnsubAckReasonCode.UnspecifiedError -> V5UnsubAckReasonCodeUnspecifiedErrorCodec.encode(buffer, value, context)
      is V5UnsubAckReasonCode.ImplementationSpecificError -> V5UnsubAckReasonCodeImplementationSpecificErrorCodec.encode(buffer, value, context)
      is V5UnsubAckReasonCode.NotAuthorized -> V5UnsubAckReasonCodeNotAuthorizedCodec.encode(buffer, value, context)
      is V5UnsubAckReasonCode.TopicFilterInvalid -> V5UnsubAckReasonCodeTopicFilterInvalidCodec.encode(buffer, value, context)
      is V5UnsubAckReasonCode.PacketIdentifierInUse -> V5UnsubAckReasonCodePacketIdentifierInUseCodec.encode(buffer, value, context)
    }
  }

  override fun wireSize(`value`: V5UnsubAckReasonCode, context: EncodeContext): WireSize = when (value) {
    is V5UnsubAckReasonCode.Success -> V5UnsubAckReasonCodeSuccessCodec.wireSize(value, context)
    is V5UnsubAckReasonCode.NoSubscriptionExisted -> V5UnsubAckReasonCodeNoSubscriptionExistedCodec.wireSize(value, context)
    is V5UnsubAckReasonCode.UnspecifiedError -> V5UnsubAckReasonCodeUnspecifiedErrorCodec.wireSize(value, context)
    is V5UnsubAckReasonCode.ImplementationSpecificError -> V5UnsubAckReasonCodeImplementationSpecificErrorCodec.wireSize(value, context)
    is V5UnsubAckReasonCode.NotAuthorized -> V5UnsubAckReasonCodeNotAuthorizedCodec.wireSize(value, context)
    is V5UnsubAckReasonCode.TopicFilterInvalid -> V5UnsubAckReasonCodeTopicFilterInvalidCodec.wireSize(value, context)
    is V5UnsubAckReasonCode.PacketIdentifierInUse -> V5UnsubAckReasonCodePacketIdentifierInUseCodec.wireSize(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = V5UnsubAckReasonCodeRaw(__discRaw)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5UnsubAckReasonCodeSuccessCodec.peekFrameSize(stream, baseOffset)
      17 -> V5UnsubAckReasonCodeNoSubscriptionExistedCodec.peekFrameSize(stream, baseOffset)
      128 -> V5UnsubAckReasonCodeUnspecifiedErrorCodec.peekFrameSize(stream, baseOffset)
      131 -> V5UnsubAckReasonCodeImplementationSpecificErrorCodec.peekFrameSize(stream, baseOffset)
      135 -> V5UnsubAckReasonCodeNotAuthorizedCodec.peekFrameSize(stream, baseOffset)
      143 -> V5UnsubAckReasonCodeTopicFilterInvalidCodec.peekFrameSize(stream, baseOffset)
      145 -> V5UnsubAckReasonCodePacketIdentifierInUseCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "V5UnsubAckReasonCode.discriminator", bufferPosition = baseOffset, expected = "one of {0, 17, 128, 131, 135, 143, 145}", actual = """${__dispatchValue}""")
      }
    }
  }
}
