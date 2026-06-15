package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.payload.PacketId
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.String
import kotlin.Throwable

public class MqttPacketPublishCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): MqttPacket.Publish<P> {
    val header = MqttFixedHeader(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (__framingLength.toInt() > buffer.remaining()) {
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
        throw DecodeException(fieldPath = "Publish.topic", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = topicPrefix.toString())
      }
      val topicLength = topicPrefix.toInt()
      val topic = buffer.readString(topicLength, Charset.UTF8)
      val packetId: PacketId? = if (header.qosGreaterThanZero) PacketId(buffer.readUShort()) else null
      val payload = payloadCodec.decode(buffer, context)
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "Publish.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      MqttPacket.Publish<P>(header = header, topic = topic, packetId = packetId, payload = payload)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: MqttPacket.Publish<P>,
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
    buffer.position(topicSizePosition + 2)
    val topicBodyStart = buffer.position()
    buffer.writeString(value.topic, Charset.UTF8)
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
    payloadCodec.encode(buffer, value.payload, context)
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

  public class Partial<P : Payload> internal constructor(
    public val `header`: MqttFixedHeader,
    public val topic: String,
    public val packetId: PacketId?,
    private val outerLimit: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(payloadCodec: Decoder<P>): MqttPacket.Publish<P> = try {
      val payload = payloadCodec.decode(buffer, context)
      MqttPacket.Publish<P>(header = header, topic = topic, packetId = packetId, payload = payload)
    } finally {
      buffer.setLimit(outerLimit)
    }
  }

  public companion object {
    public fun <P : Payload> partial(buffer: ReadBuffer, context: DecodeContext): Partial<P> {
      val header = MqttFixedHeader(buffer.readUByte())
      val __framingOuterLimit = buffer.limit()
      val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
      if (__framingLength.toInt() > buffer.remaining()) {
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
        throw DecodeException(fieldPath = "Publish.topic", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = topicPrefix.toString())
      }
      val topicLength = topicPrefix.toInt()
      val topic = buffer.readString(topicLength, Charset.UTF8)
      val packetId: PacketId? = if (header.qosGreaterThanZero) PacketId(buffer.readUShort()) else null
      return Partial<P>(header = header, topic = topic, packetId = packetId, outerLimit = __framingOuterLimit, buffer = buffer, context = context)
    }
  }
}
