package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.test.protocols.mqttv5.puback.V5PubAckReasonCode
import com.ditchoom.buffer.codec.test.protocols.mqttv5.puback.V5PubAckReasonCodeCodec
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int
import kotlin.Throwable

public object MqttV5PacketPubAckCodec {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Packet.PubAck {
    val header = MqttFixedHeader(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (__framingLength.toInt() > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "PubAck.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val packetIdentifierRaw = buffer.readShort()
      val packetIdentifier = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) packetIdentifierRaw else swapBytes(packetIdentifierRaw)).toUShort()
      val reasonCode: V5PubAckReasonCode? = if (buffer.remaining() >= 1) V5PubAckReasonCodeCodec.decode(buffer, context) else null
      val properties: V5PropertyBag? = if (buffer.remaining() >= 1) V5PropertyBagCodec.decode(buffer, context) else null
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "PubAck.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      MqttV5Packet.PubAck(header = header, packetIdentifier = packetIdentifier, reasonCode = reasonCode, properties = properties)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: MqttV5Packet.PubAck,
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
    val packetIdentifierRaw = value.packetIdentifier.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) packetIdentifierRaw else swapBytes(packetIdentifierRaw))
    if (value.reasonCode != null) {
      val reasonCodeValue = value.reasonCode ?: throw EncodeException(fieldPath = "PubAck.reasonCode", reason = "@When(\"remaining >= 1\") predicate is true but field is null")
      V5PubAckReasonCodeCodec.encode(buffer, reasonCodeValue, context)
    }
    if (value.properties != null) {
      val propertiesValue = value.properties ?: throw EncodeException(fieldPath = "PubAck.properties", reason = "@When(\"remaining >= 1\") predicate is true but field is null")
      V5PropertyBagCodec.encode(buffer, propertiesValue, context)
    }
  }

  public fun peekFrameSize(stream: StreamProcessor, baseOffset: Int = 0): PeekResult {
    if (stream.available() - baseOffset < 2) return PeekResult.NeedsMoreData
    val __framingPeek = stream.peekBuffer(baseOffset + 1, 5) ?: return PeekResult.NeedsMoreData
    try {
      val __framingPeekStart = __framingPeek.position()
      val __framingLength = try {
        MqttRemainingLengthCodec.decode(__framingPeek, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return PeekResult.NeedsMoreData
          else -> throw __e
        }
      }
      val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart
      val __total = 1 + __framingPrefixWidth + __framingLength.toInt()
      return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
    } finally {
      (__framingPeek as? PlatformBuffer)?.freeNativeMemory()
    }
  }
}
