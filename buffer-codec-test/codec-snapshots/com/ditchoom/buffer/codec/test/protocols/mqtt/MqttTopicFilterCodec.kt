package com.ditchoom.buffer.codec.test.protocols.mqtt

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

public object MqttTopicFilterCodec : Codec<MqttTopicFilter> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttTopicFilter {
    val filterPrefixB0 = buffer.readUByte().toUInt()
    val filterPrefixB1 = buffer.readUByte().toUInt()
    val filterPrefix = ((filterPrefixB0 shl 8) or filterPrefixB1)
    if (filterPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "MqttTopicFilter.filter", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = filterPrefix.toString())
    }
    val filterLength = filterPrefix.toInt()
    val filter = buffer.readString(filterLength, Charset.UTF8)
    val qos = buffer.readUByte()
    return MqttTopicFilter(filter = filter, qos = qos)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttTopicFilter,
    context: EncodeContext,
  ) {
    val filterSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val filterBodyStart = buffer.position()
    buffer.writeString(value.filter, Charset.UTF8)
    val filterEndPosition = buffer.position()
    val filterByteCount = filterEndPosition - filterBodyStart
    if (filterByteCount > 65_535) {
      throw EncodeException(fieldPath = "MqttTopicFilter.filter", reason = """UTF-8 byte length ${filterByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(filterSizePosition)
    val filterPrefix = filterByteCount.toUInt()
    buffer.writeUByte(((filterPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((filterPrefix and 0xFFu).toUByte())
    buffer.position(filterEndPosition)
    buffer.writeUByte(value.qos)
  }

  override fun wireSize(`value`: MqttTopicFilter, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val filterPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val filterPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val filterPrefix = ((filterPrefixB0 shl 8) or filterPrefixB1).toUInt()
    if (filterPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "MqttTopicFilter.filter", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + filterPrefix.toInt()}""")
    }
    __offset += 2 + filterPrefix.toInt()
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
