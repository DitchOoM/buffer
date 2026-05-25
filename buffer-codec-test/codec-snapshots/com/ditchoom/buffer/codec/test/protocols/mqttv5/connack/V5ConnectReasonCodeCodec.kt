package com.ditchoom.buffer.codec.test.protocols.mqttv5.connack

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

public object V5ConnectReasonCodeCodec : Codec<V5ConnectReasonCode> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5ConnectReasonCode {
    val discriminatorPosition = buffer.position()
    val __discriminator = V5ConnectReasonCodeRawCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5ConnectReasonCodeSuccessCodec.decode(buffer, context)
      128 -> V5ConnectReasonCodeUnspecifiedErrorCodec.decode(buffer, context)
      129 -> V5ConnectReasonCodeMalformedPacketCodec.decode(buffer, context)
      130 -> V5ConnectReasonCodeProtocolErrorCodec.decode(buffer, context)
      131 -> V5ConnectReasonCodeImplementationSpecificErrorCodec.decode(buffer, context)
      132 -> V5ConnectReasonCodeUnsupportedProtocolVersionCodec.decode(buffer, context)
      133 -> V5ConnectReasonCodeClientIdentifierNotValidCodec.decode(buffer, context)
      134 -> V5ConnectReasonCodeBadUserNameOrPasswordCodec.decode(buffer, context)
      135 -> V5ConnectReasonCodeNotAuthorizedCodec.decode(buffer, context)
      136 -> V5ConnectReasonCodeServerUnavailableCodec.decode(buffer, context)
      137 -> V5ConnectReasonCodeServerBusyCodec.decode(buffer, context)
      138 -> V5ConnectReasonCodeBannedCodec.decode(buffer, context)
      140 -> V5ConnectReasonCodeBadAuthenticationMethodCodec.decode(buffer, context)
      144 -> V5ConnectReasonCodeTopicNameInvalidCodec.decode(buffer, context)
      149 -> V5ConnectReasonCodePacketTooLargeCodec.decode(buffer, context)
      151 -> V5ConnectReasonCodeQuotaExceededCodec.decode(buffer, context)
      153 -> V5ConnectReasonCodePayloadFormatInvalidCodec.decode(buffer, context)
      154 -> V5ConnectReasonCodeRetainNotSupportedCodec.decode(buffer, context)
      155 -> V5ConnectReasonCodeQoSNotSupportedCodec.decode(buffer, context)
      156 -> V5ConnectReasonCodeUseAnotherServerCodec.decode(buffer, context)
      157 -> V5ConnectReasonCodeServerMovedCodec.decode(buffer, context)
      159 -> V5ConnectReasonCodeConnectionRateExceededCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "V5ConnectReasonCode.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 140, 144, 149, 151, 153, 154, 155, 156, 157, 159}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5ConnectReasonCode,
    context: EncodeContext,
  ) {
    when (value) {
      is V5ConnectReasonCode.Success -> V5ConnectReasonCodeSuccessCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.UnspecifiedError -> V5ConnectReasonCodeUnspecifiedErrorCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.MalformedPacket -> V5ConnectReasonCodeMalformedPacketCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.ProtocolError -> V5ConnectReasonCodeProtocolErrorCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.ImplementationSpecificError -> V5ConnectReasonCodeImplementationSpecificErrorCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.UnsupportedProtocolVersion -> V5ConnectReasonCodeUnsupportedProtocolVersionCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.ClientIdentifierNotValid -> V5ConnectReasonCodeClientIdentifierNotValidCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.BadUserNameOrPassword -> V5ConnectReasonCodeBadUserNameOrPasswordCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.NotAuthorized -> V5ConnectReasonCodeNotAuthorizedCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.ServerUnavailable -> V5ConnectReasonCodeServerUnavailableCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.ServerBusy -> V5ConnectReasonCodeServerBusyCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.Banned -> V5ConnectReasonCodeBannedCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.BadAuthenticationMethod -> V5ConnectReasonCodeBadAuthenticationMethodCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.TopicNameInvalid -> V5ConnectReasonCodeTopicNameInvalidCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.PacketTooLarge -> V5ConnectReasonCodePacketTooLargeCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.QuotaExceeded -> V5ConnectReasonCodeQuotaExceededCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.PayloadFormatInvalid -> V5ConnectReasonCodePayloadFormatInvalidCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.RetainNotSupported -> V5ConnectReasonCodeRetainNotSupportedCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.QoSNotSupported -> V5ConnectReasonCodeQoSNotSupportedCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.UseAnotherServer -> V5ConnectReasonCodeUseAnotherServerCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.ServerMoved -> V5ConnectReasonCodeServerMovedCodec.encode(buffer, value, context)
      is V5ConnectReasonCode.ConnectionRateExceeded -> V5ConnectReasonCodeConnectionRateExceededCodec.encode(buffer, value, context)
    }
  }

  override fun wireSize(`value`: V5ConnectReasonCode, context: EncodeContext): WireSize = when (value) {
    is V5ConnectReasonCode.Success -> V5ConnectReasonCodeSuccessCodec.wireSize(value, context)
    is V5ConnectReasonCode.UnspecifiedError -> V5ConnectReasonCodeUnspecifiedErrorCodec.wireSize(value, context)
    is V5ConnectReasonCode.MalformedPacket -> V5ConnectReasonCodeMalformedPacketCodec.wireSize(value, context)
    is V5ConnectReasonCode.ProtocolError -> V5ConnectReasonCodeProtocolErrorCodec.wireSize(value, context)
    is V5ConnectReasonCode.ImplementationSpecificError -> V5ConnectReasonCodeImplementationSpecificErrorCodec.wireSize(value, context)
    is V5ConnectReasonCode.UnsupportedProtocolVersion -> V5ConnectReasonCodeUnsupportedProtocolVersionCodec.wireSize(value, context)
    is V5ConnectReasonCode.ClientIdentifierNotValid -> V5ConnectReasonCodeClientIdentifierNotValidCodec.wireSize(value, context)
    is V5ConnectReasonCode.BadUserNameOrPassword -> V5ConnectReasonCodeBadUserNameOrPasswordCodec.wireSize(value, context)
    is V5ConnectReasonCode.NotAuthorized -> V5ConnectReasonCodeNotAuthorizedCodec.wireSize(value, context)
    is V5ConnectReasonCode.ServerUnavailable -> V5ConnectReasonCodeServerUnavailableCodec.wireSize(value, context)
    is V5ConnectReasonCode.ServerBusy -> V5ConnectReasonCodeServerBusyCodec.wireSize(value, context)
    is V5ConnectReasonCode.Banned -> V5ConnectReasonCodeBannedCodec.wireSize(value, context)
    is V5ConnectReasonCode.BadAuthenticationMethod -> V5ConnectReasonCodeBadAuthenticationMethodCodec.wireSize(value, context)
    is V5ConnectReasonCode.TopicNameInvalid -> V5ConnectReasonCodeTopicNameInvalidCodec.wireSize(value, context)
    is V5ConnectReasonCode.PacketTooLarge -> V5ConnectReasonCodePacketTooLargeCodec.wireSize(value, context)
    is V5ConnectReasonCode.QuotaExceeded -> V5ConnectReasonCodeQuotaExceededCodec.wireSize(value, context)
    is V5ConnectReasonCode.PayloadFormatInvalid -> V5ConnectReasonCodePayloadFormatInvalidCodec.wireSize(value, context)
    is V5ConnectReasonCode.RetainNotSupported -> V5ConnectReasonCodeRetainNotSupportedCodec.wireSize(value, context)
    is V5ConnectReasonCode.QoSNotSupported -> V5ConnectReasonCodeQoSNotSupportedCodec.wireSize(value, context)
    is V5ConnectReasonCode.UseAnotherServer -> V5ConnectReasonCodeUseAnotherServerCodec.wireSize(value, context)
    is V5ConnectReasonCode.ServerMoved -> V5ConnectReasonCodeServerMovedCodec.wireSize(value, context)
    is V5ConnectReasonCode.ConnectionRateExceeded -> V5ConnectReasonCodeConnectionRateExceededCodec.wireSize(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = V5ConnectReasonCodeRaw(__discRaw)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5ConnectReasonCodeSuccessCodec.peekFrameSize(stream, baseOffset)
      128 -> V5ConnectReasonCodeUnspecifiedErrorCodec.peekFrameSize(stream, baseOffset)
      129 -> V5ConnectReasonCodeMalformedPacketCodec.peekFrameSize(stream, baseOffset)
      130 -> V5ConnectReasonCodeProtocolErrorCodec.peekFrameSize(stream, baseOffset)
      131 -> V5ConnectReasonCodeImplementationSpecificErrorCodec.peekFrameSize(stream, baseOffset)
      132 -> V5ConnectReasonCodeUnsupportedProtocolVersionCodec.peekFrameSize(stream, baseOffset)
      133 -> V5ConnectReasonCodeClientIdentifierNotValidCodec.peekFrameSize(stream, baseOffset)
      134 -> V5ConnectReasonCodeBadUserNameOrPasswordCodec.peekFrameSize(stream, baseOffset)
      135 -> V5ConnectReasonCodeNotAuthorizedCodec.peekFrameSize(stream, baseOffset)
      136 -> V5ConnectReasonCodeServerUnavailableCodec.peekFrameSize(stream, baseOffset)
      137 -> V5ConnectReasonCodeServerBusyCodec.peekFrameSize(stream, baseOffset)
      138 -> V5ConnectReasonCodeBannedCodec.peekFrameSize(stream, baseOffset)
      140 -> V5ConnectReasonCodeBadAuthenticationMethodCodec.peekFrameSize(stream, baseOffset)
      144 -> V5ConnectReasonCodeTopicNameInvalidCodec.peekFrameSize(stream, baseOffset)
      149 -> V5ConnectReasonCodePacketTooLargeCodec.peekFrameSize(stream, baseOffset)
      151 -> V5ConnectReasonCodeQuotaExceededCodec.peekFrameSize(stream, baseOffset)
      153 -> V5ConnectReasonCodePayloadFormatInvalidCodec.peekFrameSize(stream, baseOffset)
      154 -> V5ConnectReasonCodeRetainNotSupportedCodec.peekFrameSize(stream, baseOffset)
      155 -> V5ConnectReasonCodeQoSNotSupportedCodec.peekFrameSize(stream, baseOffset)
      156 -> V5ConnectReasonCodeUseAnotherServerCodec.peekFrameSize(stream, baseOffset)
      157 -> V5ConnectReasonCodeServerMovedCodec.peekFrameSize(stream, baseOffset)
      159 -> V5ConnectReasonCodeConnectionRateExceededCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "V5ConnectReasonCode.discriminator", bufferPosition = baseOffset, expected = "one of {0, 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 140, 144, 149, 151, 153, 154, 155, 156, 157, 159}", actual = """${__dispatchValue}""")
      }
    }
  }
}
