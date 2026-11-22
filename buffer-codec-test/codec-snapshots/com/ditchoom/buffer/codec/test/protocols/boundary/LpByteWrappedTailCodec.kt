package com.ditchoom.buffer.codec.test.protocols.boundary

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
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object LpByteWrappedTailCodec : Codec<LpByteWrappedTail> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LpByteWrappedTail {
    val bodyPrefix = buffer.readUByte().toUInt()
    if (bodyPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = bodyPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val bodyLength = bodyPrefix.toInt()
    if (bodyLength > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "LpByteWrappedTail.body",
            bufferPosition = buffer.position(),
            expected = "a " + bodyLength + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
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
    WrappedLabelCodec.wireBytes(value.body, context).fold(
      exact = { bodyWireBytes ->
        if (bodyWireBytes > Int.MAX_VALUE) {
          throw EncodeException(fieldPath = "LpByteWrappedTail.body", reason = """encoded message byte length ${bodyWireBytes} exceeds Int.MAX_VALUE""")
        }
        val bodyByteCount = bodyWireBytes.toInt()
        if (bodyByteCount > 255) {
          throw EncodeException(fieldPath = "LpByteWrappedTail.body", reason = """encoded message byte length ${bodyByteCount} exceeds @LengthPrefixed(LengthPrefix.Byte) max 255""")
        }
        val bodyPrefix = bodyByteCount.toUInt()
        buffer.writeUByte((bodyPrefix and 0xFFu).toUByte())
        val bodyBodyStart = buffer.position()
        WrappedLabelCodec.encode(buffer, value.body, context)
        if (buffer.position() - bodyBodyStart != bodyByteCount) {
          throw EncodeException(fieldPath = "LpByteWrappedTail.body", reason = """wireBytes declared ${bodyByteCount} bytes but encode wrote ${buffer.position() - bodyBodyStart} — the codec's wireBytes and encode disagree""")
        }
      },
      backPatch = {
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
      },
    )
  }

  override fun wireBytes(`value`: LpByteWrappedTail, context: EncodeContext): WireByteCount {
    val __bodySize = WrappedLabelCodec.wireBytes(value.body, context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    return WireByteCount.exact(1L + __bodySize)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: LpByteWrappedTail, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: LpByteWrappedTail, context: EncodeContext): Int = 1 + WrappedLabelCodec.sizeHint(value.body, context)

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    val bodyPrefix = (stream.peekByte(baseOffset + __offset).toInt() and 0xFF).toUInt()
    if (bodyPrefix > (Int.MAX_VALUE - __offset - 1).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 1 + bodyPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 1 + bodyPrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
