package com.ditchoom.buffer.codec.test.protocols.boundary

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

public object WrappedLabelCodec : Codec<WrappedLabel> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WrappedLabel {
    val labelPrefixB0 = buffer.readUByte().toUInt()
    val labelPrefixB1 = buffer.readUByte().toUInt()
    val labelPrefix = ((labelPrefixB0 shl 8) or labelPrefixB1)
    if (labelPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = labelPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val labelLength = labelPrefix.toInt()
    val label = buffer.readText(labelLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    return WrappedLabel(label = label)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WrappedLabel,
    context: EncodeContext,
  ) {
    val labelSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val labelBodyStart = buffer.position()
    buffer.writeText(value.label, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val labelEndPosition = buffer.position()
    val labelByteCount = labelEndPosition - labelBodyStart
    if (labelByteCount > 65_535) {
      throw EncodeException(fieldPath = "WrappedLabel.label", reason = """UTF-8 byte length ${labelByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(labelSizePosition)
    val labelPrefix = labelByteCount.toUInt()
    buffer.writeUByte(((labelPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((labelPrefix and 0xFFu).toUByte())
    buffer.position(labelEndPosition)
  }

  override fun wireBytes(`value`: WrappedLabel, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: WrappedLabel, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: WrappedLabel, context: EncodeContext): Int = 2 + value.label.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val labelPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val labelPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val labelPrefix = ((labelPrefixB0 shl 8) or labelPrefixB1).toUInt()
    if (labelPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + labelPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + labelPrefix.toInt()
    return FrameSize.declared(__offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
