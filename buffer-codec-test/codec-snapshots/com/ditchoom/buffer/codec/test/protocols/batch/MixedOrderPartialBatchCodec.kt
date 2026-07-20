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

public object MixedOrderPartialBatchCodec : Codec<MixedOrderPartialBatch> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MixedOrderPartialBatch {
    val __batch1Raw = buffer.readInt()
    val __batch1 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch1Raw else swapBytes(__batch1Raw)
    val bigA = (__batch1 ushr 16 and 0xFFFF).toUShort()
    val bigB = (__batch1 and 0xFFFF).toUShort()
    val trailingLittleRaw = buffer.readInt()
    val trailingLittle = (if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) trailingLittleRaw else swapBytes(trailingLittleRaw)).toUInt()
    return MixedOrderPartialBatch(bigA = bigA, bigB = bigB, trailingLittle = trailingLittle)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MixedOrderPartialBatch,
    context: EncodeContext,
  ) {
    val __batch2 = ((value.bigA.toInt() and 0xFFFF) shl 16) or (value.bigB.toInt() and 0xFFFF)
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch2 else swapBytes(__batch2))
    val trailingLittleRaw = value.trailingLittle.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) trailingLittleRaw else swapBytes(trailingLittleRaw))
  }

  override fun wireSize(`value`: MixedOrderPartialBatch, context: EncodeContext): WireSize = WireSize.Exact(8)

  override fun sizeHint(`value`: MixedOrderPartialBatch, context: EncodeContext): Int = 8

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 8) PeekResult.Complete(8) else PeekResult.NeedsMoreData
}
