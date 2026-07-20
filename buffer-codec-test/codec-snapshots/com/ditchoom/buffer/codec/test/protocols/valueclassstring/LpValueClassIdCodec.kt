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

public object LpValueClassIdCodec : Codec<LpValueClassId> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LpValueClassId {
    val idPrefixB0 = buffer.readUByte().toUInt()
    val idPrefixB1 = buffer.readUByte().toUInt()
    val idPrefix = ((idPrefixB0 shl 8) or idPrefixB1)
    if (idPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "LpValueClassId.id", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = idPrefix.toString())
    }
    val idLength = idPrefix.toInt()
    val id = UserId(buffer.readString(idLength, Charset.UTF8))
    return LpValueClassId(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LpValueClassId,
    context: EncodeContext,
  ) {
    val idSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val idBodyStart = buffer.position()
    buffer.writeString(value.id.value, Charset.UTF8)
    val idEndPosition = buffer.position()
    val idByteCount = idEndPosition - idBodyStart
    if (idByteCount > 65_535) {
      throw EncodeException(fieldPath = "LpValueClassId.id", reason = """UTF-8 byte length ${idByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(idSizePosition)
    val idPrefix = idByteCount.toUInt()
    buffer.writeUByte(((idPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((idPrefix and 0xFFu).toUByte())
    buffer.position(idEndPosition)
  }

  override fun wireSize(`value`: LpValueClassId, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val idPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val idPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val idPrefix = ((idPrefixB0 shl 8) or idPrefixB1).toUInt()
    if (idPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "LpValueClassId.id", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + idPrefix.toInt()}""")
    }
    __offset += 2 + idPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
