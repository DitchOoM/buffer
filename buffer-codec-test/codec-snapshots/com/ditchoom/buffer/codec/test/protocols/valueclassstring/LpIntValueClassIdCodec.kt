package com.ditchoom.buffer.codec.test.protocols.valueclassstring

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

public object LpIntValueClassIdCodec : Codec<LpIntValueClassId> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LpIntValueClassId {
    val idPrefixB0 = buffer.readUByte().toUInt()
    val idPrefixB1 = buffer.readUByte().toUInt()
    val idPrefixB2 = buffer.readUByte().toUInt()
    val idPrefixB3 = buffer.readUByte().toUInt()
    val idPrefix = ((idPrefixB0 shl 24) or (idPrefixB1 shl 16) or (idPrefixB2 shl 8) or idPrefixB3)
    if (idPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "LpIntValueClassId.id", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = idPrefix.toString())
    }
    val idLength = idPrefix.toInt()
    val id = TraceId(buffer.readString(idLength, Charset.UTF8))
    return LpIntValueClassId(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LpIntValueClassId,
    context: EncodeContext,
  ) {
    val idSizePosition = buffer.position()
    buffer.position(idSizePosition + 4)
    val idBodyStart = buffer.position()
    buffer.writeString(value.id.hex, Charset.UTF8)
    val idEndPosition = buffer.position()
    val idByteCount = idEndPosition - idBodyStart
    buffer.position(idSizePosition)
    val idPrefix = idByteCount.toUInt()
    buffer.writeUByte(((idPrefix shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((idPrefix shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((idPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((idPrefix and 0xFFu).toUByte())
    buffer.position(idEndPosition)
  }

  override fun wireSize(`value`: LpIntValueClassId, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    val idPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val idPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val idPrefixB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val idPrefixB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val idPrefix = ((idPrefixB0 shl 24) or (idPrefixB1 shl 16) or (idPrefixB2 shl 8) or idPrefixB3).toUInt()
    if (idPrefix > (Int.MAX_VALUE - __offset - 4).toUInt()) {
      throw DecodeException(fieldPath = "LpIntValueClassId.id", bufferPosition = baseOffset + __offset, expected = "__offset + 4 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 4 + idPrefix.toInt()}""")
    }
    __offset += 4 + idPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
