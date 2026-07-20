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

public object LpByteWrappedTailCodec : Codec<LpByteWrappedTail> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LpByteWrappedTail {
    val bodyPrefix = buffer.readUByte().toUInt()
    if (bodyPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "LpByteWrappedTail.body", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = bodyPrefix.toString())
    }
    val bodyLength = bodyPrefix.toInt()
    val bodyOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + bodyLength)
    val body = try {
      WrappedLabelCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(bodyOuterLimit)
    }
    return LpByteWrappedTail(body = body)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LpByteWrappedTail,
    context: EncodeContext,
  ) {
    when (val bodyWireSize = WrappedLabelCodec.wireSize(value.body, context)) {
      is WireSize.Exact -> {
        val bodyByteCount = bodyWireSize.bytes
        if (bodyByteCount > 255) {
          throw EncodeException(fieldPath = "LpByteWrappedTail.body", reason = """encoded message byte length ${bodyByteCount} exceeds @LengthPrefixed(LengthPrefix.Byte) max 255""")
        }
        val bodyPrefix = bodyByteCount.toUInt()
        buffer.writeUByte((bodyPrefix and 0xFFu).toUByte())
        WrappedLabelCodec.encode(buffer, value.body, context)
      }
      WireSize.BackPatch -> {
        val bodySizePosition = buffer.position()
        repeat(1) { buffer.writeUByte(0u) }
        val bodyBodyStart = buffer.position()
        WrappedLabelCodec.encode(buffer, value.body, context)
        val bodyEndPosition = buffer.position()
        val bodyPatchByteCount = bodyEndPosition - bodyBodyStart
        if (bodyPatchByteCount > 255) {
          throw EncodeException(fieldPath = "LpByteWrappedTail.body", reason = """encoded message byte length ${bodyPatchByteCount} exceeds @LengthPrefixed(LengthPrefix.Byte) max 255""")
        }
        buffer.position(bodySizePosition)
        val bodyPatchPrefix = bodyPatchByteCount.toUInt()
        buffer.writeUByte((bodyPatchPrefix and 0xFFu).toUByte())
        buffer.position(bodyEndPosition)
      }
    }
  }

  override fun wireSize(`value`: LpByteWrappedTail, context: EncodeContext): WireSize = when (val bodySize = WrappedLabelCodec.wireSize(value.body, context)) {
    is WireSize.Exact -> WireSize.Exact(1 + bodySize.bytes)
    WireSize.BackPatch -> WireSize.BackPatch
  }

  override fun sizeHint(`value`: LpByteWrappedTail, context: EncodeContext): Int = 1 + WrappedLabelCodec.sizeHint(value.body, context)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    val bodyPrefix = (stream.peekByte(baseOffset + __offset).toInt() and 0xFF).toUInt()
    if (bodyPrefix > (Int.MAX_VALUE - __offset - 1).toUInt()) {
      throw DecodeException(fieldPath = "LpByteWrappedTail.body", bufferPosition = baseOffset + __offset, expected = "__offset + 1 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 1 + bodyPrefix.toInt()}""")
    }
    __offset += 1 + bodyPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
