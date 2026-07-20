package com.ditchoom.buffer.codec.test.protocols.mqttv5.puback

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

public object V5PubAckReasonCodeCodec : Codec<V5PubAckReasonCode> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5PubAckReasonCode {
    val discriminatorPosition = buffer.position()
    val __discriminator = V5PubAckReasonCodeRawCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5PubAckReasonCodeSuccessCodec.decode(buffer, context)
      16 -> V5PubAckReasonCodeNoMatchingSubscribersCodec.decode(buffer, context)
      128 -> V5PubAckReasonCodeUnspecifiedErrorCodec.decode(buffer, context)
      131 -> V5PubAckReasonCodeImplementationSpecificErrorCodec.decode(buffer, context)
      135 -> V5PubAckReasonCodeNotAuthorizedCodec.decode(buffer, context)
      144 -> V5PubAckReasonCodeTopicNameInvalidCodec.decode(buffer, context)
      145 -> V5PubAckReasonCodePacketIdentifierInUseCodec.decode(buffer, context)
      146 -> V5PubAckReasonCodePacketIdentifierNotFoundCodec.decode(buffer, context)
      151 -> V5PubAckReasonCodeQuotaExceededCodec.decode(buffer, context)
      153 -> V5PubAckReasonCodePayloadFormatInvalidCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "V5PubAckReasonCode.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 16, 128, 131, 135, 144, 145, 146, 151, 153}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5PubAckReasonCode,
    context: EncodeContext,
  ) {
    when (value) {
      is V5PubAckReasonCode.Success -> V5PubAckReasonCodeSuccessCodec.encode(buffer, value, context)
      is V5PubAckReasonCode.NoMatchingSubscribers -> V5PubAckReasonCodeNoMatchingSubscribersCodec.encode(buffer, value, context)
      is V5PubAckReasonCode.UnspecifiedError -> V5PubAckReasonCodeUnspecifiedErrorCodec.encode(buffer, value, context)
      is V5PubAckReasonCode.ImplementationSpecificError -> V5PubAckReasonCodeImplementationSpecificErrorCodec.encode(buffer, value, context)
      is V5PubAckReasonCode.NotAuthorized -> V5PubAckReasonCodeNotAuthorizedCodec.encode(buffer, value, context)
      is V5PubAckReasonCode.TopicNameInvalid -> V5PubAckReasonCodeTopicNameInvalidCodec.encode(buffer, value, context)
      is V5PubAckReasonCode.PacketIdentifierInUse -> V5PubAckReasonCodePacketIdentifierInUseCodec.encode(buffer, value, context)
      is V5PubAckReasonCode.PacketIdentifierNotFound -> V5PubAckReasonCodePacketIdentifierNotFoundCodec.encode(buffer, value, context)
      is V5PubAckReasonCode.QuotaExceeded -> V5PubAckReasonCodeQuotaExceededCodec.encode(buffer, value, context)
      is V5PubAckReasonCode.PayloadFormatInvalid -> V5PubAckReasonCodePayloadFormatInvalidCodec.encode(buffer, value, context)
    }
  }

  override fun wireSize(`value`: V5PubAckReasonCode, context: EncodeContext): WireSize = when (value) {
    is V5PubAckReasonCode.Success -> V5PubAckReasonCodeSuccessCodec.wireSize(value, context)
    is V5PubAckReasonCode.NoMatchingSubscribers -> V5PubAckReasonCodeNoMatchingSubscribersCodec.wireSize(value, context)
    is V5PubAckReasonCode.UnspecifiedError -> V5PubAckReasonCodeUnspecifiedErrorCodec.wireSize(value, context)
    is V5PubAckReasonCode.ImplementationSpecificError -> V5PubAckReasonCodeImplementationSpecificErrorCodec.wireSize(value, context)
    is V5PubAckReasonCode.NotAuthorized -> V5PubAckReasonCodeNotAuthorizedCodec.wireSize(value, context)
    is V5PubAckReasonCode.TopicNameInvalid -> V5PubAckReasonCodeTopicNameInvalidCodec.wireSize(value, context)
    is V5PubAckReasonCode.PacketIdentifierInUse -> V5PubAckReasonCodePacketIdentifierInUseCodec.wireSize(value, context)
    is V5PubAckReasonCode.PacketIdentifierNotFound -> V5PubAckReasonCodePacketIdentifierNotFoundCodec.wireSize(value, context)
    is V5PubAckReasonCode.QuotaExceeded -> V5PubAckReasonCodeQuotaExceededCodec.wireSize(value, context)
    is V5PubAckReasonCode.PayloadFormatInvalid -> V5PubAckReasonCodePayloadFormatInvalidCodec.wireSize(value, context)
  }

  override fun sizeHint(`value`: V5PubAckReasonCode, context: EncodeContext): Int = when (value) {
    is V5PubAckReasonCode.Success -> V5PubAckReasonCodeSuccessCodec.sizeHint(value, context)
    is V5PubAckReasonCode.NoMatchingSubscribers -> V5PubAckReasonCodeNoMatchingSubscribersCodec.sizeHint(value, context)
    is V5PubAckReasonCode.UnspecifiedError -> V5PubAckReasonCodeUnspecifiedErrorCodec.sizeHint(value, context)
    is V5PubAckReasonCode.ImplementationSpecificError -> V5PubAckReasonCodeImplementationSpecificErrorCodec.sizeHint(value, context)
    is V5PubAckReasonCode.NotAuthorized -> V5PubAckReasonCodeNotAuthorizedCodec.sizeHint(value, context)
    is V5PubAckReasonCode.TopicNameInvalid -> V5PubAckReasonCodeTopicNameInvalidCodec.sizeHint(value, context)
    is V5PubAckReasonCode.PacketIdentifierInUse -> V5PubAckReasonCodePacketIdentifierInUseCodec.sizeHint(value, context)
    is V5PubAckReasonCode.PacketIdentifierNotFound -> V5PubAckReasonCodePacketIdentifierNotFoundCodec.sizeHint(value, context)
    is V5PubAckReasonCode.QuotaExceeded -> V5PubAckReasonCodeQuotaExceededCodec.sizeHint(value, context)
    is V5PubAckReasonCode.PayloadFormatInvalid -> V5PubAckReasonCodePayloadFormatInvalidCodec.sizeHint(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = V5PubAckReasonCodeRaw(__discRaw)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5PubAckReasonCodeSuccessCodec.peekFrameSize(stream, baseOffset)
      16 -> V5PubAckReasonCodeNoMatchingSubscribersCodec.peekFrameSize(stream, baseOffset)
      128 -> V5PubAckReasonCodeUnspecifiedErrorCodec.peekFrameSize(stream, baseOffset)
      131 -> V5PubAckReasonCodeImplementationSpecificErrorCodec.peekFrameSize(stream, baseOffset)
      135 -> V5PubAckReasonCodeNotAuthorizedCodec.peekFrameSize(stream, baseOffset)
      144 -> V5PubAckReasonCodeTopicNameInvalidCodec.peekFrameSize(stream, baseOffset)
      145 -> V5PubAckReasonCodePacketIdentifierInUseCodec.peekFrameSize(stream, baseOffset)
      146 -> V5PubAckReasonCodePacketIdentifierNotFoundCodec.peekFrameSize(stream, baseOffset)
      151 -> V5PubAckReasonCodeQuotaExceededCodec.peekFrameSize(stream, baseOffset)
      153 -> V5PubAckReasonCodePayloadFormatInvalidCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "V5PubAckReasonCode.discriminator", bufferPosition = baseOffset, expected = "one of {0, 16, 128, 131, 135, 144, 145, 146, 151, 153}", actual = """${__dispatchValue}""")
      }
    }
  }
}
