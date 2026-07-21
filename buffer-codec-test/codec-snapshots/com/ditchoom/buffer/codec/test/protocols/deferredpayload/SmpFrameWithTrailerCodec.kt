package com.ditchoom.buffer.codec.test.protocols.deferredpayload

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
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayloadCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object SmpFrameWithTrailerCodec : Codec<SmpFrameWithTrailer> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SmpFrameWithTrailer {
    val payloadLength = buffer.readUShort()
    val payloadBytes = payloadLength.toInt()
    val payloadOuterLimit = buffer.limit()
    val payloadEnd = buffer.position() + payloadBytes
    buffer.setLimit(payloadEnd)
    val payload = try {
      TextPayloadCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(payloadOuterLimit)
    }
    if (buffer.position() != payloadEnd) {
      throw DecodeException(
            fieldPath = "SmpFrameWithTrailer.payload",
            bufferPosition = buffer.position(),
            expected = "the payload codec to consume all " + payloadBytes + " bytes",
            actual = (payloadEnd - buffer.position()).toString() + " bytes left unread",
          )
    }
    val checksum = buffer.readUShort()
    val notePrefixB0 = buffer.readUByte().toUInt()
    val notePrefixB1 = buffer.readUByte().toUInt()
    val notePrefix = ((notePrefixB0 shl 8) or notePrefixB1)
    if (notePrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "SmpFrameWithTrailer.note", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = notePrefix.toString())
    }
    val noteLength = notePrefix.toInt()
    val note = buffer.readString(noteLength, Charset.UTF8)
    return SmpFrameWithTrailer(payloadLength = payloadLength, payload = payload, checksum = checksum, note = note)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SmpFrameWithTrailer,
    context: EncodeContext,
  ) {
    buffer.writeUShort(value.payloadLength)
    TextPayloadCodec.encode(buffer, value.payload, context)
    buffer.writeUShort(value.checksum)
    val noteSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val noteBodyStart = buffer.position()
    buffer.writeString(value.note, Charset.UTF8)
    val noteEndPosition = buffer.position()
    val noteByteCount = noteEndPosition - noteBodyStart
    if (noteByteCount > 65_535) {
      throw EncodeException(fieldPath = "SmpFrameWithTrailer.note", reason = """UTF-8 byte length ${noteByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(noteSizePosition)
    val notePrefix = noteByteCount.toUInt()
    buffer.writeUByte(((notePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((notePrefix and 0xFFu).toUByte())
    buffer.position(noteEndPosition)
  }

  override fun wireSize(`value`: SmpFrameWithTrailer, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: SmpFrameWithTrailer, context: EncodeContext): Int = 6 + value.note.length

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val payloadLengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val payloadLengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val payloadLength = ((payloadLengthB0 shl 8) or payloadLengthB1).toUInt().toUShort()
    __offset += 2
    val payloadBytes = payloadLength.toInt()
    if (stream.available() - baseOffset < __offset + payloadBytes) return PeekResult.NeedsMoreData
    __offset += payloadBytes
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    __offset += 2
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val notePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val notePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val notePrefix = ((notePrefixB0 shl 8) or notePrefixB1).toUInt()
    if (notePrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "SmpFrameWithTrailer.note", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + notePrefix.toInt()}""")
    }
    __offset += 2 + notePrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
