package com.ditchoom.buffer.codec.test.protocols.mqttv5.suback

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

public object V5SubAckReasonCodeCodec : Codec<V5SubAckReasonCode> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5SubAckReasonCode {
    val discriminatorPosition = buffer.position()
    val __discriminator = V5SubAckReasonCodeRawCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5SubAckReasonCodeGrantedQoS0Codec.decode(buffer, context)
      1 -> V5SubAckReasonCodeGrantedQoS1Codec.decode(buffer, context)
      2 -> V5SubAckReasonCodeGrantedQoS2Codec.decode(buffer, context)
      128 -> V5SubAckReasonCodeUnspecifiedErrorCodec.decode(buffer, context)
      131 -> V5SubAckReasonCodeImplementationSpecificErrorCodec.decode(buffer, context)
      135 -> V5SubAckReasonCodeNotAuthorizedCodec.decode(buffer, context)
      143 -> V5SubAckReasonCodeTopicFilterInvalidCodec.decode(buffer, context)
      145 -> V5SubAckReasonCodePacketIdentifierInUseCodec.decode(buffer, context)
      151 -> V5SubAckReasonCodeQuotaExceededCodec.decode(buffer, context)
      158 -> V5SubAckReasonCodeSharedSubscriptionsNotSupportedCodec.decode(buffer, context)
      161 -> V5SubAckReasonCodeSubscriptionIdentifiersNotSupportedCodec.decode(buffer, context)
      162 -> V5SubAckReasonCodeWildcardSubscriptionsNotSupportedCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "V5SubAckReasonCode.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 1, 2, 128, 131, 135, 143, 145, 151, 158, 161, 162}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5SubAckReasonCode,
    context: EncodeContext,
  ) {
    when (value) {
      is V5SubAckReasonCode.GrantedQoS0 -> V5SubAckReasonCodeGrantedQoS0Codec.encode(buffer, value, context)
      is V5SubAckReasonCode.GrantedQoS1 -> V5SubAckReasonCodeGrantedQoS1Codec.encode(buffer, value, context)
      is V5SubAckReasonCode.GrantedQoS2 -> V5SubAckReasonCodeGrantedQoS2Codec.encode(buffer, value, context)
      is V5SubAckReasonCode.UnspecifiedError -> V5SubAckReasonCodeUnspecifiedErrorCodec.encode(buffer, value, context)
      is V5SubAckReasonCode.ImplementationSpecificError -> V5SubAckReasonCodeImplementationSpecificErrorCodec.encode(buffer, value, context)
      is V5SubAckReasonCode.NotAuthorized -> V5SubAckReasonCodeNotAuthorizedCodec.encode(buffer, value, context)
      is V5SubAckReasonCode.TopicFilterInvalid -> V5SubAckReasonCodeTopicFilterInvalidCodec.encode(buffer, value, context)
      is V5SubAckReasonCode.PacketIdentifierInUse -> V5SubAckReasonCodePacketIdentifierInUseCodec.encode(buffer, value, context)
      is V5SubAckReasonCode.QuotaExceeded -> V5SubAckReasonCodeQuotaExceededCodec.encode(buffer, value, context)
      is V5SubAckReasonCode.SharedSubscriptionsNotSupported -> V5SubAckReasonCodeSharedSubscriptionsNotSupportedCodec.encode(buffer, value, context)
      is V5SubAckReasonCode.SubscriptionIdentifiersNotSupported -> V5SubAckReasonCodeSubscriptionIdentifiersNotSupportedCodec.encode(buffer, value, context)
      is V5SubAckReasonCode.WildcardSubscriptionsNotSupported -> V5SubAckReasonCodeWildcardSubscriptionsNotSupportedCodec.encode(buffer, value, context)
    }
  }

  override fun wireSize(`value`: V5SubAckReasonCode, context: EncodeContext): WireSize = when (value) {
    is V5SubAckReasonCode.GrantedQoS0 -> V5SubAckReasonCodeGrantedQoS0Codec.wireSize(value, context)
    is V5SubAckReasonCode.GrantedQoS1 -> V5SubAckReasonCodeGrantedQoS1Codec.wireSize(value, context)
    is V5SubAckReasonCode.GrantedQoS2 -> V5SubAckReasonCodeGrantedQoS2Codec.wireSize(value, context)
    is V5SubAckReasonCode.UnspecifiedError -> V5SubAckReasonCodeUnspecifiedErrorCodec.wireSize(value, context)
    is V5SubAckReasonCode.ImplementationSpecificError -> V5SubAckReasonCodeImplementationSpecificErrorCodec.wireSize(value, context)
    is V5SubAckReasonCode.NotAuthorized -> V5SubAckReasonCodeNotAuthorizedCodec.wireSize(value, context)
    is V5SubAckReasonCode.TopicFilterInvalid -> V5SubAckReasonCodeTopicFilterInvalidCodec.wireSize(value, context)
    is V5SubAckReasonCode.PacketIdentifierInUse -> V5SubAckReasonCodePacketIdentifierInUseCodec.wireSize(value, context)
    is V5SubAckReasonCode.QuotaExceeded -> V5SubAckReasonCodeQuotaExceededCodec.wireSize(value, context)
    is V5SubAckReasonCode.SharedSubscriptionsNotSupported -> V5SubAckReasonCodeSharedSubscriptionsNotSupportedCodec.wireSize(value, context)
    is V5SubAckReasonCode.SubscriptionIdentifiersNotSupported -> V5SubAckReasonCodeSubscriptionIdentifiersNotSupportedCodec.wireSize(value, context)
    is V5SubAckReasonCode.WildcardSubscriptionsNotSupported -> V5SubAckReasonCodeWildcardSubscriptionsNotSupportedCodec.wireSize(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = V5SubAckReasonCodeRaw(__discRaw)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5SubAckReasonCodeGrantedQoS0Codec.peekFrameSize(stream, baseOffset)
      1 -> V5SubAckReasonCodeGrantedQoS1Codec.peekFrameSize(stream, baseOffset)
      2 -> V5SubAckReasonCodeGrantedQoS2Codec.peekFrameSize(stream, baseOffset)
      128 -> V5SubAckReasonCodeUnspecifiedErrorCodec.peekFrameSize(stream, baseOffset)
      131 -> V5SubAckReasonCodeImplementationSpecificErrorCodec.peekFrameSize(stream, baseOffset)
      135 -> V5SubAckReasonCodeNotAuthorizedCodec.peekFrameSize(stream, baseOffset)
      143 -> V5SubAckReasonCodeTopicFilterInvalidCodec.peekFrameSize(stream, baseOffset)
      145 -> V5SubAckReasonCodePacketIdentifierInUseCodec.peekFrameSize(stream, baseOffset)
      151 -> V5SubAckReasonCodeQuotaExceededCodec.peekFrameSize(stream, baseOffset)
      158 -> V5SubAckReasonCodeSharedSubscriptionsNotSupportedCodec.peekFrameSize(stream, baseOffset)
      161 -> V5SubAckReasonCodeSubscriptionIdentifiersNotSupportedCodec.peekFrameSize(stream, baseOffset)
      162 -> V5SubAckReasonCodeWildcardSubscriptionsNotSupportedCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "V5SubAckReasonCode.discriminator", bufferPosition = baseOffset, expected = "one of {0, 1, 2, 128, 131, 135, 143, 145, 151, 158, 161, 162}", actual = """${__dispatchValue}""")
      }
    }
  }
}
