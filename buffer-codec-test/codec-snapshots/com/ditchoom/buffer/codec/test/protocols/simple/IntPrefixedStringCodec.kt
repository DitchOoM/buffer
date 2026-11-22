package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireByteCount
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
      throw FrameTooLargeException(declaredBytes = namePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val nameLength = namePrefix.toInt()
    val name = buffer.readText(nameLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    return IntPrefixedString(name = name)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: IntPrefixedString,
    context: EncodeContext,
  ) {
    val nameSizePosition = buffer.position()
    repeat(4) { buffer.writeUByte(0u) }
    val nameBodyStart = buffer.position()
    buffer.writeText(value.name, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
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

  override fun wireBytes(`value`: IntPrefixedString, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  override fun sizeHint(`value`: IntPrefixedString, context: EncodeContext): Int = 4 + value.name.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 4) return FrameSize.NeedsMoreData
    val namePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val namePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val namePrefixB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val namePrefixB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val namePrefix = ((namePrefixB0 shl 24) or (namePrefixB1 shl 16) or (namePrefixB2 shl 8) or namePrefixB3).toUInt()
    if (namePrefix > (Int.MAX_VALUE - __offset - 4).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 4 + namePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 4 + namePrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }
}
