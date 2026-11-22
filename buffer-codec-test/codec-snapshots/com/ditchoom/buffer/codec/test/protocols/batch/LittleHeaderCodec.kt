package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
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

public object LittleHeaderCodec : Codec<LittleHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LittleHeader {
    val __batch1Raw = buffer.readLong()
    val __batch1 = if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch1Raw else swapBytes(__batch1Raw)
    val type = (__batch1 and 0xFFL).toUByte()
    val version = (__batch1 ushr 8 and 0xFFL).toUByte()
    val flags = (__batch1 ushr 16 and 0xFFFFL).toUShort()
    val length = (__batch1 ushr 32 and 0xFFFFFFFFL).toUInt()
    return LittleHeader(type = type, version = version, flags = flags, length = length)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LittleHeader,
    context: EncodeContext,
  ) {
    val __batch2 = (value.type.toLong() and 0xFFL) or ((value.version.toLong() and 0xFFL) shl 8) or ((value.flags.toLong() and 0xFFFFL) shl 16) or ((value.length.toLong() and 0xFFFFFFFFL) shl 32)
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch2 else swapBytes(__batch2))
  }

  override fun wireBytes(`value`: LittleHeader, context: EncodeContext): WireByteCount = WireByteCount.exact(8L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: LittleHeader, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: LittleHeader, context: EncodeContext): Int = 8

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 8L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
