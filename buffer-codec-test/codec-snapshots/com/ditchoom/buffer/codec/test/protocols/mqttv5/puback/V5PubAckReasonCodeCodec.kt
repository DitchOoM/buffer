package com.ditchoom.buffer.codec.test.protocols.mqttv5.puback

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
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: V5PubAckReasonCode, context: EncodeContext): WireByteCount = when (value) {
    is V5PubAckReasonCode.Success -> V5PubAckReasonCodeSuccessCodec.wireBytes(value, context)
    is V5PubAckReasonCode.NoMatchingSubscribers -> V5PubAckReasonCodeNoMatchingSubscribersCodec.wireBytes(value, context)
    is V5PubAckReasonCode.UnspecifiedError -> V5PubAckReasonCodeUnspecifiedErrorCodec.wireBytes(value, context)
    is V5PubAckReasonCode.ImplementationSpecificError -> V5PubAckReasonCodeImplementationSpecificErrorCodec.wireBytes(value, context)
    is V5PubAckReasonCode.NotAuthorized -> V5PubAckReasonCodeNotAuthorizedCodec.wireBytes(value, context)
    is V5PubAckReasonCode.TopicNameInvalid -> V5PubAckReasonCodeTopicNameInvalidCodec.wireBytes(value, context)
    is V5PubAckReasonCode.PacketIdentifierInUse -> V5PubAckReasonCodePacketIdentifierInUseCodec.wireBytes(value, context)
    is V5PubAckReasonCode.PacketIdentifierNotFound -> V5PubAckReasonCodePacketIdentifierNotFoundCodec.wireBytes(value, context)
    is V5PubAckReasonCode.QuotaExceeded -> V5PubAckReasonCodeQuotaExceededCodec.wireBytes(value, context)
    is V5PubAckReasonCode.PayloadFormatInvalid -> V5PubAckReasonCodePayloadFormatInvalidCodec.wireBytes(value, context)
  }

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: V5PubAckReasonCode, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

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

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = V5PubAckReasonCodeRaw(__discRaw)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5PubAckReasonCodeSuccessCodec.frameSize(stream, baseOffset)
      16 -> V5PubAckReasonCodeNoMatchingSubscribersCodec.frameSize(stream, baseOffset)
      128 -> V5PubAckReasonCodeUnspecifiedErrorCodec.frameSize(stream, baseOffset)
      131 -> V5PubAckReasonCodeImplementationSpecificErrorCodec.frameSize(stream, baseOffset)
      135 -> V5PubAckReasonCodeNotAuthorizedCodec.frameSize(stream, baseOffset)
      144 -> V5PubAckReasonCodeTopicNameInvalidCodec.frameSize(stream, baseOffset)
      145 -> V5PubAckReasonCodePacketIdentifierInUseCodec.frameSize(stream, baseOffset)
      146 -> V5PubAckReasonCodePacketIdentifierNotFoundCodec.frameSize(stream, baseOffset)
      151 -> V5PubAckReasonCodeQuotaExceededCodec.frameSize(stream, baseOffset)
      153 -> V5PubAckReasonCodePayloadFormatInvalidCodec.frameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "V5PubAckReasonCode.discriminator", bufferPosition = baseOffset, expected = "one of {0, 16, 128, 131, 135, 144, 145, 146, 151, 153}", actual = """${__dispatchValue}""")
      }
    }
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
