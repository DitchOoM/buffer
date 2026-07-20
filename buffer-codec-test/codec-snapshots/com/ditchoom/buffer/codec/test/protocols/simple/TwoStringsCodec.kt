package com.ditchoom.buffer.codec.test.protocols.simple

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

public object TwoStringsCodec : Codec<TwoStrings> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TwoStrings {
    val firstPrefixB0 = buffer.readUByte().toUInt()
    val firstPrefixB1 = buffer.readUByte().toUInt()
    val firstPrefix = ((firstPrefixB0 shl 8) or firstPrefixB1)
    if (firstPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "TwoStrings.first", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = firstPrefix.toString())
    }
    val firstLength = firstPrefix.toInt()
    val first = buffer.readString(firstLength, Charset.UTF8)
    val secondPrefixB0 = buffer.readUByte().toUInt()
    val secondPrefixB1 = buffer.readUByte().toUInt()
    val secondPrefix = ((secondPrefixB0 shl 8) or secondPrefixB1)
    if (secondPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "TwoStrings.second", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = secondPrefix.toString())
    }
    val secondLength = secondPrefix.toInt()
    val second = buffer.readString(secondLength, Charset.UTF8)
    return TwoStrings(first = first, second = second)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: TwoStrings,
    context: EncodeContext,
  ) {
    val firstSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val firstBodyStart = buffer.position()
    buffer.writeString(value.first, Charset.UTF8)
    val firstEndPosition = buffer.position()
    val firstByteCount = firstEndPosition - firstBodyStart
    if (firstByteCount > 65_535) {
      throw EncodeException(fieldPath = "TwoStrings.first", reason = """UTF-8 byte length ${firstByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(firstSizePosition)
    val firstPrefix = firstByteCount.toUInt()
    buffer.writeUByte(((firstPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((firstPrefix and 0xFFu).toUByte())
    buffer.position(firstEndPosition)
    val secondSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val secondBodyStart = buffer.position()
    buffer.writeString(value.second, Charset.UTF8)
    val secondEndPosition = buffer.position()
    val secondByteCount = secondEndPosition - secondBodyStart
    if (secondByteCount > 65_535) {
      throw EncodeException(fieldPath = "TwoStrings.second", reason = """UTF-8 byte length ${secondByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(secondSizePosition)
    val secondPrefix = secondByteCount.toUInt()
    buffer.writeUByte(((secondPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((secondPrefix and 0xFFu).toUByte())
    buffer.position(secondEndPosition)
  }

  override fun wireSize(`value`: TwoStrings, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val firstPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val firstPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val firstPrefix = ((firstPrefixB0 shl 8) or firstPrefixB1).toUInt()
    if (firstPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "TwoStrings.first", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + firstPrefix.toInt()}""")
    }
    __offset += 2 + firstPrefix.toInt()
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val secondPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val secondPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val secondPrefix = ((secondPrefixB0 shl 8) or secondPrefixB1).toUInt()
    if (secondPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "TwoStrings.second", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + secondPrefix.toInt()}""")
    }
    __offset += 2 + secondPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
