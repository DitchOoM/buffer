package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object WavFmtChunkCodec : Codec<WavFmtChunk> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WavFmtChunk {
    val fourCCRaw = buffer.readInt()
    val fourCC = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) fourCCRaw else swapBytes(fourCCRaw)).toUInt()
    val bodyPrefixB0 = buffer.readUByte().toUInt()
    val bodyPrefixB1 = buffer.readUByte().toUInt()
    val bodyPrefixB2 = buffer.readUByte().toUInt()
    val bodyPrefixB3 = buffer.readUByte().toUInt()
    val bodyPrefix = (bodyPrefixB0 or (bodyPrefixB1 shl 8) or (bodyPrefixB2 shl 16) or (bodyPrefixB3 shl 24))
    if (bodyPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "WavFmtChunk.body", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = bodyPrefix.toString())
    }
    val bodyLength = bodyPrefix.toInt()
    val bodyOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + bodyLength)
    val body = try {
      WavFmtBodyCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(bodyOuterLimit)
    }
    return WavFmtChunk(fourCC = fourCC, body = body)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WavFmtChunk,
    context: EncodeContext,
  ) {
    val fourCCRaw = value.fourCC.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) fourCCRaw else swapBytes(fourCCRaw))
    when (val bodyWireSize = WavFmtBodyCodec.wireSize(value.body, context)) {
      is WireSize.Exact -> {
        val bodyByteCount = bodyWireSize.bytes
        val bodyPrefix = bodyByteCount.toUInt()
        buffer.writeUByte((bodyPrefix and 0xFFu).toUByte())
        buffer.writeUByte(((bodyPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte(((bodyPrefix shr 16) and 0xFFu).toUByte())
        buffer.writeUByte(((bodyPrefix shr 24) and 0xFFu).toUByte())
        WavFmtBodyCodec.encode(buffer, value.body, context)
      }
      WireSize.BackPatch -> {
        val bodySizePosition = buffer.position()
        repeat(4) { buffer.writeUByte(0u) }
        val bodyBodyStart = buffer.position()
        WavFmtBodyCodec.encode(buffer, value.body, context)
        val bodyEndPosition = buffer.position()
        val bodyPatchByteCount = bodyEndPosition - bodyBodyStart
        buffer.position(bodySizePosition)
        val bodyPatchPrefix = bodyPatchByteCount.toUInt()
        buffer.writeUByte((bodyPatchPrefix and 0xFFu).toUByte())
        buffer.writeUByte(((bodyPatchPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte(((bodyPatchPrefix shr 16) and 0xFFu).toUByte())
        buffer.writeUByte(((bodyPatchPrefix shr 24) and 0xFFu).toUByte())
        buffer.position(bodyEndPosition)
      }
    }
  }

  override fun wireSize(`value`: WavFmtChunk, context: EncodeContext): WireSize = when (val bodySize = WavFmtBodyCodec.wireSize(value.body, context)) {
    is WireSize.Exact -> WireSize.Exact(8 + bodySize.bytes)
    WireSize.BackPatch -> WireSize.BackPatch
  }

  override fun sizeHint(`value`: WavFmtChunk, context: EncodeContext): Int = 8 + WavFmtBodyCodec.sizeHint(value.body, context)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    __offset += 4
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    val bodyPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val bodyPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val bodyPrefixB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val bodyPrefixB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val bodyPrefix = (bodyPrefixB0 or (bodyPrefixB1 shl 8) or (bodyPrefixB2 shl 16) or (bodyPrefixB3 shl 24)).toUInt()
    if (bodyPrefix > (Int.MAX_VALUE - __offset - 4).toUInt()) {
      throw DecodeException(fieldPath = "WavFmtChunk.body", bufferPosition = baseOffset + __offset, expected = "__offset + 4 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 4 + bodyPrefix.toInt()}""")
    }
    __offset += 4 + bodyPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
