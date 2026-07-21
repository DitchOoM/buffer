package com.ditchoom.buffer.codec.test.protocols.boundary

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

public object LpWrappedTailCodec : Codec<LpWrappedTail> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LpWrappedTail {
    val kind = buffer.readByte()
    val bodyPrefixB0 = buffer.readUByte().toUInt()
    val bodyPrefixB1 = buffer.readUByte().toUInt()
    val bodyPrefix = ((bodyPrefixB0 shl 8) or bodyPrefixB1)
    if (bodyPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "LpWrappedTail.body", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = bodyPrefix.toString())
    }
    val bodyLength = bodyPrefix.toInt()
    val bodyOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + bodyLength)
    val body = try {
      WrappedLabelCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(bodyOuterLimit)
    }
    return LpWrappedTail(kind = kind, body = body)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LpWrappedTail,
    context: EncodeContext,
  ) {
    buffer.writeByte(value.kind)
    when (val bodyWireSize = WrappedLabelCodec.wireSize(value.body, context)) {
      is WireSize.Exact -> {
        val bodyByteCount = bodyWireSize.bytes
        if (bodyByteCount > 65_535) {
          throw EncodeException(fieldPath = "LpWrappedTail.body", reason = """encoded message byte length ${bodyByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
        }
        val bodyPrefix = bodyByteCount.toUInt()
        buffer.writeUByte(((bodyPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((bodyPrefix and 0xFFu).toUByte())
        val bodyBodyStart = buffer.position()
        WrappedLabelCodec.encode(buffer, value.body, context)
        if (buffer.position() - bodyBodyStart != bodyByteCount) {
          throw EncodeException(fieldPath = "LpWrappedTail.body", reason = """wireSize declared ${bodyByteCount} bytes but encode wrote ${buffer.position() - bodyBodyStart} — the codec's wireSize and encode disagree""")
        }
      }
      WireSize.BackPatch -> {
        val bodySizePosition = buffer.position()
        repeat(2) { buffer.writeUByte(0u) }
        val bodyBodyStart = buffer.position()
        WrappedLabelCodec.encode(buffer, value.body, context)
        val bodyEndPosition = buffer.position()
        val bodyPatchByteCount = bodyEndPosition - bodyBodyStart
        if (bodyPatchByteCount > 65_535) {
          throw EncodeException(fieldPath = "LpWrappedTail.body", reason = """encoded message byte length ${bodyPatchByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
        }
        buffer.position(bodySizePosition)
        val bodyPatchPrefix = bodyPatchByteCount.toUInt()
        buffer.writeUByte(((bodyPatchPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((bodyPatchPrefix and 0xFFu).toUByte())
        buffer.position(bodyEndPosition)
      }
    }
  }

  override fun wireSize(`value`: LpWrappedTail, context: EncodeContext): WireSize = when (val bodySize = WrappedLabelCodec.wireSize(value.body, context)) {
    is WireSize.Exact -> WireSize.Exact(3 + bodySize.bytes)
    WireSize.BackPatch -> WireSize.BackPatch
  }

  override fun sizeHint(`value`: LpWrappedTail, context: EncodeContext): Int = 3 + WrappedLabelCodec.sizeHint(value.body, context)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val bodyPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val bodyPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val bodyPrefix = ((bodyPrefixB0 shl 8) or bodyPrefixB1).toUInt()
    if (bodyPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "LpWrappedTail.body", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + bodyPrefix.toInt()}""")
    }
    __offset += 2 + bodyPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
