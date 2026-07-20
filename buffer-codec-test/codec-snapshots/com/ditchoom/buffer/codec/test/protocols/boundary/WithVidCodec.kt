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

public object WithVidCodec : Codec<WithVid> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WithVid {
    val padPrefixB0 = buffer.readUByte().toUInt()
    val padPrefixB1 = buffer.readUByte().toUInt()
    val padPrefix = ((padPrefixB0 shl 8) or padPrefixB1)
    if (padPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "WithVid.pad", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = padPrefix.toString())
    }
    val padLength = padPrefix.toInt()
    val pad = buffer.readString(padLength, Charset.UTF8)
    val idPrefixB0 = buffer.readUByte().toUInt()
    val idPrefixB1 = buffer.readUByte().toUInt()
    val idPrefix = ((idPrefixB0 shl 8) or idPrefixB1)
    if (idPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "WithVid.id", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = idPrefix.toString())
    }
    val idLength = idPrefix.toInt()
    val id = BoundaryVid(buffer.readString(idLength, Charset.UTF8))
    return WithVid(pad = pad, id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WithVid,
    context: EncodeContext,
  ) {
    val padSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val padBodyStart = buffer.position()
    buffer.writeString(value.pad, Charset.UTF8)
    val padEndPosition = buffer.position()
    val padByteCount = padEndPosition - padBodyStart
    if (padByteCount > 65_535) {
      throw EncodeException(fieldPath = "WithVid.pad", reason = """UTF-8 byte length ${padByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(padSizePosition)
    val padPrefix = padByteCount.toUInt()
    buffer.writeUByte(((padPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((padPrefix and 0xFFu).toUByte())
    buffer.position(padEndPosition)
    val idSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val idBodyStart = buffer.position()
    buffer.writeString(value.id.value, Charset.UTF8)
    val idEndPosition = buffer.position()
    val idByteCount = idEndPosition - idBodyStart
    if (idByteCount > 65_535) {
      throw EncodeException(fieldPath = "WithVid.id", reason = """UTF-8 byte length ${idByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(idSizePosition)
    val idPrefix = idByteCount.toUInt()
    buffer.writeUByte(((idPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((idPrefix and 0xFFu).toUByte())
    buffer.position(idEndPosition)
  }

  override fun wireSize(`value`: WithVid, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val padPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val padPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val padPrefix = ((padPrefixB0 shl 8) or padPrefixB1).toUInt()
    if (padPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "WithVid.pad", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + padPrefix.toInt()}""")
    }
    __offset += 2 + padPrefix.toInt()
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val idPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val idPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val idPrefix = ((idPrefixB0 shl 8) or idPrefixB1).toUInt()
    if (idPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "WithVid.id", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + idPrefix.toInt()}""")
    }
    __offset += 2 + idPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
