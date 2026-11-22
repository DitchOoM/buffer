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
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object V5SubscriptionCodec : Codec<V5Subscription> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5Subscription {
    val topicFilterPrefixB0 = buffer.readUByte().toUInt()
    val topicFilterPrefixB1 = buffer.readUByte().toUInt()
    val topicFilterPrefix = ((topicFilterPrefixB0 shl 8) or topicFilterPrefixB1)
    if (topicFilterPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = topicFilterPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val topicFilterLength = topicFilterPrefix.toInt()
    val topicFilter = buffer.readText(topicFilterLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val subscriptionOptions = V5SubscriptionOptions(buffer.readUByte())
    return V5Subscription(topicFilter = topicFilter, subscriptionOptions = subscriptionOptions)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5Subscription,
    context: EncodeContext,
  ) {
    val topicFilterSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val topicFilterBodyStart = buffer.position()
    buffer.writeText(value.topicFilter, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
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

  override fun wireBytes(`value`: V5Subscription, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: V5Subscription, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: V5Subscription, context: EncodeContext): Int = 3 + value.topicFilter.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val topicFilterPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val topicFilterPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val topicFilterPrefix = ((topicFilterPrefixB0 shl 8) or topicFilterPrefixB1).toUInt()
    if (topicFilterPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + topicFilterPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + topicFilterPrefix.toInt()
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    return FrameSize.declared(0, __offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
