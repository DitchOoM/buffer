package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.ByteOrder
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
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object TlsRecordCodec : Codec<TlsRecord> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TlsRecord {
    val contentType = buffer.readUByte()
    val legacyRecordVersionRaw = buffer.readShort()
    val legacyRecordVersion = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) legacyRecordVersionRaw else swapBytes(legacyRecordVersionRaw)).toUShort()
    val fragmentPrefixB0 = buffer.readUByte().toUInt()
    val fragmentPrefixB1 = buffer.readUByte().toUInt()
    val fragmentPrefix = ((fragmentPrefixB0 shl 8) or fragmentPrefixB1)
    if (fragmentPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "TlsRecord.fragment", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = fragmentPrefix.toString())
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
    when (val fragmentWireSize = TlsHandshakeCodec.wireSize(value.fragment, context)) {
      is WireSize.Exact -> {
        val fragmentByteCount = fragmentWireSize.bytes
        if (fragmentByteCount > 65_535) {
          throw EncodeException(fieldPath = "TlsRecord.fragment", reason = """encoded message byte length ${fragmentByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
        }
        val fragmentPrefix = fragmentByteCount.toUInt()
        buffer.writeUByte(((fragmentPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((fragmentPrefix and 0xFFu).toUByte())
        val fragmentBodyStart = buffer.position()
        TlsHandshakeCodec.encode(buffer, value.fragment, context)
        if (buffer.position() - fragmentBodyStart != fragmentByteCount) {
          throw EncodeException(fieldPath = "TlsRecord.fragment", reason = """wireSize declared ${fragmentByteCount} bytes but encode wrote ${buffer.position() - fragmentBodyStart} — the codec's wireSize and encode disagree""")
        }
      }
      WireSize.BackPatch -> {
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
      }
    }
  }

  override fun wireSize(`value`: TlsRecord, context: EncodeContext): WireSize = when (val fragmentSize = TlsHandshakeCodec.wireSize(value.fragment, context)) {
    is WireSize.Exact -> WireSize.Exact(5 + fragmentSize.bytes)
    WireSize.BackPatch -> WireSize.BackPatch
  }

  override fun sizeHint(`value`: TlsRecord, context: EncodeContext): Int = 5 + TlsHandshakeCodec.sizeHint(value.fragment, context)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    __offset += 2
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val fragmentPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val fragmentPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val fragmentPrefix = ((fragmentPrefixB0 shl 8) or fragmentPrefixB1).toUInt()
    if (fragmentPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "TlsRecord.fragment", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + fragmentPrefix.toInt()}""")
    }
    __offset += 2 + fragmentPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
