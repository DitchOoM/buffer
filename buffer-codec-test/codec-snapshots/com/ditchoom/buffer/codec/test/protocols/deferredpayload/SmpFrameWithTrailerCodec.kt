package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayloadCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.String
import kotlin.UShort

public object SmpFrameWithTrailerCodec : Codec<SmpFrameWithTrailer> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SmpFrameWithTrailer {
    val payloadLength = buffer.readUShort()
    val payloadBytes = payloadLength.toInt()
    if (payloadBytes > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "SmpFrameWithTrailer.payload",
            bufferPosition = buffer.position(),
            expected = "a " + payloadBytes + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
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
      throw FrameTooLargeException(declaredBytes = notePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val noteLength = notePrefix.toInt()
    val note = buffer.readText(noteLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
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
    buffer.writeText(value.note, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
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

  override fun wireBytes(`value`: SmpFrameWithTrailer, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  override fun sizeHint(`value`: SmpFrameWithTrailer, context: EncodeContext): Int = 6 + value.note.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val payloadLengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val payloadLengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val payloadLength = ((payloadLengthB0 shl 8) or payloadLengthB1).toUInt().toUShort()
    __offset += 2
    val payloadBytes = payloadLength.toInt()
    if (payloadBytes < 0 || payloadBytes > Int.MAX_VALUE - __offset) {
      throw FrameTooLargeException(declaredBytes = if (payloadBytes < 0) payloadBytes.toLong() else __offset.toLong() + payloadBytes, maxBytes = Int.MAX_VALUE)
    }
    if (stream.available() - baseOffset < __offset + payloadBytes) return FrameSize.NeedsMoreData
    __offset += payloadBytes
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    __offset += 2
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val notePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val notePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val notePrefix = ((notePrefixB0 shl 8) or notePrefixB1).toUInt()
    if (notePrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + notePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + notePrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val payloadLength = buffer.readUShort()
    val __payloadStart = buffer.position()
    if (payloadLength.toInt() > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "SmpFrameWithTrailer.payload",
            bufferPosition = buffer.position(),
            expected = "a " + payloadLength.toInt() + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    val __payloadEnd = __payloadStart + payloadLength.toInt()
    buffer.position(__payloadEnd)
    val checksum = buffer.readUShort()
    val notePrefixB0 = buffer.readUByte().toUInt()
    val notePrefixB1 = buffer.readUByte().toUInt()
    val notePrefix = ((notePrefixB0 shl 8) or notePrefixB1)
    if (notePrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = notePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val noteLength = notePrefix.toInt()
    val note = buffer.readText(noteLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    return Partial(payloadLength = payloadLength, checksum = checksum, note = note, payloadStart = __payloadStart, payloadEnd = __payloadEnd, buffer = buffer, context = context)
  }

  public class Partial internal constructor(
    public val payloadLength: UShort,
    public val checksum: UShort,
    public val note: String,
    private val payloadStart: Int,
    private val payloadEnd: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(): SmpFrameWithTrailer {
      buffer.position(payloadStart)
      val __payloadSavedLimit = buffer.limit()
      buffer.setLimit(payloadEnd)
      return try {
        val payload = TextPayloadCodec.decode(buffer, context)
        if (buffer.position() != payloadEnd) {
          throw DecodeException(
                fieldPath = "SmpFrameWithTrailer.payload",
                bufferPosition = buffer.position(),
                expected = "the payload codec to consume all " + (payloadEnd - payloadStart) + " bytes",
                actual = (payloadEnd - buffer.position()).toString() + " bytes left unread",
              )
        }
        SmpFrameWithTrailer(payloadLength = payloadLength, payload = payload, checksum = checksum, note = note)
      } finally {
        buffer.setLimit(__payloadSavedLimit)
      }
    }
  }
}
