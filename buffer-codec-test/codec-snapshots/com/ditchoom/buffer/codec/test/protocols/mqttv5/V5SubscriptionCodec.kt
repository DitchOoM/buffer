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

public object V5SubscriptionCodec : Codec<V5Subscription> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5Subscription {
    val topicFilterPrefixB0 = buffer.readUByte().toUInt()
    val topicFilterPrefixB1 = buffer.readUByte().toUInt()
    val topicFilterPrefix = ((topicFilterPrefixB0 shl 8) or topicFilterPrefixB1)
    if (topicFilterPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "V5Subscription.topicFilter", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = topicFilterPrefix.toString())
    }
    val topicFilterLength = topicFilterPrefix.toInt()
    val topicFilter = buffer.readString(topicFilterLength, Charset.UTF8)
    val subscriptionOptions = V5SubscriptionOptions(buffer.readUByte())
    return V5Subscription(topicFilter = topicFilter, subscriptionOptions = subscriptionOptions)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5Subscription,
    context: EncodeContext,
  ) {
    val topicFilterSizePosition = buffer.position()
    buffer.position(topicFilterSizePosition + 2)
    val topicFilterBodyStart = buffer.position()
    buffer.writeString(value.topicFilter, Charset.UTF8)
    val topicFilterEndPosition = buffer.position()
    val topicFilterByteCount = topicFilterEndPosition - topicFilterBodyStart
    if (topicFilterByteCount > 65_535) {
      throw EncodeException(fieldPath = "V5Subscription.topicFilter", reason = """UTF-8 byte length ${topicFilterByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(topicFilterSizePosition)
    val topicFilterPrefix = topicFilterByteCount.toUInt()
    buffer.writeUByte(((topicFilterPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((topicFilterPrefix and 0xFFu).toUByte())
    buffer.position(topicFilterEndPosition)
    buffer.writeUByte(value.subscriptionOptions.raw)
  }

  override fun wireSize(`value`: V5Subscription, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val topicFilterPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val topicFilterPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val topicFilterPrefix = ((topicFilterPrefixB0 shl 8) or topicFilterPrefixB1).toUInt()
    if (topicFilterPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "V5Subscription.topicFilter", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + topicFilterPrefix.toInt()}""")
    }
    __offset += 2 + topicFilterPrefix.toInt()
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
