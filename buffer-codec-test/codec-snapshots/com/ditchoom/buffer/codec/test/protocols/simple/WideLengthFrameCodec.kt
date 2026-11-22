package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
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

public object WideLengthFrameCodec : Codec<WideLengthFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WideLengthFrame {
    val lengthRaw = buffer.readInt()
    val length = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) lengthRaw else swapBytes(lengthRaw)).toUInt()
    val flags = buffer.readUByte()
    if (length > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = length.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val payload = buffer.readText(length.toInt(), (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    return WideLengthFrame(length = length, flags = flags, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WideLengthFrame,
    context: EncodeContext,
  ) {
    val lengthRaw = value.length.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) lengthRaw else swapBytes(lengthRaw))
    buffer.writeUByte(value.flags)
    buffer.writeText(value.payload, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
  }

  override fun wireBytes(`value`: WideLengthFrame, context: EncodeContext): WireByteCount = WireByteCount.exact(5L + value.length.toLong())

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: WideLengthFrame, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: WideLengthFrame, context: EncodeContext): Int = 5 + value.payload.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 4) return FrameSize.NeedsMoreData
    val lengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val lengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val lengthB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val lengthB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val length = ((lengthB0 shl 24) or (lengthB1 shl 16) or (lengthB2 shl 8) or lengthB3).toUInt()
    __offset += 4
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (length > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = length.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val payloadBytes = length.toInt()
    if (payloadBytes < 0 || payloadBytes > Int.MAX_VALUE - __offset) {
      throw FrameTooLargeException(declaredBytes = if (payloadBytes < 0) payloadBytes.toLong() else __offset.toLong() + payloadBytes, maxBytes = Int.MAX_VALUE)
    }
    __offset += payloadBytes
    return FrameSize.declared(0, __offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
