package com.ditchoom.buffer.codec.test.protocols.mqttv5.suback

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

  override fun wireBytes(`value`: V5SubAckReasonCode, context: EncodeContext): WireByteCount = when (value) {
    is V5SubAckReasonCode.GrantedQoS0 -> V5SubAckReasonCodeGrantedQoS0Codec.wireBytes(value, context)
    is V5SubAckReasonCode.GrantedQoS1 -> V5SubAckReasonCodeGrantedQoS1Codec.wireBytes(value, context)
    is V5SubAckReasonCode.GrantedQoS2 -> V5SubAckReasonCodeGrantedQoS2Codec.wireBytes(value, context)
    is V5SubAckReasonCode.UnspecifiedError -> V5SubAckReasonCodeUnspecifiedErrorCodec.wireBytes(value, context)
    is V5SubAckReasonCode.ImplementationSpecificError -> V5SubAckReasonCodeImplementationSpecificErrorCodec.wireBytes(value, context)
    is V5SubAckReasonCode.NotAuthorized -> V5SubAckReasonCodeNotAuthorizedCodec.wireBytes(value, context)
    is V5SubAckReasonCode.TopicFilterInvalid -> V5SubAckReasonCodeTopicFilterInvalidCodec.wireBytes(value, context)
    is V5SubAckReasonCode.PacketIdentifierInUse -> V5SubAckReasonCodePacketIdentifierInUseCodec.wireBytes(value, context)
    is V5SubAckReasonCode.QuotaExceeded -> V5SubAckReasonCodeQuotaExceededCodec.wireBytes(value, context)
    is V5SubAckReasonCode.SharedSubscriptionsNotSupported -> V5SubAckReasonCodeSharedSubscriptionsNotSupportedCodec.wireBytes(value, context)
    is V5SubAckReasonCode.SubscriptionIdentifiersNotSupported -> V5SubAckReasonCodeSubscriptionIdentifiersNotSupportedCodec.wireBytes(value, context)
    is V5SubAckReasonCode.WildcardSubscriptionsNotSupported -> V5SubAckReasonCodeWildcardSubscriptionsNotSupportedCodec.wireBytes(value, context)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: V5SubAckReasonCode, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: V5SubAckReasonCode, context: EncodeContext): Int = when (value) {
    is V5SubAckReasonCode.GrantedQoS0 -> V5SubAckReasonCodeGrantedQoS0Codec.sizeHint(value, context)
    is V5SubAckReasonCode.GrantedQoS1 -> V5SubAckReasonCodeGrantedQoS1Codec.sizeHint(value, context)
    is V5SubAckReasonCode.GrantedQoS2 -> V5SubAckReasonCodeGrantedQoS2Codec.sizeHint(value, context)
    is V5SubAckReasonCode.UnspecifiedError -> V5SubAckReasonCodeUnspecifiedErrorCodec.sizeHint(value, context)
    is V5SubAckReasonCode.ImplementationSpecificError -> V5SubAckReasonCodeImplementationSpecificErrorCodec.sizeHint(value, context)
    is V5SubAckReasonCode.NotAuthorized -> V5SubAckReasonCodeNotAuthorizedCodec.sizeHint(value, context)
    is V5SubAckReasonCode.TopicFilterInvalid -> V5SubAckReasonCodeTopicFilterInvalidCodec.sizeHint(value, context)
    is V5SubAckReasonCode.PacketIdentifierInUse -> V5SubAckReasonCodePacketIdentifierInUseCodec.sizeHint(value, context)
    is V5SubAckReasonCode.QuotaExceeded -> V5SubAckReasonCodeQuotaExceededCodec.sizeHint(value, context)
    is V5SubAckReasonCode.SharedSubscriptionsNotSupported -> V5SubAckReasonCodeSharedSubscriptionsNotSupportedCodec.sizeHint(value, context)
    is V5SubAckReasonCode.SubscriptionIdentifiersNotSupported -> V5SubAckReasonCodeSubscriptionIdentifiersNotSupportedCodec.sizeHint(value, context)
    is V5SubAckReasonCode.WildcardSubscriptionsNotSupported -> V5SubAckReasonCodeWildcardSubscriptionsNotSupportedCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = V5SubAckReasonCodeRaw(__discRaw)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5SubAckReasonCodeGrantedQoS0Codec.frameSize(stream, baseOffset)
      1 -> V5SubAckReasonCodeGrantedQoS1Codec.frameSize(stream, baseOffset)
      2 -> V5SubAckReasonCodeGrantedQoS2Codec.frameSize(stream, baseOffset)
      128 -> V5SubAckReasonCodeUnspecifiedErrorCodec.frameSize(stream, baseOffset)
      131 -> V5SubAckReasonCodeImplementationSpecificErrorCodec.frameSize(stream, baseOffset)
      135 -> V5SubAckReasonCodeNotAuthorizedCodec.frameSize(stream, baseOffset)
      143 -> V5SubAckReasonCodeTopicFilterInvalidCodec.frameSize(stream, baseOffset)
      145 -> V5SubAckReasonCodePacketIdentifierInUseCodec.frameSize(stream, baseOffset)
      151 -> V5SubAckReasonCodeQuotaExceededCodec.frameSize(stream, baseOffset)
      158 -> V5SubAckReasonCodeSharedSubscriptionsNotSupportedCodec.frameSize(stream, baseOffset)
      161 -> V5SubAckReasonCodeSubscriptionIdentifiersNotSupportedCodec.frameSize(stream, baseOffset)
      162 -> V5SubAckReasonCodeWildcardSubscriptionsNotSupportedCodec.frameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "V5SubAckReasonCode.discriminator", bufferPosition = baseOffset, expected = "one of {0, 1, 2, 128, 131, 135, 143, 145, 151, 158, 161, 162}", actual = """${__dispatchValue}""")
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
