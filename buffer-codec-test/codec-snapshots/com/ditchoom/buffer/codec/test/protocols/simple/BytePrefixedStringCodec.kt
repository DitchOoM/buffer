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

public object BytePrefixedStringCodec : Codec<BytePrefixedString> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BytePrefixedString {
    val namePrefix = buffer.readUByte().toUInt()
    if (namePrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "BytePrefixedString.name", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = namePrefix.toString())
    }
    val nameLength = namePrefix.toInt()
    val name = buffer.readString(nameLength, Charset.UTF8)
    return BytePrefixedString(name = name)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BytePrefixedString,
    context: EncodeContext,
  ) {
    val nameSizePosition = buffer.position()
    repeat(1) { buffer.writeUByte(0u) }
    val nameBodyStart = buffer.position()
    buffer.writeString(value.name, Charset.UTF8)
    val nameEndPosition = buffer.position()
    val nameByteCount = nameEndPosition - nameBodyStart
    if (nameByteCount > 255) {
      throw EncodeException(fieldPath = "BytePrefixedString.name", reason = """UTF-8 byte length ${nameByteCount} exceeds @LengthPrefixed(LengthPrefix.Byte) max 255""")
    }
    buffer.position(nameSizePosition)
    val namePrefix = nameByteCount.toUInt()
    buffer.writeUByte((namePrefix and 0xFFu).toUByte())
    buffer.position(nameEndPosition)
  }

  override fun wireSize(`value`: BytePrefixedString, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    val namePrefix = (stream.peekByte(baseOffset + __offset).toInt() and 0xFF).toUInt()
    if (namePrefix > (Int.MAX_VALUE - __offset - 1).toUInt()) {
      throw DecodeException(fieldPath = "BytePrefixedString.name", bufferPosition = baseOffset + __offset, expected = "__offset + 1 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 1 + namePrefix.toInt()}""")
    }
    __offset += 1 + namePrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
