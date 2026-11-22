package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object MqttV5PropertyContentTypeCodec : Codec<MqttV5Property.ContentType> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.ContentType {
    val id = MqttV5PropertyId(buffer.readUByte())
    val valuePrefixB0 = buffer.readUByte().toUInt()
    val valuePrefixB1 = buffer.readUByte().toUInt()
    val valuePrefix = ((valuePrefixB0 shl 8) or valuePrefixB1)
    if (valuePrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = valuePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val valueLength = valuePrefix.toInt()
    val value = buffer.readText(valueLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    return MqttV5Property.ContentType(id = id, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.ContentType,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    val valueSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val valueBodyStart = buffer.position()
    buffer.writeText(value.value, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val valueEndPosition = buffer.position()
    val valueByteCount = valueEndPosition - valueBodyStart
    if (valueByteCount > 65_535) {
      throw EncodeException(fieldPath = "ContentType.value", reason = """UTF-8 byte length ${valueByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(valueSizePosition)
    val valuePrefix = valueByteCount.toUInt()
    buffer.writeUByte(((valuePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((valuePrefix and 0xFFu).toUByte())
    buffer.position(valueEndPosition)
  }

  override fun wireBytes(`value`: MqttV5Property.ContentType, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  override fun sizeHint(`value`: MqttV5Property.ContentType, context: EncodeContext): Int = 3 + value.value.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val valuePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val valuePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val valuePrefix = ((valuePrefixB0 shl 8) or valuePrefixB1).toUInt()
    if (valuePrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + valuePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + valuePrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }
}
