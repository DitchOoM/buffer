package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

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

  override fun wireSize(`value`: BigDispatchFrame.TypeA, context: EncodeContext): WireSize = WireSize.Exact(8)

  override fun sizeHint(`value`: BigDispatchFrame.TypeA, context: EncodeContext): Int = 8

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 8) PeekResult.Complete(8) else PeekResult.NeedsMoreData
}
