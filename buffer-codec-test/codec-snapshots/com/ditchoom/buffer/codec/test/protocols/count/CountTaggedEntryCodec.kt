package com.ditchoom.buffer.codec.test.protocols.count

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

public object CountTaggedEntryCodec : Codec<CountTaggedEntry> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountTaggedEntry {
    val tagPrefixB0 = buffer.readUByte().toUInt()
    val tagPrefixB1 = buffer.readUByte().toUInt()
    val tagPrefix = ((tagPrefixB0 shl 8) or tagPrefixB1)
    if (tagPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "CountTaggedEntry.tag", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = tagPrefix.toString())
    }
    val tagLength = tagPrefix.toInt()
    val tag = CountTag(buffer.readString(tagLength, Charset.UTF8))
    return CountTaggedEntry(tag = tag)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountTaggedEntry,
    context: EncodeContext,
  ) {
    val tagSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val tagBodyStart = buffer.position()
    buffer.writeString(value.tag.value, Charset.UTF8)
    val tagEndPosition = buffer.position()
    val tagByteCount = tagEndPosition - tagBodyStart
    if (tagByteCount > 65_535) {
      throw EncodeException(fieldPath = "CountTaggedEntry.tag", reason = """UTF-8 byte length ${tagByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(tagSizePosition)
    val tagPrefix = tagByteCount.toUInt()
    buffer.writeUByte(((tagPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((tagPrefix and 0xFFu).toUByte())
    buffer.position(tagEndPosition)
  }

  override fun wireSize(`value`: CountTaggedEntry, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val tagPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val tagPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val tagPrefix = ((tagPrefixB0 shl 8) or tagPrefixB1).toUInt()
    if (tagPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "CountTaggedEntry.tag", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + tagPrefix.toInt()}""")
    }
    __offset += 2 + tagPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
