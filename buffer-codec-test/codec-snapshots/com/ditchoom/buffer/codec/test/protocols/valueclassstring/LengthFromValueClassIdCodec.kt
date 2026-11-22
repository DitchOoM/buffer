package com.ditchoom.buffer.codec.test.protocols.valueclassstring

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
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object LengthFromValueClassIdCodec : Codec<LengthFromValueClassId> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LengthFromValueClassId {
    val __batch1 = buffer.readShort().toInt() and 0xFFFF
    val len: kotlin.UByte
    val flags: kotlin.Byte
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      len = (__batch1 ushr 8 and 0xFF).toUByte()
      flags = (__batch1 and 0xFF).toByte()
    } else {
      len = (__batch1 and 0xFF).toUByte()
      flags = (__batch1 ushr 8 and 0xFF).toByte()
    }
    val payload = UserId(buffer.readText(len.toInt(), (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY)))
    return LengthFromValueClassId(len = len, flags = flags, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LengthFromValueClassId,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeShort((((value.len.toInt() and 0xFF) shl 8) or (value.flags.toInt() and 0xFF)).toShort())
    } else {
      buffer.writeShort(((value.len.toInt() and 0xFF) or ((value.flags.toInt() and 0xFF) shl 8)).toShort())
    }
    buffer.writeText(value.payload.value, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
  }

  override fun wireBytes(`value`: LengthFromValueClassId, context: EncodeContext): WireByteCount = WireByteCount.exact(2L + value.len.toLong())

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: LengthFromValueClassId, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: LengthFromValueClassId, context: EncodeContext): Int = 2 + value.payload.value.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    val len = stream.peekByte(baseOffset + __offset).toUByte()
    __offset += 1
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    val payloadBytes = len.toInt()
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
