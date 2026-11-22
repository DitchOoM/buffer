package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.test.protocols.payload.PacketId
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.String
import kotlin.Suppress

public class MqttV5PacketPublishCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Packet.Publish<P> {
    val header = MqttFixedHeader(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "Publish.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val topicPrefixB0 = buffer.readUByte().toUInt()
      val topicPrefixB1 = buffer.readUByte().toUInt()
      val topicPrefix = ((topicPrefixB0 shl 8) or topicPrefixB1)
      if (topicPrefix > Int.MAX_VALUE.toUInt()) {
        throw FrameTooLargeException(declaredBytes = topicPrefix.toLong(), maxBytes = Int.MAX_VALUE)
      }
      val topicLength = topicPrefix.toInt()
      val topic = buffer.readText(topicLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
      val packetId: PacketId? = if (header.qosGreaterThanZero) PacketId(buffer.readUShort()) else null
      val properties = V5PropertyBagCodec.decode(buffer, context)
      val payload = payloadCodec.decode(buffer, context)
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "Publish.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      MqttV5Packet.Publish<P>(header = header, topic = topic, packetId = packetId, properties = properties, payload = payload)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: MqttV5Packet.Publish<P>,
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
    val topicSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val topicBodyStart = buffer.position()
    buffer.writeText(value.topic, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val topicEndPosition = buffer.position()
    val topicByteCount = topicEndPosition - topicBodyStart
    if (topicByteCount > 65_535) {
      throw EncodeException(fieldPath = "Publish.topic", reason = """UTF-8 byte length ${topicByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(topicSizePosition)
    val topicPrefix = topicByteCount.toUInt()
    buffer.writeUByte(((topicPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((topicPrefix and 0xFFu).toUByte())
    buffer.position(topicEndPosition)
    if (value.header.qosGreaterThanZero) {
      val packetIdValue = value.packetId ?: throw EncodeException(fieldPath = "Publish.packetId", reason = "@When(\"header.qosGreaterThanZero\") predicate is true but field is null")
      buffer.writeUShort(packetIdValue.raw)
    }
    V5PropertyBagCodec.encode(buffer, value.properties, context)
    payloadCodec.encode(buffer, value.payload, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = Companion.frameSize(stream, baseOffset)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)

  public class Partial<P : Payload> internal constructor(
    public val `header`: MqttFixedHeader,
    public val topic: String,
    public val packetId: PacketId?,
    public val properties: V5PropertyBag,
    private val outerLimit: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(payloadCodec: Decoder<P>): MqttV5Packet.Publish<P> = try {
      val payload = payloadCodec.decode(buffer, context)
      MqttV5Packet.Publish<P>(header = header, topic = topic, packetId = packetId, properties = properties, payload = payload)
    } finally {
      buffer.setLimit(outerLimit)
    }
  }

  public companion object : FrameDetector {
    override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = MqttRemainingLengthCodec.peekLength(stream, baseOffset + 1).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(1 + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })

    @Deprecated(
      message = "Call frameSize, which does not box. Removed in the next major release.",
      replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
    )
    @Suppress("DEPRECATION")
    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)

    public fun <P : Payload> partial(buffer: ReadBuffer, context: DecodeContext): Partial<P> {
      val header = MqttFixedHeader(buffer.readUByte())
      val __framingOuterLimit = buffer.limit()
      val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
      if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
        throw DecodeException(
              fieldPath = "Publish.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "a fully-buffered " + __framingLength + "-byte framed body",
              actual = buffer.remaining().toString() + " bytes available",
            )
      }
      MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
      val topicPrefixB0 = buffer.readUByte().toUInt()
      val topicPrefixB1 = buffer.readUByte().toUInt()
      val topicPrefix = ((topicPrefixB0 shl 8) or topicPrefixB1)
      if (topicPrefix > Int.MAX_VALUE.toUInt()) {
        throw FrameTooLargeException(declaredBytes = topicPrefix.toLong(), maxBytes = Int.MAX_VALUE)
      }
      val topicLength = topicPrefix.toInt()
      val topic = buffer.readText(topicLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
      val packetId: PacketId? = if (header.qosGreaterThanZero) PacketId(buffer.readUShort()) else null
      val properties = V5PropertyBagCodec.decode(buffer, context)
      return Partial<P>(header = header, topic = topic, packetId = packetId, properties = properties, outerLimit = __framingOuterLimit, buffer = buffer, context = context)
    }
  }
}
