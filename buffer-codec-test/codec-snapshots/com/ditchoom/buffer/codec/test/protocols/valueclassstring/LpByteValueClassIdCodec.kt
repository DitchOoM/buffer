package com.ditchoom.buffer.codec.test.protocols.valueclassstring

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object LpByteValueClassIdCodec : Codec<LpByteValueClassId> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LpByteValueClassId {
    val idPrefix = buffer.readUByte().toUInt()
    if (idPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = idPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val idLength = idPrefix.toInt()
    val id = UserId(buffer.readText(idLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY)))
    return LpByteValueClassId(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LpByteValueClassId,
    context: EncodeContext,
  ) {
    val idSizePosition = buffer.position()
    repeat(1) { buffer.writeUByte(0u) }
    val idBodyStart = buffer.position()
    buffer.writeText(value.id.value, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val idEndPosition = buffer.position()
    val idByteCount = idEndPosition - idBodyStart
    if (idByteCount > 255) {
      throw EncodeException(fieldPath = "LpByteValueClassId.id", reason = """UTF-8 byte length ${idByteCount} exceeds @LengthPrefixed(LengthPrefix.Byte) max 255""")
    }
    buffer.position(idSizePosition)
    val idPrefix = idByteCount.toUInt()
    buffer.writeUByte((idPrefix and 0xFFu).toUByte())
    buffer.position(idEndPosition)
  }

  override fun wireBytes(`value`: LpByteValueClassId, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: LpByteValueClassId, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: LpByteValueClassId, context: EncodeContext): Int = 1 + value.id.value.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    val idPrefix = (stream.peekByte(baseOffset + __offset).toInt() and 0xFF).toUInt()
    if (idPrefix > (Int.MAX_VALUE - __offset - 1).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 1 + idPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 1 + idPrefix.toInt()
    return FrameSize.declared(__offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
