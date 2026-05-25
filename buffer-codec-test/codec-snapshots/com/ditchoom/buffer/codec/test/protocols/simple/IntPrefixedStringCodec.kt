package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object IntPrefixedStringCodec : Codec<IntPrefixedString> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): IntPrefixedString {
    val namePrefixB0 = buffer.readUByte().toUInt()
    val namePrefixB1 = buffer.readUByte().toUInt()
    val namePrefixB2 = buffer.readUByte().toUInt()
    val namePrefixB3 = buffer.readUByte().toUInt()
    val namePrefix = ((namePrefixB0 shl 24) or (namePrefixB1 shl 16) or (namePrefixB2 shl 8) or namePrefixB3)
    if (namePrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "IntPrefixedString.name", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = namePrefix.toString())
    }
    val nameLength = namePrefix.toInt()
    val name = buffer.readString(nameLength, Charset.UTF8)
    return IntPrefixedString(name = name)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: IntPrefixedString,
    context: EncodeContext,
  ) {
    val nameSizePosition = buffer.position()
    buffer.position(nameSizePosition + 4)
    val nameBodyStart = buffer.position()
    buffer.writeString(value.name, Charset.UTF8)
    val nameEndPosition = buffer.position()
    val nameByteCount = nameEndPosition - nameBodyStart
    buffer.position(nameSizePosition)
    val namePrefix = nameByteCount.toUInt()
    buffer.writeUByte(((namePrefix shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((namePrefix shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((namePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((namePrefix and 0xFFu).toUByte())
    buffer.position(nameEndPosition)
  }

  override fun wireSize(`value`: IntPrefixedString, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    val namePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val namePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val namePrefixB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val namePrefixB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val namePrefix = ((namePrefixB0 shl 24) or (namePrefixB1 shl 16) or (namePrefixB2 shl 8) or namePrefixB3).toUInt()
    if (namePrefix > (Int.MAX_VALUE - __offset - 4).toUInt()) {
      throw DecodeException(fieldPath = "IntPrefixedString.name", bufferPosition = baseOffset + __offset, expected = "__offset + 4 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 4 + namePrefix.toInt()}""")
    }
    __offset += 4 + namePrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
