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

public object BigDispatchFrameTypeACodec : Codec<BigDispatchFrame.TypeA> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BigDispatchFrame.TypeA {
    val __batch1Raw = buffer.readLong()
    val __batch1 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch1Raw else swapBytes(__batch1Raw)
    val a = (__batch1 ushr 56 and 0xFFL).toUByte()
    val b = (__batch1 ushr 48 and 0xFFL).toUByte()
    val c = (__batch1 ushr 32 and 0xFFFFL).toUShort()
    val d = (__batch1 and 0xFFFFFFFFL).toUInt()
    return BigDispatchFrame.TypeA(a = a, b = b, c = c, d = d)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BigDispatchFrame.TypeA,
    context: EncodeContext,
  ) {
    val __batch2 = ((value.a.toLong() and 0xFFL) shl 56) or ((value.b.toLong() and 0xFFL) shl 48) or ((value.c.toLong() and 0xFFFFL) shl 32) or (value.d.toLong() and 0xFFFFFFFFL)
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch2 else swapBytes(__batch2))
  }

  override fun wireBytes(`value`: BigDispatchFrame.TypeA, context: EncodeContext): WireByteCount = WireByteCount.exact(8L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: BigDispatchFrame.TypeA, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: BigDispatchFrame.TypeA, context: EncodeContext): Int = 8

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 8L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
