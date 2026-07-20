package com.ditchoom.buffer.codec.test.protocols.valueclassstring

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

public object LpByteValueClassIdCodec : Codec<LpByteValueClassId> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LpByteValueClassId {
    val idPrefix = buffer.readUByte().toUInt()
    if (idPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "LpByteValueClassId.id", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = idPrefix.toString())
    }
    val idLength = idPrefix.toInt()
    val id = UserId(buffer.readString(idLength, Charset.UTF8))
    return LpByteValueClassId(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LpByteValueClassId,
    context: EncodeContext,
  ) {
    val idSizePosition = buffer.position()
    repeat(1) { buffer.writeUByte(0u) }
    val idBodyStart = buffer.position()
    buffer.writeString(value.id.value, Charset.UTF8)
    val idEndPosition = buffer.position()
    val idByteCount = idEndPosition - idBodyStart
    if (idByteCount > 255) {
      throw EncodeException(fieldPath = "LpByteValueClassId.id", reason = """UTF-8 byte length ${idByteCount} exceeds @LengthPrefixed(LengthPrefix.Byte) max 255""")
    }
    buffer.position(idSizePosition)
    val idPrefix = idByteCount.toUInt()
    buffer.writeUByte((idPrefix and 0xFFu).toUByte())
    buffer.position(idEndPosition)
  }

  override fun wireSize(`value`: LpByteValueClassId, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: LpByteValueClassId, context: EncodeContext): Int = 1 + value.id.value.length

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    val idPrefix = (stream.peekByte(baseOffset + __offset).toInt() and 0xFF).toUInt()
    if (idPrefix > (Int.MAX_VALUE - __offset - 1).toUInt()) {
      throw DecodeException(fieldPath = "LpByteValueClassId.id", bufferPosition = baseOffset + __offset, expected = "__offset + 1 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 1 + idPrefix.toInt()}""")
    }
    __offset += 1 + idPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
