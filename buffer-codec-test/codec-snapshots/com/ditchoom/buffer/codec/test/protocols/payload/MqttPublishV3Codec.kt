package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.String

public class MqttPublishV3Codec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : Codec<MqttPublishV3<P>> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttPublishV3<P> {
    val header = MqttFixedHeader(buffer.readUByte())
    val topicPrefixB0 = buffer.readUByte().toUInt()
    val topicPrefixB1 = buffer.readUByte().toUInt()
    val topicPrefix = ((topicPrefixB0 shl 8) or topicPrefixB1)
    if (topicPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "MqttPublishV3.topic", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = topicPrefix.toString())
    }
    val topicLength = topicPrefix.toInt()
    val topic = buffer.readString(topicLength, Charset.UTF8)
    val packetId = PacketId(buffer.readUShort())
    val payload = payloadCodec.decode(buffer, context)
    return MqttPublishV3<P>(header = header, topic = topic, packetId = packetId, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttPublishV3<P>,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.header.raw)
    val topicSizePosition = buffer.position()
    buffer.position(topicSizePosition + 2)
    val topicBodyStart = buffer.position()
    buffer.writeString(value.topic, Charset.UTF8)
    val topicEndPosition = buffer.position()
    val topicByteCount = topicEndPosition - topicBodyStart
    if (topicByteCount > 65_535) {
      throw EncodeException(fieldPath = "MqttPublishV3.topic", reason = """UTF-8 byte length ${topicByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(topicSizePosition)
    val topicPrefix = topicByteCount.toUInt()
    buffer.writeUByte(((topicPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((topicPrefix and 0xFFu).toUByte())
    buffer.position(topicEndPosition)
    buffer.writeUShort(value.packetId.raw)
    payloadCodec.encode(buffer, value.payload, context)
  }

  override fun wireSize(`value`: MqttPublishV3<P>, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming

  public class Partial<P : Payload> internal constructor(
    public val `header`: MqttFixedHeader,
    public val topic: String,
    public val packetId: PacketId,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(payloadCodec: Decoder<P>): MqttPublishV3<P> {
      val payload = payloadCodec.decode(buffer, context)
      return MqttPublishV3<P>(header = header, topic = topic, packetId = packetId, payload = payload)
    }
  }

  public companion object {
    public fun <P : Payload> partial(buffer: ReadBuffer, context: DecodeContext): Partial<P> {
      val header = MqttFixedHeader(buffer.readUByte())
      val topicPrefixB0 = buffer.readUByte().toUInt()
      val topicPrefixB1 = buffer.readUByte().toUInt()
      val topicPrefix = ((topicPrefixB0 shl 8) or topicPrefixB1)
      if (topicPrefix > Int.MAX_VALUE.toUInt()) {
        throw DecodeException(fieldPath = "MqttPublishV3.topic", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = topicPrefix.toString())
      }
      val topicLength = topicPrefix.toInt()
      val topic = buffer.readString(topicLength, Charset.UTF8)
      val packetId = PacketId(buffer.readUShort())
      return Partial<P>(header = header, topic = topic, packetId = packetId, buffer = buffer, context = context)
    }
  }
}
