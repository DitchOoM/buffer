package com.ditchoom.buffer.codec.test.protocols.boundary

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

public object BigTailCodec : Codec<BigTail> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BigTail {
    val aPrefixB0 = buffer.readUByte().toUInt()
    val aPrefixB1 = buffer.readUByte().toUInt()
    val aPrefix = ((aPrefixB0 shl 8) or aPrefixB1)
    if (aPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "BigTail.a", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = aPrefix.toString())
    }
    val aLength = aPrefix.toInt()
    val a = buffer.readString(aLength, Charset.UTF8)
    val bPrefixB0 = buffer.readUByte().toUInt()
    val bPrefixB1 = buffer.readUByte().toUInt()
    val bPrefixB2 = buffer.readUByte().toUInt()
    val bPrefixB3 = buffer.readUByte().toUInt()
    val bPrefix = ((bPrefixB0 shl 24) or (bPrefixB1 shl 16) or (bPrefixB2 shl 8) or bPrefixB3)
    if (bPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "BigTail.b", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = bPrefix.toString())
    }
    val bLength = bPrefix.toInt()
    val b = buffer.readString(bLength, Charset.UTF8)
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
    buffer.writeString(value.a, Charset.UTF8)
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
    buffer.writeString(value.b, Charset.UTF8)
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

  override fun wireSize(`value`: BigTail, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val aPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val aPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val aPrefix = ((aPrefixB0 shl 8) or aPrefixB1).toUInt()
    if (aPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "BigTail.a", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + aPrefix.toInt()}""")
    }
    __offset += 2 + aPrefix.toInt()
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    val bPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val bPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val bPrefixB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val bPrefixB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val bPrefix = ((bPrefixB0 shl 24) or (bPrefixB1 shl 16) or (bPrefixB2 shl 8) or bPrefixB3).toUInt()
    if (bPrefix > (Int.MAX_VALUE - __offset - 4).toUInt()) {
      throw DecodeException(fieldPath = "BigTail.b", bufferPosition = baseOffset + __offset, expected = "__offset + 4 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 4 + bPrefix.toInt()}""")
    }
    __offset += 4 + bPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
