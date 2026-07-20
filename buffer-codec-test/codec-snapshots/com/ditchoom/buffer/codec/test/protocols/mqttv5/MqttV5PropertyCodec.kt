package com.ditchoom.buffer.codec.test.protocols.mqttv5

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

public object MqttV5PropertyCodec : Codec<MqttV5Property> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property {
    val discriminatorPosition = buffer.position()
    val __discriminator = MqttV5PropertyIdCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      1 -> MqttV5PropertyPayloadFormatIndicatorCodec.decode(buffer, context)
      2 -> MqttV5PropertyMessageExpiryIntervalCodec.decode(buffer, context)
      3 -> MqttV5PropertyContentTypeCodec.decode(buffer, context)
      8 -> MqttV5PropertyResponseTopicCodec.decode(buffer, context)
      9 -> MqttV5PropertyCorrelationDataCodec.decode(buffer, context)
      11 -> MqttV5PropertySubscriptionIdentifierCodec.decode(buffer, context)
      17 -> MqttV5PropertySessionExpiryIntervalCodec.decode(buffer, context)
      18 -> MqttV5PropertyAssignedClientIdentifierCodec.decode(buffer, context)
      19 -> MqttV5PropertyServerKeepAliveCodec.decode(buffer, context)
      21 -> MqttV5PropertyAuthenticationMethodCodec.decode(buffer, context)
      22 -> MqttV5PropertyAuthenticationDataCodec.decode(buffer, context)
      23 -> MqttV5PropertyRequestProblemInformationCodec.decode(buffer, context)
      24 -> MqttV5PropertyWillDelayIntervalCodec.decode(buffer, context)
      25 -> MqttV5PropertyRequestResponseInformationCodec.decode(buffer, context)
      26 -> MqttV5PropertyResponseInformationCodec.decode(buffer, context)
      28 -> MqttV5PropertyServerReferenceCodec.decode(buffer, context)
      31 -> MqttV5PropertyReasonStringCodec.decode(buffer, context)
      33 -> MqttV5PropertyReceiveMaximumCodec.decode(buffer, context)
      34 -> MqttV5PropertyTopicAliasMaximumCodec.decode(buffer, context)
      35 -> MqttV5PropertyTopicAliasCodec.decode(buffer, context)
      36 -> MqttV5PropertyMaximumQoSCodec.decode(buffer, context)
      37 -> MqttV5PropertyRetainAvailableCodec.decode(buffer, context)
      38 -> MqttV5PropertyUserPropertyCodec.decode(buffer, context)
      39 -> MqttV5PropertyMaximumPacketSizeCodec.decode(buffer, context)
      40 -> MqttV5PropertyWildcardSubscriptionAvailableCodec.decode(buffer, context)
      41 -> MqttV5PropertySubscriptionIdentifiersAvailableCodec.decode(buffer, context)
      42 -> MqttV5PropertySharedSubscriptionAvailableCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "MqttV5Property.discriminator", bufferPosition = discriminatorPosition, expected = "one of {1, 2, 3, 8, 9, 11, 17, 18, 19, 21, 22, 23, 24, 25, 26, 28, 31, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property,
    context: EncodeContext,
  ) {
    when (value) {
      is MqttV5Property.PayloadFormatIndicator -> MqttV5PropertyPayloadFormatIndicatorCodec.encode(buffer, value, context)
      is MqttV5Property.MessageExpiryInterval -> MqttV5PropertyMessageExpiryIntervalCodec.encode(buffer, value, context)
      is MqttV5Property.ContentType -> MqttV5PropertyContentTypeCodec.encode(buffer, value, context)
      is MqttV5Property.ResponseTopic -> MqttV5PropertyResponseTopicCodec.encode(buffer, value, context)
      is MqttV5Property.CorrelationData -> MqttV5PropertyCorrelationDataCodec.encode(buffer, value, context)
      is MqttV5Property.SubscriptionIdentifier -> MqttV5PropertySubscriptionIdentifierCodec.encode(buffer, value, context)
      is MqttV5Property.SessionExpiryInterval -> MqttV5PropertySessionExpiryIntervalCodec.encode(buffer, value, context)
      is MqttV5Property.AssignedClientIdentifier -> MqttV5PropertyAssignedClientIdentifierCodec.encode(buffer, value, context)
      is MqttV5Property.ServerKeepAlive -> MqttV5PropertyServerKeepAliveCodec.encode(buffer, value, context)
      is MqttV5Property.AuthenticationMethod -> MqttV5PropertyAuthenticationMethodCodec.encode(buffer, value, context)
      is MqttV5Property.AuthenticationData -> MqttV5PropertyAuthenticationDataCodec.encode(buffer, value, context)
      is MqttV5Property.RequestProblemInformation -> MqttV5PropertyRequestProblemInformationCodec.encode(buffer, value, context)
      is MqttV5Property.WillDelayInterval -> MqttV5PropertyWillDelayIntervalCodec.encode(buffer, value, context)
      is MqttV5Property.RequestResponseInformation -> MqttV5PropertyRequestResponseInformationCodec.encode(buffer, value, context)
      is MqttV5Property.ResponseInformation -> MqttV5PropertyResponseInformationCodec.encode(buffer, value, context)
      is MqttV5Property.ServerReference -> MqttV5PropertyServerReferenceCodec.encode(buffer, value, context)
      is MqttV5Property.ReasonString -> MqttV5PropertyReasonStringCodec.encode(buffer, value, context)
      is MqttV5Property.ReceiveMaximum -> MqttV5PropertyReceiveMaximumCodec.encode(buffer, value, context)
      is MqttV5Property.TopicAliasMaximum -> MqttV5PropertyTopicAliasMaximumCodec.encode(buffer, value, context)
      is MqttV5Property.TopicAlias -> MqttV5PropertyTopicAliasCodec.encode(buffer, value, context)
      is MqttV5Property.MaximumQoS -> MqttV5PropertyMaximumQoSCodec.encode(buffer, value, context)
      is MqttV5Property.RetainAvailable -> MqttV5PropertyRetainAvailableCodec.encode(buffer, value, context)
      is MqttV5Property.UserProperty -> MqttV5PropertyUserPropertyCodec.encode(buffer, value, context)
      is MqttV5Property.MaximumPacketSize -> MqttV5PropertyMaximumPacketSizeCodec.encode(buffer, value, context)
      is MqttV5Property.WildcardSubscriptionAvailable -> MqttV5PropertyWildcardSubscriptionAvailableCodec.encode(buffer, value, context)
      is MqttV5Property.SubscriptionIdentifiersAvailable -> MqttV5PropertySubscriptionIdentifiersAvailableCodec.encode(buffer, value, context)
      is MqttV5Property.SharedSubscriptionAvailable -> MqttV5PropertySharedSubscriptionAvailableCodec.encode(buffer, value, context)
    }
  }

  override fun wireSize(`value`: MqttV5Property, context: EncodeContext): WireSize = when (value) {
    is MqttV5Property.PayloadFormatIndicator -> MqttV5PropertyPayloadFormatIndicatorCodec.wireSize(value, context)
    is MqttV5Property.MessageExpiryInterval -> MqttV5PropertyMessageExpiryIntervalCodec.wireSize(value, context)
    is MqttV5Property.ContentType -> MqttV5PropertyContentTypeCodec.wireSize(value, context)
    is MqttV5Property.ResponseTopic -> MqttV5PropertyResponseTopicCodec.wireSize(value, context)
    is MqttV5Property.CorrelationData -> MqttV5PropertyCorrelationDataCodec.wireSize(value, context)
    is MqttV5Property.SubscriptionIdentifier -> MqttV5PropertySubscriptionIdentifierCodec.wireSize(value, context)
    is MqttV5Property.SessionExpiryInterval -> MqttV5PropertySessionExpiryIntervalCodec.wireSize(value, context)
    is MqttV5Property.AssignedClientIdentifier -> MqttV5PropertyAssignedClientIdentifierCodec.wireSize(value, context)
    is MqttV5Property.ServerKeepAlive -> MqttV5PropertyServerKeepAliveCodec.wireSize(value, context)
    is MqttV5Property.AuthenticationMethod -> MqttV5PropertyAuthenticationMethodCodec.wireSize(value, context)
    is MqttV5Property.AuthenticationData -> MqttV5PropertyAuthenticationDataCodec.wireSize(value, context)
    is MqttV5Property.RequestProblemInformation -> MqttV5PropertyRequestProblemInformationCodec.wireSize(value, context)
    is MqttV5Property.WillDelayInterval -> MqttV5PropertyWillDelayIntervalCodec.wireSize(value, context)
    is MqttV5Property.RequestResponseInformation -> MqttV5PropertyRequestResponseInformationCodec.wireSize(value, context)
    is MqttV5Property.ResponseInformation -> MqttV5PropertyResponseInformationCodec.wireSize(value, context)
    is MqttV5Property.ServerReference -> MqttV5PropertyServerReferenceCodec.wireSize(value, context)
    is MqttV5Property.ReasonString -> MqttV5PropertyReasonStringCodec.wireSize(value, context)
    is MqttV5Property.ReceiveMaximum -> MqttV5PropertyReceiveMaximumCodec.wireSize(value, context)
    is MqttV5Property.TopicAliasMaximum -> MqttV5PropertyTopicAliasMaximumCodec.wireSize(value, context)
    is MqttV5Property.TopicAlias -> MqttV5PropertyTopicAliasCodec.wireSize(value, context)
    is MqttV5Property.MaximumQoS -> MqttV5PropertyMaximumQoSCodec.wireSize(value, context)
    is MqttV5Property.RetainAvailable -> MqttV5PropertyRetainAvailableCodec.wireSize(value, context)
    is MqttV5Property.UserProperty -> MqttV5PropertyUserPropertyCodec.wireSize(value, context)
    is MqttV5Property.MaximumPacketSize -> MqttV5PropertyMaximumPacketSizeCodec.wireSize(value, context)
    is MqttV5Property.WildcardSubscriptionAvailable -> MqttV5PropertyWildcardSubscriptionAvailableCodec.wireSize(value, context)
    is MqttV5Property.SubscriptionIdentifiersAvailable -> MqttV5PropertySubscriptionIdentifiersAvailableCodec.wireSize(value, context)
    is MqttV5Property.SharedSubscriptionAvailable -> MqttV5PropertySharedSubscriptionAvailableCodec.wireSize(value, context)
  }

  override fun sizeHint(`value`: MqttV5Property, context: EncodeContext): Int = when (value) {
    is MqttV5Property.PayloadFormatIndicator -> MqttV5PropertyPayloadFormatIndicatorCodec.sizeHint(value, context)
    is MqttV5Property.MessageExpiryInterval -> MqttV5PropertyMessageExpiryIntervalCodec.sizeHint(value, context)
    is MqttV5Property.ContentType -> MqttV5PropertyContentTypeCodec.sizeHint(value, context)
    is MqttV5Property.ResponseTopic -> MqttV5PropertyResponseTopicCodec.sizeHint(value, context)
    is MqttV5Property.CorrelationData -> MqttV5PropertyCorrelationDataCodec.sizeHint(value, context)
    is MqttV5Property.SubscriptionIdentifier -> MqttV5PropertySubscriptionIdentifierCodec.sizeHint(value, context)
    is MqttV5Property.SessionExpiryInterval -> MqttV5PropertySessionExpiryIntervalCodec.sizeHint(value, context)
    is MqttV5Property.AssignedClientIdentifier -> MqttV5PropertyAssignedClientIdentifierCodec.sizeHint(value, context)
    is MqttV5Property.ServerKeepAlive -> MqttV5PropertyServerKeepAliveCodec.sizeHint(value, context)
    is MqttV5Property.AuthenticationMethod -> MqttV5PropertyAuthenticationMethodCodec.sizeHint(value, context)
    is MqttV5Property.AuthenticationData -> MqttV5PropertyAuthenticationDataCodec.sizeHint(value, context)
    is MqttV5Property.RequestProblemInformation -> MqttV5PropertyRequestProblemInformationCodec.sizeHint(value, context)
    is MqttV5Property.WillDelayInterval -> MqttV5PropertyWillDelayIntervalCodec.sizeHint(value, context)
    is MqttV5Property.RequestResponseInformation -> MqttV5PropertyRequestResponseInformationCodec.sizeHint(value, context)
    is MqttV5Property.ResponseInformation -> MqttV5PropertyResponseInformationCodec.sizeHint(value, context)
    is MqttV5Property.ServerReference -> MqttV5PropertyServerReferenceCodec.sizeHint(value, context)
    is MqttV5Property.ReasonString -> MqttV5PropertyReasonStringCodec.sizeHint(value, context)
    is MqttV5Property.ReceiveMaximum -> MqttV5PropertyReceiveMaximumCodec.sizeHint(value, context)
    is MqttV5Property.TopicAliasMaximum -> MqttV5PropertyTopicAliasMaximumCodec.sizeHint(value, context)
    is MqttV5Property.TopicAlias -> MqttV5PropertyTopicAliasCodec.sizeHint(value, context)
    is MqttV5Property.MaximumQoS -> MqttV5PropertyMaximumQoSCodec.sizeHint(value, context)
    is MqttV5Property.RetainAvailable -> MqttV5PropertyRetainAvailableCodec.sizeHint(value, context)
    is MqttV5Property.UserProperty -> MqttV5PropertyUserPropertyCodec.sizeHint(value, context)
    is MqttV5Property.MaximumPacketSize -> MqttV5PropertyMaximumPacketSizeCodec.sizeHint(value, context)
    is MqttV5Property.WildcardSubscriptionAvailable -> MqttV5PropertyWildcardSubscriptionAvailableCodec.sizeHint(value, context)
    is MqttV5Property.SubscriptionIdentifiersAvailable -> MqttV5PropertySubscriptionIdentifiersAvailableCodec.sizeHint(value, context)
    is MqttV5Property.SharedSubscriptionAvailable -> MqttV5PropertySharedSubscriptionAvailableCodec.sizeHint(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = MqttV5PropertyId(__discRaw)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      1 -> MqttV5PropertyPayloadFormatIndicatorCodec.peekFrameSize(stream, baseOffset)
      2 -> MqttV5PropertyMessageExpiryIntervalCodec.peekFrameSize(stream, baseOffset)
      3 -> MqttV5PropertyContentTypeCodec.peekFrameSize(stream, baseOffset)
      8 -> MqttV5PropertyResponseTopicCodec.peekFrameSize(stream, baseOffset)
      9 -> MqttV5PropertyCorrelationDataCodec.peekFrameSize(stream, baseOffset)
      11 -> MqttV5PropertySubscriptionIdentifierCodec.peekFrameSize(stream, baseOffset)
      17 -> MqttV5PropertySessionExpiryIntervalCodec.peekFrameSize(stream, baseOffset)
      18 -> MqttV5PropertyAssignedClientIdentifierCodec.peekFrameSize(stream, baseOffset)
      19 -> MqttV5PropertyServerKeepAliveCodec.peekFrameSize(stream, baseOffset)
      21 -> MqttV5PropertyAuthenticationMethodCodec.peekFrameSize(stream, baseOffset)
      22 -> MqttV5PropertyAuthenticationDataCodec.peekFrameSize(stream, baseOffset)
      23 -> MqttV5PropertyRequestProblemInformationCodec.peekFrameSize(stream, baseOffset)
      24 -> MqttV5PropertyWillDelayIntervalCodec.peekFrameSize(stream, baseOffset)
      25 -> MqttV5PropertyRequestResponseInformationCodec.peekFrameSize(stream, baseOffset)
      26 -> MqttV5PropertyResponseInformationCodec.peekFrameSize(stream, baseOffset)
      28 -> MqttV5PropertyServerReferenceCodec.peekFrameSize(stream, baseOffset)
      31 -> MqttV5PropertyReasonStringCodec.peekFrameSize(stream, baseOffset)
      33 -> MqttV5PropertyReceiveMaximumCodec.peekFrameSize(stream, baseOffset)
      34 -> MqttV5PropertyTopicAliasMaximumCodec.peekFrameSize(stream, baseOffset)
      35 -> MqttV5PropertyTopicAliasCodec.peekFrameSize(stream, baseOffset)
      36 -> MqttV5PropertyMaximumQoSCodec.peekFrameSize(stream, baseOffset)
      37 -> MqttV5PropertyRetainAvailableCodec.peekFrameSize(stream, baseOffset)
      38 -> MqttV5PropertyUserPropertyCodec.peekFrameSize(stream, baseOffset)
      39 -> MqttV5PropertyMaximumPacketSizeCodec.peekFrameSize(stream, baseOffset)
      40 -> MqttV5PropertyWildcardSubscriptionAvailableCodec.peekFrameSize(stream, baseOffset)
      41 -> MqttV5PropertySubscriptionIdentifiersAvailableCodec.peekFrameSize(stream, baseOffset)
      42 -> MqttV5PropertySharedSubscriptionAvailableCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "MqttV5Property.discriminator", bufferPosition = baseOffset, expected = "one of {1, 2, 3, 8, 9, 11, 17, 18, 19, 21, 22, 23, 24, 25, 26, 28, 31, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42}", actual = """${__dispatchValue}""")
      }
    }
  }
}
