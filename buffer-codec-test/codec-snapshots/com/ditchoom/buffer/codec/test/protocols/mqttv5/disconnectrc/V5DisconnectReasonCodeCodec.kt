package com.ditchoom.buffer.codec.test.protocols.mqttv5.disconnectrc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object V5DisconnectReasonCodeCodec : Codec<V5DisconnectReasonCode> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5DisconnectReasonCode {
    val discriminatorPosition = buffer.position()
    val __discriminator = V5DisconnectReasonCodeRawCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5DisconnectReasonCodeNormalDisconnectionCodec.decode(buffer, context)
      4 -> V5DisconnectReasonCodeDisconnectWithWillMessageCodec.decode(buffer, context)
      128 -> V5DisconnectReasonCodeUnspecifiedErrorCodec.decode(buffer, context)
      129 -> V5DisconnectReasonCodeMalformedPacketCodec.decode(buffer, context)
      130 -> V5DisconnectReasonCodeProtocolErrorCodec.decode(buffer, context)
      131 -> V5DisconnectReasonCodeImplementationSpecificErrorCodec.decode(buffer, context)
      135 -> V5DisconnectReasonCodeNotAuthorizedCodec.decode(buffer, context)
      137 -> V5DisconnectReasonCodeServerBusyCodec.decode(buffer, context)
      139 -> V5DisconnectReasonCodeServerShuttingDownCodec.decode(buffer, context)
      141 -> V5DisconnectReasonCodeKeepAliveTimeoutCodec.decode(buffer, context)
      142 -> V5DisconnectReasonCodeSessionTakenOverCodec.decode(buffer, context)
      143 -> V5DisconnectReasonCodeTopicFilterInvalidCodec.decode(buffer, context)
      144 -> V5DisconnectReasonCodeTopicNameInvalidCodec.decode(buffer, context)
      147 -> V5DisconnectReasonCodeReceiveMaximumExceededCodec.decode(buffer, context)
      148 -> V5DisconnectReasonCodeTopicAliasInvalidCodec.decode(buffer, context)
      149 -> V5DisconnectReasonCodePacketTooLargeCodec.decode(buffer, context)
      151 -> V5DisconnectReasonCodeQuotaExceededCodec.decode(buffer, context)
      152 -> V5DisconnectReasonCodeAdministrativeActionCodec.decode(buffer, context)
      153 -> V5DisconnectReasonCodePayloadFormatInvalidCodec.decode(buffer, context)
      154 -> V5DisconnectReasonCodeRetainNotSupportedCodec.decode(buffer, context)
      155 -> V5DisconnectReasonCodeQoSNotSupportedCodec.decode(buffer, context)
      156 -> V5DisconnectReasonCodeUseAnotherServerCodec.decode(buffer, context)
      157 -> V5DisconnectReasonCodeServerMovedCodec.decode(buffer, context)
      158 -> V5DisconnectReasonCodeSharedSubscriptionsNotSupportedCodec.decode(buffer, context)
      159 -> V5DisconnectReasonCodeConnectionRateExceededCodec.decode(buffer, context)
      160 -> V5DisconnectReasonCodeMaximumConnectTimeCodec.decode(buffer, context)
      161 -> V5DisconnectReasonCodeSubscriptionIdentifiersNotSupportedCodec.decode(buffer, context)
      162 -> V5DisconnectReasonCodeWildcardSubscriptionsNotSupportedCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "V5DisconnectReasonCode.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 4, 128, 129, 130, 131, 135, 137, 139, 141, 142, 143, 144, 147, 148, 149, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5DisconnectReasonCode,
    context: EncodeContext,
  ) {
    when (value) {
      is V5DisconnectReasonCode.NormalDisconnection -> V5DisconnectReasonCodeNormalDisconnectionCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.DisconnectWithWillMessage -> V5DisconnectReasonCodeDisconnectWithWillMessageCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.UnspecifiedError -> V5DisconnectReasonCodeUnspecifiedErrorCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.MalformedPacket -> V5DisconnectReasonCodeMalformedPacketCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.ProtocolError -> V5DisconnectReasonCodeProtocolErrorCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.ImplementationSpecificError -> V5DisconnectReasonCodeImplementationSpecificErrorCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.NotAuthorized -> V5DisconnectReasonCodeNotAuthorizedCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.ServerBusy -> V5DisconnectReasonCodeServerBusyCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.ServerShuttingDown -> V5DisconnectReasonCodeServerShuttingDownCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.KeepAliveTimeout -> V5DisconnectReasonCodeKeepAliveTimeoutCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.SessionTakenOver -> V5DisconnectReasonCodeSessionTakenOverCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.TopicFilterInvalid -> V5DisconnectReasonCodeTopicFilterInvalidCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.TopicNameInvalid -> V5DisconnectReasonCodeTopicNameInvalidCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.ReceiveMaximumExceeded -> V5DisconnectReasonCodeReceiveMaximumExceededCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.TopicAliasInvalid -> V5DisconnectReasonCodeTopicAliasInvalidCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.PacketTooLarge -> V5DisconnectReasonCodePacketTooLargeCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.QuotaExceeded -> V5DisconnectReasonCodeQuotaExceededCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.AdministrativeAction -> V5DisconnectReasonCodeAdministrativeActionCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.PayloadFormatInvalid -> V5DisconnectReasonCodePayloadFormatInvalidCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.RetainNotSupported -> V5DisconnectReasonCodeRetainNotSupportedCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.QoSNotSupported -> V5DisconnectReasonCodeQoSNotSupportedCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.UseAnotherServer -> V5DisconnectReasonCodeUseAnotherServerCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.ServerMoved -> V5DisconnectReasonCodeServerMovedCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.SharedSubscriptionsNotSupported -> V5DisconnectReasonCodeSharedSubscriptionsNotSupportedCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.ConnectionRateExceeded -> V5DisconnectReasonCodeConnectionRateExceededCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.MaximumConnectTime -> V5DisconnectReasonCodeMaximumConnectTimeCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.SubscriptionIdentifiersNotSupported -> V5DisconnectReasonCodeSubscriptionIdentifiersNotSupportedCodec.encode(buffer, value, context)
      is V5DisconnectReasonCode.WildcardSubscriptionsNotSupported -> V5DisconnectReasonCodeWildcardSubscriptionsNotSupportedCodec.encode(buffer, value, context)
    }
  }

  override fun wireBytes(`value`: V5DisconnectReasonCode, context: EncodeContext): WireByteCount = when (value) {
    is V5DisconnectReasonCode.NormalDisconnection -> V5DisconnectReasonCodeNormalDisconnectionCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.DisconnectWithWillMessage -> V5DisconnectReasonCodeDisconnectWithWillMessageCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.UnspecifiedError -> V5DisconnectReasonCodeUnspecifiedErrorCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.MalformedPacket -> V5DisconnectReasonCodeMalformedPacketCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.ProtocolError -> V5DisconnectReasonCodeProtocolErrorCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.ImplementationSpecificError -> V5DisconnectReasonCodeImplementationSpecificErrorCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.NotAuthorized -> V5DisconnectReasonCodeNotAuthorizedCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.ServerBusy -> V5DisconnectReasonCodeServerBusyCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.ServerShuttingDown -> V5DisconnectReasonCodeServerShuttingDownCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.KeepAliveTimeout -> V5DisconnectReasonCodeKeepAliveTimeoutCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.SessionTakenOver -> V5DisconnectReasonCodeSessionTakenOverCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.TopicFilterInvalid -> V5DisconnectReasonCodeTopicFilterInvalidCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.TopicNameInvalid -> V5DisconnectReasonCodeTopicNameInvalidCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.ReceiveMaximumExceeded -> V5DisconnectReasonCodeReceiveMaximumExceededCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.TopicAliasInvalid -> V5DisconnectReasonCodeTopicAliasInvalidCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.PacketTooLarge -> V5DisconnectReasonCodePacketTooLargeCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.QuotaExceeded -> V5DisconnectReasonCodeQuotaExceededCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.AdministrativeAction -> V5DisconnectReasonCodeAdministrativeActionCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.PayloadFormatInvalid -> V5DisconnectReasonCodePayloadFormatInvalidCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.RetainNotSupported -> V5DisconnectReasonCodeRetainNotSupportedCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.QoSNotSupported -> V5DisconnectReasonCodeQoSNotSupportedCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.UseAnotherServer -> V5DisconnectReasonCodeUseAnotherServerCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.ServerMoved -> V5DisconnectReasonCodeServerMovedCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.SharedSubscriptionsNotSupported -> V5DisconnectReasonCodeSharedSubscriptionsNotSupportedCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.ConnectionRateExceeded -> V5DisconnectReasonCodeConnectionRateExceededCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.MaximumConnectTime -> V5DisconnectReasonCodeMaximumConnectTimeCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.SubscriptionIdentifiersNotSupported -> V5DisconnectReasonCodeSubscriptionIdentifiersNotSupportedCodec.wireBytes(value, context)
    is V5DisconnectReasonCode.WildcardSubscriptionsNotSupported -> V5DisconnectReasonCodeWildcardSubscriptionsNotSupportedCodec.wireBytes(value, context)
  }

  override fun sizeHint(`value`: V5DisconnectReasonCode, context: EncodeContext): Int = when (value) {
    is V5DisconnectReasonCode.NormalDisconnection -> V5DisconnectReasonCodeNormalDisconnectionCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.DisconnectWithWillMessage -> V5DisconnectReasonCodeDisconnectWithWillMessageCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.UnspecifiedError -> V5DisconnectReasonCodeUnspecifiedErrorCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.MalformedPacket -> V5DisconnectReasonCodeMalformedPacketCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.ProtocolError -> V5DisconnectReasonCodeProtocolErrorCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.ImplementationSpecificError -> V5DisconnectReasonCodeImplementationSpecificErrorCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.NotAuthorized -> V5DisconnectReasonCodeNotAuthorizedCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.ServerBusy -> V5DisconnectReasonCodeServerBusyCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.ServerShuttingDown -> V5DisconnectReasonCodeServerShuttingDownCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.KeepAliveTimeout -> V5DisconnectReasonCodeKeepAliveTimeoutCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.SessionTakenOver -> V5DisconnectReasonCodeSessionTakenOverCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.TopicFilterInvalid -> V5DisconnectReasonCodeTopicFilterInvalidCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.TopicNameInvalid -> V5DisconnectReasonCodeTopicNameInvalidCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.ReceiveMaximumExceeded -> V5DisconnectReasonCodeReceiveMaximumExceededCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.TopicAliasInvalid -> V5DisconnectReasonCodeTopicAliasInvalidCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.PacketTooLarge -> V5DisconnectReasonCodePacketTooLargeCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.QuotaExceeded -> V5DisconnectReasonCodeQuotaExceededCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.AdministrativeAction -> V5DisconnectReasonCodeAdministrativeActionCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.PayloadFormatInvalid -> V5DisconnectReasonCodePayloadFormatInvalidCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.RetainNotSupported -> V5DisconnectReasonCodeRetainNotSupportedCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.QoSNotSupported -> V5DisconnectReasonCodeQoSNotSupportedCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.UseAnotherServer -> V5DisconnectReasonCodeUseAnotherServerCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.ServerMoved -> V5DisconnectReasonCodeServerMovedCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.SharedSubscriptionsNotSupported -> V5DisconnectReasonCodeSharedSubscriptionsNotSupportedCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.ConnectionRateExceeded -> V5DisconnectReasonCodeConnectionRateExceededCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.MaximumConnectTime -> V5DisconnectReasonCodeMaximumConnectTimeCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.SubscriptionIdentifiersNotSupported -> V5DisconnectReasonCodeSubscriptionIdentifiersNotSupportedCodec.sizeHint(value, context)
    is V5DisconnectReasonCode.WildcardSubscriptionsNotSupported -> V5DisconnectReasonCodeWildcardSubscriptionsNotSupportedCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = V5DisconnectReasonCodeRaw(__discRaw)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> V5DisconnectReasonCodeNormalDisconnectionCodec.frameSize(stream, baseOffset)
      4 -> V5DisconnectReasonCodeDisconnectWithWillMessageCodec.frameSize(stream, baseOffset)
      128 -> V5DisconnectReasonCodeUnspecifiedErrorCodec.frameSize(stream, baseOffset)
      129 -> V5DisconnectReasonCodeMalformedPacketCodec.frameSize(stream, baseOffset)
      130 -> V5DisconnectReasonCodeProtocolErrorCodec.frameSize(stream, baseOffset)
      131 -> V5DisconnectReasonCodeImplementationSpecificErrorCodec.frameSize(stream, baseOffset)
      135 -> V5DisconnectReasonCodeNotAuthorizedCodec.frameSize(stream, baseOffset)
      137 -> V5DisconnectReasonCodeServerBusyCodec.frameSize(stream, baseOffset)
      139 -> V5DisconnectReasonCodeServerShuttingDownCodec.frameSize(stream, baseOffset)
      141 -> V5DisconnectReasonCodeKeepAliveTimeoutCodec.frameSize(stream, baseOffset)
      142 -> V5DisconnectReasonCodeSessionTakenOverCodec.frameSize(stream, baseOffset)
      143 -> V5DisconnectReasonCodeTopicFilterInvalidCodec.frameSize(stream, baseOffset)
      144 -> V5DisconnectReasonCodeTopicNameInvalidCodec.frameSize(stream, baseOffset)
      147 -> V5DisconnectReasonCodeReceiveMaximumExceededCodec.frameSize(stream, baseOffset)
      148 -> V5DisconnectReasonCodeTopicAliasInvalidCodec.frameSize(stream, baseOffset)
      149 -> V5DisconnectReasonCodePacketTooLargeCodec.frameSize(stream, baseOffset)
      151 -> V5DisconnectReasonCodeQuotaExceededCodec.frameSize(stream, baseOffset)
      152 -> V5DisconnectReasonCodeAdministrativeActionCodec.frameSize(stream, baseOffset)
      153 -> V5DisconnectReasonCodePayloadFormatInvalidCodec.frameSize(stream, baseOffset)
      154 -> V5DisconnectReasonCodeRetainNotSupportedCodec.frameSize(stream, baseOffset)
      155 -> V5DisconnectReasonCodeQoSNotSupportedCodec.frameSize(stream, baseOffset)
      156 -> V5DisconnectReasonCodeUseAnotherServerCodec.frameSize(stream, baseOffset)
      157 -> V5DisconnectReasonCodeServerMovedCodec.frameSize(stream, baseOffset)
      158 -> V5DisconnectReasonCodeSharedSubscriptionsNotSupportedCodec.frameSize(stream, baseOffset)
      159 -> V5DisconnectReasonCodeConnectionRateExceededCodec.frameSize(stream, baseOffset)
      160 -> V5DisconnectReasonCodeMaximumConnectTimeCodec.frameSize(stream, baseOffset)
      161 -> V5DisconnectReasonCodeSubscriptionIdentifiersNotSupportedCodec.frameSize(stream, baseOffset)
      162 -> V5DisconnectReasonCodeWildcardSubscriptionsNotSupportedCodec.frameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "V5DisconnectReasonCode.discriminator", bufferPosition = baseOffset, expected = "one of {0, 4, 128, 129, 130, 131, 135, 137, 139, 141, 142, 143, 144, 147, 148, 149, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162}", actual = """${__dispatchValue}""")
      }
    }
  }
}
