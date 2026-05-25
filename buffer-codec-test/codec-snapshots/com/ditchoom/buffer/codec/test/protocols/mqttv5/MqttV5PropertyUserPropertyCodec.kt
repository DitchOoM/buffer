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

public object MqttV5PropertyUserPropertyCodec : Codec<MqttV5Property.UserProperty> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.UserProperty {
    val id = MqttV5PropertyId(buffer.readUByte())
    val keyPrefixB0 = buffer.readUByte().toUInt()
    val keyPrefixB1 = buffer.readUByte().toUInt()
    val keyPrefix = ((keyPrefixB0 shl 8) or keyPrefixB1)
    if (keyPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "UserProperty.key", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = keyPrefix.toString())
    }
    val keyLength = keyPrefix.toInt()
    val key = buffer.readString(keyLength, Charset.UTF8)
    val valuePrefixB0 = buffer.readUByte().toUInt()
    val valuePrefixB1 = buffer.readUByte().toUInt()
    val valuePrefix = ((valuePrefixB0 shl 8) or valuePrefixB1)
    if (valuePrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "UserProperty.value", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = valuePrefix.toString())
    }
    val valueLength = valuePrefix.toInt()
    val value = buffer.readString(valueLength, Charset.UTF8)
    return MqttV5Property.UserProperty(id = id, key = key, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.UserProperty,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    val keySizePosition = buffer.position()
    buffer.position(keySizePosition + 2)
    val keyBodyStart = buffer.position()
    buffer.writeString(value.key, Charset.UTF8)
    val keyEndPosition = buffer.position()
    val keyByteCount = keyEndPosition - keyBodyStart
    if (keyByteCount > 65_535) {
      throw EncodeException(fieldPath = "UserProperty.key", reason = """UTF-8 byte length ${keyByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(keySizePosition)
    val keyPrefix = keyByteCount.toUInt()
    buffer.writeUByte(((keyPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((keyPrefix and 0xFFu).toUByte())
    buffer.position(keyEndPosition)
    val valueSizePosition = buffer.position()
    buffer.position(valueSizePosition + 2)
    val valueBodyStart = buffer.position()
    buffer.writeString(value.value, Charset.UTF8)
    val valueEndPosition = buffer.position()
    val valueByteCount = valueEndPosition - valueBodyStart
    if (valueByteCount > 65_535) {
      throw EncodeException(fieldPath = "UserProperty.value", reason = """UTF-8 byte length ${valueByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(valueSizePosition)
    val valuePrefix = valueByteCount.toUInt()
    buffer.writeUByte(((valuePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((valuePrefix and 0xFFu).toUByte())
    buffer.position(valueEndPosition)
  }

  override fun wireSize(`value`: MqttV5Property.UserProperty, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val keyPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val keyPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val keyPrefix = ((keyPrefixB0 shl 8) or keyPrefixB1).toUInt()
    if (keyPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "UserProperty.key", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + keyPrefix.toInt()}""")
    }
    __offset += 2 + keyPrefix.toInt()
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val valuePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val valuePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val valuePrefix = ((valuePrefixB0 shl 8) or valuePrefixB1).toUInt()
    if (valuePrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "UserProperty.value", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + valuePrefix.toInt()}""")
    }
    __offset += 2 + valuePrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
