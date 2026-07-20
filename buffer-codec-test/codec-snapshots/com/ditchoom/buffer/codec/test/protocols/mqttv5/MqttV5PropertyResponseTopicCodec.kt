package com.ditchoom.buffer.codec.test.protocols.mqttv5

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
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object MqttV5PropertyResponseTopicCodec : Codec<MqttV5Property.ResponseTopic> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.ResponseTopic {
    val id = MqttV5PropertyId(buffer.readUByte())
    val valuePrefixB0 = buffer.readUByte().toUInt()
    val valuePrefixB1 = buffer.readUByte().toUInt()
    val valuePrefix = ((valuePrefixB0 shl 8) or valuePrefixB1)
    if (valuePrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "ResponseTopic.value", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = valuePrefix.toString())
    }
    val valueLength = valuePrefix.toInt()
    val value = buffer.readString(valueLength, Charset.UTF8)
    return MqttV5Property.ResponseTopic(id = id, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.ResponseTopic,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    val valueSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val valueBodyStart = buffer.position()
    buffer.writeString(value.value, Charset.UTF8)
    val valueEndPosition = buffer.position()
    val valueByteCount = valueEndPosition - valueBodyStart
    if (valueByteCount > 65_535) {
      throw EncodeException(fieldPath = "ResponseTopic.value", reason = """UTF-8 byte length ${valueByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(valueSizePosition)
    val valuePrefix = valueByteCount.toUInt()
    buffer.writeUByte(((valuePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((valuePrefix and 0xFFu).toUByte())
    buffer.position(valueEndPosition)
  }

  override fun wireSize(`value`: MqttV5Property.ResponseTopic, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val valuePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val valuePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val valuePrefix = ((valuePrefixB0 shl 8) or valuePrefixB1).toUInt()
    if (valuePrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "ResponseTopic.value", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + valuePrefix.toInt()}""")
    }
    __offset += 2 + valuePrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
