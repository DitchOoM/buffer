package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.String

public object MqttPublishV3ConcreteCodec : Codec<MqttPublishV3Concrete> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttPublishV3Concrete {
    val header = MqttFixedHeader(buffer.readUByte())
    val topicPrefixB0 = buffer.readUByte().toUInt()
    val topicPrefixB1 = buffer.readUByte().toUInt()
    val topicPrefix = ((topicPrefixB0 shl 8) or topicPrefixB1)
    if (topicPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "MqttPublishV3Concrete.topic", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = topicPrefix.toString())
    }
    val topicLength = topicPrefix.toInt()
    val topic = buffer.readString(topicLength, Charset.UTF8)
    val packetId = PacketId(buffer.readUShort())
    val payload = JpegImageCodec.decode(buffer, context)
    return MqttPublishV3Concrete(header = header, topic = topic, packetId = packetId, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttPublishV3Concrete,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.header.raw)
    val topicSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val topicBodyStart = buffer.position()
    buffer.writeString(value.topic, Charset.UTF8)
    val topicEndPosition = buffer.position()
    val topicByteCount = topicEndPosition - topicBodyStart
    if (topicByteCount > 65_535) {
      throw EncodeException(fieldPath = "MqttPublishV3Concrete.topic", reason = """UTF-8 byte length ${topicByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(topicSizePosition)
    val topicPrefix = topicByteCount.toUInt()
    buffer.writeUByte(((topicPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((topicPrefix and 0xFFu).toUByte())
    buffer.position(topicEndPosition)
    buffer.writeUShort(value.packetId.raw)
    JpegImageCodec.encode(buffer, value.payload, context)
  }

  override fun wireSize(`value`: MqttPublishV3Concrete, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val header = MqttFixedHeader(buffer.readUByte())
    val topicPrefixB0 = buffer.readUByte().toUInt()
    val topicPrefixB1 = buffer.readUByte().toUInt()
    val topicPrefix = ((topicPrefixB0 shl 8) or topicPrefixB1)
    if (topicPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "MqttPublishV3Concrete.topic", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = topicPrefix.toString())
    }
    val topicLength = topicPrefix.toInt()
    val topic = buffer.readString(topicLength, Charset.UTF8)
    val packetId = PacketId(buffer.readUShort())
    return Partial(header = header, topic = topic, packetId = packetId, buffer = buffer, context = context)
  }

  public class Partial internal constructor(
    public val `header`: MqttFixedHeader,
    public val topic: String,
    public val packetId: PacketId,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(): MqttPublishV3Concrete {
      val payload = JpegImageCodec.decode(buffer, context)
      return MqttPublishV3Concrete(header = header, topic = topic, packetId = packetId, payload = payload)
    }
  }
}
