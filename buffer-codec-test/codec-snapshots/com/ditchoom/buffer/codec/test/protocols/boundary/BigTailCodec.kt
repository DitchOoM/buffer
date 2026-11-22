package com.ditchoom.buffer.codec.test.protocols.boundary

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

public object BigTailCodec : Codec<BigTail> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BigTail {
    val aPrefixB0 = buffer.readUByte().toUInt()
    val aPrefixB1 = buffer.readUByte().toUInt()
    val aPrefix = ((aPrefixB0 shl 8) or aPrefixB1)
    if (aPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = aPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val aLength = aPrefix.toInt()
    val a = buffer.readText(aLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val bPrefixB0 = buffer.readUByte().toUInt()
    val bPrefixB1 = buffer.readUByte().toUInt()
    val bPrefixB2 = buffer.readUByte().toUInt()
    val bPrefixB3 = buffer.readUByte().toUInt()
    val bPrefix = ((bPrefixB0 shl 24) or (bPrefixB1 shl 16) or (bPrefixB2 shl 8) or bPrefixB3)
    if (bPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = bPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val bLength = bPrefix.toInt()
    val b = buffer.readText(bLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    return BigTail(a = a, b = b)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BigTail,
    context: EncodeContext,
  ) {
    val aSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val aBodyStart = buffer.position()
    buffer.writeText(value.a, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val aEndPosition = buffer.position()
    val aByteCount = aEndPosition - aBodyStart
    if (aByteCount > 65_535) {
      throw EncodeException(fieldPath = "BigTail.a", reason = """UTF-8 byte length ${aByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(aSizePosition)
    val aPrefix = aByteCount.toUInt()
    buffer.writeUByte(((aPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((aPrefix and 0xFFu).toUByte())
    buffer.position(aEndPosition)
    val bSizePosition = buffer.position()
    repeat(4) { buffer.writeUByte(0u) }
    val bBodyStart = buffer.position()
    buffer.writeText(value.b, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val bEndPosition = buffer.position()
    val bByteCount = bEndPosition - bBodyStart
    buffer.position(bSizePosition)
    val bPrefix = bByteCount.toUInt()
    buffer.writeUByte(((bPrefix shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((bPrefix shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((bPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((bPrefix and 0xFFu).toUByte())
    buffer.position(bEndPosition)
  }

  override fun wireBytes(`value`: BigTail, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  override fun sizeHint(`value`: BigTail, context: EncodeContext): Int = 6 + value.a.length + value.b.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val aPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val aPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val aPrefix = ((aPrefixB0 shl 8) or aPrefixB1).toUInt()
    if (aPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + aPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + aPrefix.toInt()
    if (stream.available() - baseOffset < __offset + 4) return FrameSize.NeedsMoreData
    val bPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val bPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val bPrefixB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val bPrefixB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val bPrefix = ((bPrefixB0 shl 24) or (bPrefixB1 shl 16) or (bPrefixB2 shl 8) or bPrefixB3).toUInt()
    if (bPrefix > (Int.MAX_VALUE - __offset - 4).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 4 + bPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 4 + bPrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }
}
