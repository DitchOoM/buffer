package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object TlsRecordCodec : Codec<TlsRecord> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TlsRecord {
    val contentType = buffer.readUByte()
    val legacyRecordVersionRaw = buffer.readShort()
    val legacyRecordVersion = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) legacyRecordVersionRaw else swapBytes(legacyRecordVersionRaw)).toUShort()
    val fragmentPrefixB0 = buffer.readUByte().toUInt()
    val fragmentPrefixB1 = buffer.readUByte().toUInt()
    val fragmentPrefix = ((fragmentPrefixB0 shl 8) or fragmentPrefixB1)
    if (fragmentPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = fragmentPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val fragmentLength = fragmentPrefix.toInt()
    if (fragmentLength > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "TlsRecord.fragment",
            bufferPosition = buffer.position(),
            expected = "a " + fragmentLength + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    val fragmentOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + fragmentLength)
    val fragment = try {
      TlsHandshakeCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(fragmentOuterLimit)
    }
    return TlsRecord(contentType = contentType, legacyRecordVersion = legacyRecordVersion, fragment = fragment)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: TlsRecord,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.contentType)
    val legacyRecordVersionRaw = value.legacyRecordVersion.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) legacyRecordVersionRaw else swapBytes(legacyRecordVersionRaw))
    TlsHandshakeCodec.wireBytes(value.fragment, context).fold(
      exact = { fragmentWireBytes ->
        if (fragmentWireBytes > Int.MAX_VALUE) {
          throw EncodeException(fieldPath = "TlsRecord.fragment", reason = """encoded message byte length ${fragmentWireBytes} exceeds Int.MAX_VALUE""")
        }
        val fragmentByteCount = fragmentWireBytes.toInt()
        if (fragmentByteCount > 65_535) {
          throw EncodeException(fieldPath = "TlsRecord.fragment", reason = """encoded message byte length ${fragmentByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
        }
        val fragmentPrefix = fragmentByteCount.toUInt()
        buffer.writeUByte(((fragmentPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((fragmentPrefix and 0xFFu).toUByte())
        val fragmentBodyStart = buffer.position()
        TlsHandshakeCodec.encode(buffer, value.fragment, context)
        if (buffer.position() - fragmentBodyStart != fragmentByteCount) {
          throw EncodeException(fieldPath = "TlsRecord.fragment", reason = """wireBytes declared ${fragmentByteCount} bytes but encode wrote ${buffer.position() - fragmentBodyStart} — the codec's wireBytes and encode disagree""")
        }
      },
      backPatch = {
        val fragmentSizePosition = buffer.position()
        repeat(2) { buffer.writeUByte(0u) }
        val fragmentBodyStart = buffer.position()
        TlsHandshakeCodec.encode(buffer, value.fragment, context)
        val fragmentEndPosition = buffer.position()
        val fragmentPatchByteCount = fragmentEndPosition - fragmentBodyStart
        if (fragmentPatchByteCount > 65_535) {
          throw EncodeException(fieldPath = "TlsRecord.fragment", reason = """encoded message byte length ${fragmentPatchByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
        }
        buffer.position(fragmentSizePosition)
        val fragmentPatchPrefix = fragmentPatchByteCount.toUInt()
        buffer.writeUByte(((fragmentPatchPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((fragmentPatchPrefix and 0xFFu).toUByte())
        buffer.position(fragmentEndPosition)
      },
    )
  }

  override fun wireBytes(`value`: TlsRecord, context: EncodeContext): WireByteCount {
    val __fragmentSize = TlsHandshakeCodec.wireBytes(value.fragment, context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    return WireByteCount.exact(5L + __fragmentSize)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: TlsRecord, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: TlsRecord, context: EncodeContext): Int = 5 + TlsHandshakeCodec.sizeHint(value.fragment, context)

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    __offset += 2
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val fragmentPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val fragmentPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val fragmentPrefix = ((fragmentPrefixB0 shl 8) or fragmentPrefixB1).toUInt()
    if (fragmentPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + fragmentPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + fragmentPrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
