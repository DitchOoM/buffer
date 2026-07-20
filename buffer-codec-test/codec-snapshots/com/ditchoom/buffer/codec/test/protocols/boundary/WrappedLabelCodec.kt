package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.Charset
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

public object WrappedLabelCodec : Codec<WrappedLabel> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WrappedLabel {
    val labelPrefixB0 = buffer.readUByte().toUInt()
    val labelPrefixB1 = buffer.readUByte().toUInt()
    val labelPrefix = ((labelPrefixB0 shl 8) or labelPrefixB1)
    if (labelPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "WrappedLabel.label", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = labelPrefix.toString())
    }
    val labelLength = labelPrefix.toInt()
    val label = buffer.readString(labelLength, Charset.UTF8)
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
    buffer.writeString(value.label, Charset.UTF8)
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

  override fun wireSize(`value`: WrappedLabel, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val labelPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val labelPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val labelPrefix = ((labelPrefixB0 shl 8) or labelPrefixB1).toUInt()
    if (labelPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "WrappedLabel.label", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + labelPrefix.toInt()}""")
    }
    __offset += 2 + labelPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
