package com.ditchoom.buffer.codec.test.protocols.visibility

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

internal object InternalPacketCodec : Codec<InternalPacket> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): InternalPacket {
    val id = buffer.readInt()
    val namePrefixB0 = buffer.readUByte().toUInt()
    val namePrefixB1 = buffer.readUByte().toUInt()
    val namePrefix = ((namePrefixB0 shl 8) or namePrefixB1)
    if (namePrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = namePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val nameLength = namePrefix.toInt()
    val name = buffer.readText(nameLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    return InternalPacket(id = id, name = name)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: InternalPacket,
    context: EncodeContext,
  ) {
    buffer.writeInt(value.id)
    val nameSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val nameBodyStart = buffer.position()
    buffer.writeText(value.name, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val nameEndPosition = buffer.position()
    val nameByteCount = nameEndPosition - nameBodyStart
    if (nameByteCount > 65_535) {
      throw EncodeException(fieldPath = "InternalPacket.name", reason = """UTF-8 byte length ${nameByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(nameSizePosition)
    val namePrefix = nameByteCount.toUInt()
    buffer.writeUByte(((namePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((namePrefix and 0xFFu).toUByte())
    buffer.position(nameEndPosition)
  }

  override fun wireBytes(`value`: InternalPacket, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  override fun sizeHint(`value`: InternalPacket, context: EncodeContext): Int = 6 + value.name.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 4) return FrameSize.NeedsMoreData
    __offset += 4
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val namePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val namePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val namePrefix = ((namePrefixB0 shl 8) or namePrefixB1).toUInt()
    if (namePrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + namePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + namePrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }
}
