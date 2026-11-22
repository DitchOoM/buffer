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

public object LpWrappedTailCodec : Codec<LpWrappedTail> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LpWrappedTail {
    val kind = buffer.readByte()
    val bodyPrefixB0 = buffer.readUByte().toUInt()
    val bodyPrefixB1 = buffer.readUByte().toUInt()
    val bodyPrefix = ((bodyPrefixB0 shl 8) or bodyPrefixB1)
    if (bodyPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = bodyPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val bodyLength = bodyPrefix.toInt()
    if (bodyLength > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "LpWrappedTail.body",
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
    return LpWrappedTail(kind = kind, body = body)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LpWrappedTail,
    context: EncodeContext,
  ) {
    buffer.writeByte(value.kind)
    WrappedLabelCodec.wireBytes(value.body, context).fold(
      exact = { bodyWireBytes ->
        if (bodyWireBytes > Int.MAX_VALUE) {
          throw EncodeException(fieldPath = "LpWrappedTail.body", reason = """encoded message byte length ${bodyWireBytes} exceeds Int.MAX_VALUE""")
        }
        val bodyByteCount = bodyWireBytes.toInt()
        if (bodyByteCount > 65_535) {
          throw EncodeException(fieldPath = "LpWrappedTail.body", reason = """encoded message byte length ${bodyByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
        }
        val bodyPrefix = bodyByteCount.toUInt()
        buffer.writeUByte(((bodyPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((bodyPrefix and 0xFFu).toUByte())
        val bodyBodyStart = buffer.position()
        WrappedLabelCodec.encode(buffer, value.body, context)
        if (buffer.position() - bodyBodyStart != bodyByteCount) {
          throw EncodeException(fieldPath = "LpWrappedTail.body", reason = """wireBytes declared ${bodyByteCount} bytes but encode wrote ${buffer.position() - bodyBodyStart} — the codec's wireBytes and encode disagree""")
        }
      },
      backPatch = {
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
      },
    )
  }

  override fun wireBytes(`value`: LpWrappedTail, context: EncodeContext): WireByteCount {
    val __bodySize = WrappedLabelCodec.wireBytes(value.body, context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    return WireByteCount.exact(3L + __bodySize)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: LpWrappedTail, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: LpWrappedTail, context: EncodeContext): Int = 3 + WrappedLabelCodec.sizeHint(value.body, context)

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val bodyPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val bodyPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val bodyPrefix = ((bodyPrefixB0 shl 8) or bodyPrefixB1).toUInt()
    if (bodyPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + bodyPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + bodyPrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
