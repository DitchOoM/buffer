package com.ditchoom.buffer.codec.test.protocols.simple

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

public object BytePrefixedStringCodec : Codec<BytePrefixedString> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BytePrefixedString {
    val namePrefix = buffer.readUByte().toUInt()
    if (namePrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = namePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val nameLength = namePrefix.toInt()
    val name = buffer.readText(nameLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
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
    buffer.writeText(value.name, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
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

  override fun wireBytes(`value`: BytePrefixedString, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  override fun sizeHint(`value`: BytePrefixedString, context: EncodeContext): Int = 1 + value.name.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    val namePrefix = (stream.peekByte(baseOffset + __offset).toInt() and 0xFF).toUInt()
    if (namePrefix > (Int.MAX_VALUE - __offset - 1).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 1 + namePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 1 + namePrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }
}
