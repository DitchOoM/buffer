package com.ditchoom.buffer.codec.test.protocols.textpolicy

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.Utf8
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

public object TextPolicyMessageCodec : Codec<TextPolicyMessage> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TextPolicyMessage {
    val topicPrefixB0 = buffer.readUByte().toUInt()
    val topicPrefixB1 = buffer.readUByte().toUInt()
    val topicPrefix = ((topicPrefixB0 shl 8) or topicPrefixB1)
    if (topicPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = topicPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val topicLength = topicPrefix.toInt()
    val topic = buffer.readText(topicLength, Utf8.Strict)
    val notePrefixB0 = buffer.readUByte().toUInt()
    val notePrefixB1 = buffer.readUByte().toUInt()
    val notePrefix = ((notePrefixB0 shl 8) or notePrefixB1)
    if (notePrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = notePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val noteLength = notePrefix.toInt()
    val note = buffer.readText(noteLength, LenientHolder.policy)
    val payloadPrefixB0 = buffer.readUByte().toUInt()
    val payloadPrefixB1 = buffer.readUByte().toUInt()
    val payloadPrefix = ((payloadPrefixB0 shl 8) or payloadPrefixB1)
    if (payloadPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = payloadPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val payloadLength = payloadPrefix.toInt()
    val payload = buffer.readText(payloadLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    return TextPolicyMessage(topic = topic, note = note, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: TextPolicyMessage,
    context: EncodeContext,
  ) {
    val topicSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val topicBodyStart = buffer.position()
    buffer.writeText(value.topic, Utf8.Strict)
    val topicEndPosition = buffer.position()
    val topicByteCount = topicEndPosition - topicBodyStart
    if (topicByteCount > 65_535) {
      throw EncodeException(fieldPath = "TextPolicyMessage.topic", reason = """UTF-8 byte length ${topicByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(topicSizePosition)
    val topicPrefix = topicByteCount.toUInt()
    buffer.writeUByte(((topicPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((topicPrefix and 0xFFu).toUByte())
    buffer.position(topicEndPosition)
    val noteSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val noteBodyStart = buffer.position()
    buffer.writeText(value.note, LenientHolder.policy)
    val noteEndPosition = buffer.position()
    val noteByteCount = noteEndPosition - noteBodyStart
    if (noteByteCount > 65_535) {
      throw EncodeException(fieldPath = "TextPolicyMessage.note", reason = """UTF-8 byte length ${noteByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(noteSizePosition)
    val notePrefix = noteByteCount.toUInt()
    buffer.writeUByte(((notePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((notePrefix and 0xFFu).toUByte())
    buffer.position(noteEndPosition)
    val payloadSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val payloadBodyStart = buffer.position()
    buffer.writeText(value.payload, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val payloadEndPosition = buffer.position()
    val payloadByteCount = payloadEndPosition - payloadBodyStart
    if (payloadByteCount > 65_535) {
      throw EncodeException(fieldPath = "TextPolicyMessage.payload", reason = """UTF-8 byte length ${payloadByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(payloadSizePosition)
    val payloadPrefix = payloadByteCount.toUInt()
    buffer.writeUByte(((payloadPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((payloadPrefix and 0xFFu).toUByte())
    buffer.position(payloadEndPosition)
  }

  override fun wireBytes(`value`: TextPolicyMessage, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: TextPolicyMessage, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: TextPolicyMessage, context: EncodeContext): Int = 6 + value.topic.length + value.note.length + value.payload.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val topicPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val topicPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val topicPrefix = ((topicPrefixB0 shl 8) or topicPrefixB1).toUInt()
    if (topicPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + topicPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + topicPrefix.toInt()
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val notePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val notePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val notePrefix = ((notePrefixB0 shl 8) or notePrefixB1).toUInt()
    if (notePrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + notePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + notePrefix.toInt()
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val payloadPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val payloadPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val payloadPrefix = ((payloadPrefixB0 shl 8) or payloadPrefixB1).toUInt()
    if (payloadPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + payloadPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + payloadPrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
