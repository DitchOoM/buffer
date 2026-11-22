package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object BigDispatchFrameTypeBCodec : Codec<BigDispatchFrame.TypeB> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BigDispatchFrame.TypeB {
    val __batch1Raw = buffer.readLong()
    val __batch1 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch1Raw else swapBytes(__batch1Raw)
    val x = (__batch1 ushr 56 and 0xFFL).toUByte()
    val y = (__batch1 ushr 48 and 0xFFL).toUByte()
    val z = (__batch1 ushr 32 and 0xFFFFL).toUShort()
    val w = (__batch1 and 0xFFFFFFFFL).toUInt()
    return BigDispatchFrame.TypeB(x = x, y = y, z = z, w = w)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BigDispatchFrame.TypeB,
    context: EncodeContext,
  ) {
    val __batch2 = ((value.x.toLong() and 0xFFL) shl 56) or ((value.y.toLong() and 0xFFL) shl 48) or ((value.z.toLong() and 0xFFFFL) shl 32) or (value.w.toLong() and 0xFFFFFFFFL)
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch2 else swapBytes(__batch2))
  }

  override fun wireBytes(`value`: BigDispatchFrame.TypeB, context: EncodeContext): WireByteCount = WireByteCount.exact(8L)

  override fun sizeHint(`value`: BigDispatchFrame.TypeB, context: EncodeContext): Int = 8

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 8L)
}
