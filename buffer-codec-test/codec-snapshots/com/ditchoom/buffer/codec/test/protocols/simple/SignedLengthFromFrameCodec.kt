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
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object SignedLengthFromFrameCodec : Codec<SignedLengthFromFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedLengthFromFrame {
    val __batch1Raw = buffer.readShort().toInt() and 0xFFFF
    val __batch1 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch1Raw else swapBytes(__batch1Raw.toShort()).toInt() and 0xFFFF
    val length = (__batch1 ushr 8 and 0xFF).toByte()
    val flags = (__batch1 and 0xFF).toUByte()
    if (length < 0) {
      throw FrameTooLargeException(declaredBytes = length.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val payload = buffer.readText(length.toInt(), (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    return SignedLengthFromFrame(length = length, flags = flags, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedLengthFromFrame,
    context: EncodeContext,
  ) {
    val __batch2 = (((value.length.toInt() and 0xFF) shl 8) or (value.flags.toInt() and 0xFF)).toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch2 else swapBytes(__batch2))
    buffer.writeText(value.payload, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
  }

  override fun wireBytes(`value`: SignedLengthFromFrame, context: EncodeContext): WireByteCount = WireByteCount.exact(2L + value.length.toInt())

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: SignedLengthFromFrame, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: SignedLengthFromFrame, context: EncodeContext): Int = 2 + value.payload.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    val length = stream.peekByte(baseOffset + __offset)
    __offset += 1
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (length < 0) {
      throw FrameTooLargeException(declaredBytes = length.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val payloadBytes = length.toInt()
    if (payloadBytes < 0 || payloadBytes > Int.MAX_VALUE - __offset) {
      throw FrameTooLargeException(declaredBytes = if (payloadBytes < 0) payloadBytes.toLong() else __offset.toLong() + payloadBytes, maxBytes = Int.MAX_VALUE)
    }
    __offset += payloadBytes
    return FrameSize.declared(__offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
