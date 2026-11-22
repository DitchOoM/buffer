package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.test.protocols.mqttv5.authrc.V5AuthReasonCode
import com.ditchoom.buffer.codec.test.protocols.mqttv5.authrc.V5AuthReasonCodeCodec
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object MqttV5PacketAuthCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Packet.Auth {
    val header = MqttFixedHeader(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "Auth.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val reasonCode: V5AuthReasonCode? = if (buffer.remaining() >= 1) V5AuthReasonCodeCodec.decode(buffer, context) else null
      val properties: V5PropertyBag? = if (buffer.remaining() >= 1) V5PropertyBagCodec.decode(buffer, context) else null
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "Auth.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      MqttV5Packet.Auth(header = header, reasonCode = reasonCode, properties = properties)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: MqttV5Packet.Auth,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer = FramedEncoder.encode(
    factory = factory,
    framingCodec = MqttRemainingLengthCodec,
    context = context,
    headerWireWidth = 1,
    writeHeader = { buffer ->
      buffer.writeUByte(value.header.raw)
    },
  ) { buffer ->
    if (value.reasonCode != null) {
      val reasonCodeValue = value.reasonCode ?: throw EncodeException(fieldPath = "Auth.reasonCode", reason = "@When(\"remaining >= 1\") predicate is true but field is null")
      V5AuthReasonCodeCodec.encode(buffer, reasonCodeValue, context)
    }
    if (value.properties != null) {
      val propertiesValue = value.properties ?: throw EncodeException(fieldPath = "Auth.properties", reason = "@When(\"remaining >= 1\") predicate is true but field is null")
      V5PropertyBagCodec.encode(buffer, propertiesValue, context)
    }
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = MqttRemainingLengthCodec.peekLength(stream, baseOffset + 1).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(1 + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
